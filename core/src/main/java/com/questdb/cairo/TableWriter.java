package com.questdb.cairo;

import com.questdb.PartitionBy;
import com.questdb.factory.configuration.ColumnMetadata;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.misc.Files;
import com.questdb.misc.Numbers;
import com.questdb.misc.Unsafe;
import com.questdb.std.LongList;
import com.questdb.std.ObjList;
import com.questdb.std.VirtualMemory;
import com.questdb.std.str.CompositePath;
import com.questdb.std.str.LPSZ;
import com.questdb.std.str.Path;
import com.questdb.store.ColumnType;
import com.questdb.store.SymbolTable;

import java.util.function.LongSupplier;

public class TableWriter {

    private static final int PARTITION_INDEX_HEADER_LENGTH = 8;

    private final JournalMetadata metadata;
    private final ObjList<AppendMemory> columns = new ObjList<>();
    private final CompositePath path;
    private final LongList refs = new LongList();
    private final Row row = new Row();
    private final int rootLen;
    private final ReadWriteMemory partitionIndexMem = new ReadWriteMemory();
    private long masterRef = 0;
    private int columnCount;
    private Runnable[] nullers;
    private int mode = 509;
    private LongSupplier sizer;

    public TableWriter(CharSequence location, JournalMetadata metadata) {
        this.metadata = metadata;
        this.columnCount = metadata.getColumnCount();
        this.refs.extendAndSet(columnCount, 0);
        this.nullers = new Runnable[columnCount];
        this.path = new CompositePath().of(location).concat(metadata.getName());
        this.rootLen = path.length();
        configureColumnMemory();

        if (metadata.getPartitionBy() == PartitionBy.NONE || metadata.getPartitionBy() == PartitionBy.DEFAULT) {
            switchPartition("default");
        }
        path.trimTo(rootLen);
    }

    public void commit() {
        partitionIndexMem.jumpTo(8);
        for (int i = 0; i < columnCount; i++) {
            partitionIndexMem.putLong(getPrimaryColumn(i).size());

            VirtualMemory m = getSecondaryColumn(i);
            if (m == null) {
                partitionIndexMem.skip(8);
            } else {
                partitionIndexMem.putLong(m.size());
            }
        }
    }

    public Row newRow() {
        masterRef++;
        return row;
    }

    public void truncate() {
        for (int i = 0; i < columnCount; i++) {
            getPrimaryColumn(i).truncate();
            AppendMemory mem = getSecondaryColumn(i);
            if (mem != null) {
                mem.truncate();
            }
        }
    }

    private static long primaryColumnSizeOffset(int column) {
        return column * 16 + PARTITION_INDEX_HEADER_LENGTH;
    }

    private static long secondaryColumnSizeOffset(int column) {
        return primaryColumnSizeOffset(column) + 8;
    }

    private void configureColumnMemory() {
        for (int i = 0; i < columnCount; i++) {
            ColumnMetadata m = metadata.getColumnQuick(i);
            columns.add(new AppendMemory());

            if (m.type == ColumnType.BINARY || m.type == ColumnType.STRING || m.type == ColumnType.SYMBOL) {
                columns.add(new AppendMemory());
            } else {
                columns.add(null);
            }
        }
    }

    private void configureNuller(int index, int type, AppendMemory mem1, AppendMemory mem2) {
        switch (type) {
            case ColumnType.STRING:
                nullers[index] = () -> mem2.putLong(mem1.putNullStr());
                break;
            case ColumnType.SYMBOL:
                nullers[index] = () -> mem1.putInt(SymbolTable.VALUE_IS_NULL);
                break;
            case ColumnType.INT:
                nullers[index] = () -> mem1.putInt(Numbers.INT_NaN);
                break;
            case ColumnType.FLOAT:
                nullers[index] = () -> mem1.putFloat(Float.NaN);
                break;
            case ColumnType.DOUBLE:
                nullers[index] = () -> mem1.putDouble(Double.NaN);
                break;
            case ColumnType.LONG:
            case ColumnType.DATE:
                nullers[index] = () -> mem1.putLong(Numbers.LONG_NaN);
                break;
            case ColumnType.BINARY:
                nullers[index] = () -> mem2.putLong(mem1.putNullBin());
                break;
            default:
                break;
        }
    }

    private AppendMemory getPrimaryColumn(int column) {
        return columns.getQuick(column * 2);
    }

    private AppendMemory getSecondaryColumn(int column) {
        return columns.getQuick(column * 2 + 1);
    }

    private void switchPartition(CharSequence name) {
        path.concat(name);
        int partitionPathLen = path.length();

        if (Files.mkdirs(path.put(Path.SEPARATOR).$(), mode) != 0) {
            throw new RuntimeException("cannot create def partition");
        }

        path.trimTo(partitionPathLen);
        switchPartitionIndex(path.concat("_index.qdb").$());

        assert columnCount > 0;

        for (int i = 0; i < columnCount; i++) {
            ColumnMetadata m = metadata.getColumnQuick(i);
            AppendMemory mem1 = getPrimaryColumn(i);
            AppendMemory mem2 = getSecondaryColumn(i);

            path.trimTo(partitionPathLen);
            mem1.of(path.concat(m.name).put(".d").$(), 4096 * 4096, partitionIndexMem.getLong(primaryColumnSizeOffset(i)));

            if (mem2 != null) {
                path.trimTo(partitionPathLen);
                mem2.of(path.concat(m.name).put(".i").$(), 4096 * 4096, partitionIndexMem.getLong(secondaryColumnSizeOffset(i)));
            } else {
                columns.add(null);
            }
            // set nullers
            configureNuller(i, m.type, mem1, mem2);
            path.trimTo(rootLen);
        }
    }

    private void switchPartitionIndex(LPSZ path) {
        boolean exists = Files.exists(path);
        partitionIndexMem.of(path, (int) Files.PAGE_SIZE, 0L, (int) Files.PAGE_SIZE);
        if (exists) {
            if (partitionIndexMem.getInt(0) != 0xdeadbeef) {
                partitionIndexMem.close();
                throw new RuntimeException("bad header");
            }

            if (partitionIndexMem.getInt(4) != columnCount) {
                partitionIndexMem.close();
                throw new RuntimeException("Column count mismatch");
            }
        } else {
            partitionIndexMem.putInt(0xdeadbeef);
            partitionIndexMem.putInt(columnCount);
            // reserve space for column sizes and their initial row counts
            // note: there are two files for string and binary columns, so we keep two 64bit values for each logical column in the table.
            // note: to save disk space initial row count may be non-zero if column added later on

            // struct {
            //      int tag;
            //      int columnCount;
            //      COLUMN_SIZE sizes[columnCount];
            //      long firstRows[columnCount];
            // }
            // typedef struct {
            //      long file1Size;
            //      long file2Size;
            // } COLUMN_SIZE

            partitionIndexMem.skip(columnCount * 8 * 3);
        }
    }

    public class Row {
        public void append() {
            for (int i = 0; i < columnCount; i++) {
                if (refs.getQuick(i) < masterRef) {
                    Unsafe.arrayGet(nullers, i).run();
                }
            }
        }

        public void putDate(int index, long value) {
            putLong(index, value);
        }

        public void putDouble(int index, double value) {
            getPrimaryColumn(index).putDouble(value);
            refs.setQuick(index, masterRef);
        }

        public void putInt(int index, int value) {
            getPrimaryColumn(index).putInt(value);
            refs.setQuick(index, masterRef);
        }

        public void putLong(int index, long value) {
            getPrimaryColumn(index).putLong(value);
            refs.setQuick(index, masterRef);
        }

        public void putStr(int index, CharSequence value) {
            long r = getPrimaryColumn(index).putStr(value);
            getSecondaryColumn(index).putLong(r);
            refs.setQuick(index, masterRef);
        }
    }
}