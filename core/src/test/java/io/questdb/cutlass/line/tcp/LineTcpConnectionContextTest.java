package io.questdb.cutlass.line.tcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import io.questdb.cairo.AbstractCairoTest;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoTestUtils;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.PartitionBy;
import io.questdb.cairo.TableModel;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.TableReaderRecordCursor;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.WorkerPool;
import io.questdb.mp.WorkerPoolConfiguration;
import io.questdb.network.IODispatcher;
import io.questdb.network.IORequestProcessor;
import io.questdb.network.NetworkFacade;
import io.questdb.network.NetworkFacadeImpl;
import io.questdb.std.Unsafe;
import io.questdb.test.tools.TestUtils;

public class LineTcpConnectionContextTest extends AbstractCairoTest {
    private final static Log LOG = LogFactory.getLog(LineTcpConnectionContextTest.class);
    private static final int FD = 1_000_000;
    private LineTcpConnectionContext context;
    private LineTcpReceiverConfiguration lineTcpConfiguration;
    private LineTcpMeasurementScheduler scheduler;
    private boolean disconnected;
    private String recvBuffer;
    private int nWriterThreads;
    private WorkerPool workerPool;

    @Test
    public void testSingleMeasurement() throws Exception {
        runInContext(() -> {
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n";
            context.handleIO();
            Assert.assertFalse(disconnected);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testMultipleMeasurements1() throws Exception {
        runInContext(() -> {
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast temperature=81 1465839830101400200\n" +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            context.handleIO();
            Assert.assertFalse(disconnected);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testMultipleMeasurements2() throws Exception {
        runInContext(() -> {
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast temperature=81 1465839830101400200\n";
            context.handleIO();
            Assert.assertFalse(disconnected);
            recvBuffer = "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            context.handleIO();
            Assert.assertFalse(disconnected);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testExtremeFragmentation() throws Exception {
        runInContext(() -> {
            String allMsgs = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast temperature=81 1465839830101400200\n" +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            int n = 0;
            while (n < allMsgs.length()) {
                recvBuffer = allMsgs.substring(n, n + 1);
                n++;
                context.handleIO();
                Assert.assertFalse(disconnected);
            }
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testFragmentation1() throws Exception {
        testFragmentation("weat".length());
    }

    @Test
    public void testFragmentation2() throws Exception {
        testFragmentation("weather".length());
    }

    @Test
    public void testFragmentation3() throws Exception {
        testFragmentation("weather,".length());
    }

    @Test
    public void testFragmentation4() throws Exception {
        testFragmentation("weather,locat".length());
    }

    @Test
    public void testFragmentation5() throws Exception {
        testFragmentation("weather,location".length());
    }

    @Test
    public void testFragmentation6() throws Exception {
        testFragmentation("weather,location=".length());
    }

    @Test
    public void testFragmentation7() throws Exception {
        testFragmentation("weather,location=us-midw".length());
    }

    @Test
    public void testFragmentation8() throws Exception {
        testFragmentation("weather,location=us-midwest".length());
    }

    @Test
    public void testFragmentation9() throws Exception {
        testFragmentation("weather,location=us-midwest ".length());
    }

    @Test
    public void testFragmentation10() throws Exception {
        testFragmentation("weather,location=us-midwest tempera".length());
    }

    @Test
    public void testFragmentation11() throws Exception {
        testFragmentation("weather,location=us-midwest temperature".length());
    }

    @Test
    public void testFragmentation12() throws Exception {
        testFragmentation("weather,location=us-midwest temperature=".length());
    }

    @Test
    public void testFragmentation13() throws Exception {
        testFragmentation("weather,location=us-midwest temperature=8".length());
    }

    @Test
    public void testFragmentation14() throws Exception {
        testFragmentation("weather,location=us-midwest temperature=82".length());
    }

    @Test
    public void testFragmentation15() throws Exception {
        testFragmentation("weather,location=us-midwest temperature=82 ".length());
    }

    @Test
    public void testFragmentation16() throws Exception {
        testFragmentation("weather,location=us-midwest temperature=82 1465839830100400".length());
    }

    @Test
    public void testFragmentation17() throws Exception {
        testFragmentation("weather,location=us-midwest temperature=82 1465839830100400200".length());
    }

    @Test
    public void testMaxSizes() throws Exception {
        runInContext(() -> {
            String longMeasurement = "weather,location=us-eastcoastxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx temperature=81 1465839830101400200\n";
            Assert.assertFalse(longMeasurement.length() < lineTcpConfiguration.getMaxMeasurementSize());
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    longMeasurement +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            Assert.assertFalse(recvBuffer.length() < lineTcpConfiguration.getNetMsgBufferSize());
            do {
                context.handleIO();
                Assert.assertFalse(disconnected);
            } while (recvBuffer.length() > 0);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\n" +
                    "us-eastcoastxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\t81.0\t2016-06-13T17:43:50.101400Z\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testAddTagColumn() throws Exception {
        runInContext(() -> {
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast,city=york temperature=81 1465839830101400200\n" +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            do {
                context.handleIO();
                Assert.assertFalse(disconnected);
            } while (recvBuffer.length() > 0);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\tcity\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\t\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\t\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\tyork\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\t\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\t\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\t\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\t\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testAddFieldColumn() throws Exception {
        runInContext(() -> {
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast temperature=81,humidity=23 1465839830101400200\n" +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            do {
                context.handleIO();
                Assert.assertFalse(disconnected);
            } while (recvBuffer.length() > 0);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\thumidity\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\tNaN\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\tNaN\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\t23.0\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\tNaN\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\tNaN\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\tNaN\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\tNaN\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testAddToExistingTable() throws Exception {
        addTable("weather");
        runInContext(() -> {
            recvBuffer = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast temperature=81 1465839830101400200\n" +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            do {
                context.handleIO();
                Assert.assertFalse(disconnected);
            } while (recvBuffer.length() > 0);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testMultiplTablesWithSingleWriterThread() throws Exception {
        nWriterThreads = 1;
        int nTables = 3;
        int nIterations = 20_000;
        testThreading(nTables, nIterations);
    }

    @Test
    public void testMultiplTablesWithMultipleWriterThreads() throws Exception {
        nWriterThreads = 5;
        int nTables = 12;
        int nIterations = 20_000;
        testThreading(nTables, nIterations);
    }

    private void testThreading(int nTables, int nIterations) throws Exception {
        Random random = new Random(0);
        int countByTable[] = new int[nTables];
        long maxTimestampByTable[] = new long[nTables];
        runInContext(() -> {
            long timestamp = 1465839830100400200l;
            for (int nIter = 0; nIter < nIterations; nIter++) {
                int nLines = random.nextInt(50) + 1;
                recvBuffer = "";
                for (int nLine = 0; nLine < nLines; nLine++) {
                    int nTable = random.nextInt(nTables);
                    double temperature = 50.0 + (random.nextInt(500) / 10.0);
                    recvBuffer += "weather" + nTable + ",location=us-midwest temperature=" + temperature + " " + timestamp + "\n";
                    countByTable[nTable]++;
                    maxTimestampByTable[nTable] = timestamp;
                    timestamp += 1000;
                }
                do {
                    context.handleIO();
                    Assert.assertFalse(disconnected);
                } while (recvBuffer.length() > 0);
            }
            waitForIOCompletion();
            closeContext();
            for (int nTable = 0; nTable < nTables; nTable++) {
                assertTableCount("weather" + nTable, countByTable[nTable], maxTimestampByTable[nTable]);
            }
        });
    }

    private void testFragmentation(int breakPos) throws Exception {
        runInContext(() -> {
            String allMsgs = "weather,location=us-midwest temperature=82 1465839830100400200\n" +
                    "weather,location=us-midwest temperature=83 1465839830100500200\n" +
                    "weather,location=us-eastcoast temperature=81 1465839830101400200\n" +
                    "weather,location=us-midwest temperature=85 1465839830102300200\n" +
                    "weather,location=us-eastcoast temperature=89 1465839830102400200\n" +
                    "weather,location=us-eastcoast temperature=80 1465839830102400200\n" +
                    "weather,location=us-westcost temperature=82 1465839830102500200\n";
            recvBuffer = allMsgs.substring(0, breakPos);
            context.handleIO();
            Assert.assertFalse(disconnected);
            recvBuffer = allMsgs.substring(breakPos);
            context.handleIO();
            Assert.assertFalse(disconnected);
            waitForIOCompletion();
            closeContext();
            String expected = "location\ttemperature\ttimestamp\n" +
                    "us-midwest\t82.0\t2016-06-13T17:43:50.100400Z\n" +
                    "us-midwest\t83.0\t2016-06-13T17:43:50.100500Z\n" +
                    "us-eastcoast\t81.0\t2016-06-13T17:43:50.101400Z\n" +
                    "us-midwest\t85.0\t2016-06-13T17:43:50.102300Z\n" +
                    "us-eastcoast\t89.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-eastcoast\t80.0\t2016-06-13T17:43:50.102400Z\n" +
                    "us-westcost\t82.0\t2016-06-13T17:43:50.102500Z\n";
            assertTable(expected, "weather");
        });
    }

    @Test
    public void testEmptyLine() throws Exception {
        runInContext(() -> {
            recvBuffer = "\n";
            context.handleIO();
            Assert.assertFalse(disconnected);
        });
    }

    @Test
    public void testOverflow() throws Exception {
        runInContext(() -> {
            int msgBufferSize = lineTcpConfiguration.getNetMsgBufferSize();
            recvBuffer = "A";
            while (recvBuffer.length() <= msgBufferSize) {
                recvBuffer += recvBuffer;
            }
            int nUnread = recvBuffer.length() - msgBufferSize;
            context.handleIO();
            Assert.assertTrue(disconnected);
            Assert.assertEquals(nUnread, recvBuffer.length());
        });
    }

    private void waitForIOCompletion() {
        int maxIterations = 256;
        recvBuffer = null;
        // Guard against slow writers on disconnect
        while (maxIterations-- > 0) {
            boolean busy = context.handleIO();
            if (!busy) {
                break;
            }
            LockSupport.parkNanos(1_000_000);
        }
        Assert.assertTrue(maxIterations > 0);
        Assert.assertTrue(disconnected);
    }

    private void runInContext(Runnable r) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (CairoEngine engine = new CairoEngine(configuration, null)) {
                setupContext(engine);
                try {
                    r.run();
                } finally {
                    closeContext();
                    engine.releaseAllWriters();
                    engine.releaseAllReaders();
                }
            }
        });
    }

    private void assertTableCount(CharSequence tableName, int nExpectedRows, long maxExpectedTimestampNanos) {
        try (TableReader reader = new TableReader(configuration, tableName)) {
            Assert.assertEquals(maxExpectedTimestampNanos / 1000, reader.getMaxTimestamp());
            TableReaderRecordCursor recordCursor = reader.getCursor();
            int nRows = 0;
            while (recordCursor.hasNext()) {
                nRows++;
            }
            Assert.assertEquals(nExpectedRows, nRows);
        }
    }

    private void addTable(String tableName) {
        try (@SuppressWarnings("resource")
        TableModel model = new TableModel(configuration, tableName,
                PartitionBy.NONE).col("location", ColumnType.SYMBOL).col("temperature", ColumnType.DOUBLE).timestamp()) {
            CairoTestUtils.create(model);
        }
    }

    private void assertTable(CharSequence expected, CharSequence tableName) {
        try (TableReader reader = new TableReader(configuration, tableName)) {
            assertThat(expected, reader.getCursor(), reader.getMetadata(), true);
        }
    }

    @Before
    public void before() {
        NetworkFacade nf = new NetworkFacadeImpl() {
            @Override
            public int recv(long fd, long buffer, int bufferLen) {
                Assert.assertEquals(FD, fd);
                if (null == recvBuffer) {
                    return -1;
                }

                byte[] bytes = recvBuffer.getBytes(StandardCharsets.UTF_8);
                int n = 0;
                while (n < bufferLen && n < bytes.length) {
                    Unsafe.getUnsafe().putByte(buffer++, bytes[n++]);
                }
                recvBuffer = new String(bytes, n, bytes.length - n);
                return n;
            }
        };
        nWriterThreads = 2;
        lineTcpConfiguration = new DefaultLineTcpReceiverConfiguration() {
            @Override
            public NetworkFacade getNetworkFacade() {
                return nf;
            }

            @Override
            public int getNetMsgBufferSize() {
                return 512;
            }

            @Override
            public int getMaxMeasurementSize() {
                return 128;
            }
        };
        // Adding this table prevents the memory leak detection from breaking when tests are not run individually
        // addTable("test1");
        // addTable("test1234567890");
        // addTable("test12345678901234567890");
        // addTable("test123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890");
    }

    private void setupContext(CairoEngine engine) {
        workerPool = new WorkerPool(new WorkerPoolConfiguration() {
            private final int workerCount;
            private final int[] affinityByThread;
            {
                workerCount = nWriterThreads;
                affinityByThread = new int[workerCount];
                Arrays.fill(affinityByThread, -1);
            }

            @Override
            public boolean haltOnError() {
                return false;
            }

            @Override
            public int getWorkerCount() {
                return workerCount;
            }

            @Override
            public int[] getWorkerAffinity() {
                return affinityByThread;
            }
        });
        scheduler = new LineTcpMeasurementScheduler(configuration, lineTcpConfiguration, engine, workerPool);
        context = new LineTcpConnectionContext(lineTcpConfiguration, scheduler);
        disconnected = false;
        recvBuffer = null;
        IODispatcher<LineTcpConnectionContext> dispatcher = new IODispatcher<LineTcpConnectionContext>() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public boolean run(int workerId) {
                return false;
            }

            @Override
            public int getConnectionCount() {
                return 0;
            }

            @Override
            public void registerChannel(LineTcpConnectionContext context, int operation) {
            }

            @Override
            public boolean processIOQueue(IORequestProcessor<LineTcpConnectionContext> processor) {
                return false;
            }

            @Override
            public void disconnect(LineTcpConnectionContext context) {
                disconnected = true;
            }
        };
        context.of(FD, dispatcher);
        workerPool.start(LOG);
    }

    private void closeContext() {
        if (null != scheduler) {
            workerPool.halt();
            context.close();
            context = null;
            scheduler.close();
            scheduler = null;
        }
    }
}