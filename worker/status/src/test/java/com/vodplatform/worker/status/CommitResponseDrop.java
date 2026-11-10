package com.vodplatform.worker.status;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;

/** One connection to the disposable loopback PostgreSQL server, cut at its COMMIT CommandComplete. */
final class CommitResponseDrop implements AutoCloseable {
    private static final String LOOPBACK = "127.0.0.1";
    private static final byte[] COMMIT = "COMMIT\0".getBytes(StandardCharsets.US_ASCII);
    private final ServerSocket server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Future<Boolean> dropped;

    CommitResponseDrop(int postgresPort) throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK));
        dropped = threads.submit(() -> relay(postgresPort));
    }

    PGSimpleDataSource dataSource(PGSimpleDataSource target) {
        var proxy = new PGSimpleDataSource();
        proxy.setServerNames(new String[] {LOOPBACK});
        proxy.setPortNumbers(new int[] {server.getLocalPort()});
        proxy.setDatabaseName(target.getDatabaseName());
        proxy.setUser(target.getUser());
        proxy.setPassword(target.getPassword());
        proxy.setConnectTimeout(target.getConnectTimeout());
        proxy.setLoginTimeout(target.getLoginTimeout());
        proxy.setSocketTimeout(target.getSocketTimeout());
        proxy.setOptions(target.getOptions());
        // Inspect plaintext PostgreSQL frames only on this isolated loopback fixture.
        proxy.setSslMode("disable");
        proxy.setGssEncMode("disable");
        return proxy;
    }

    boolean droppedCommit() throws Exception {
        return dropped.get(5, TimeUnit.SECONDS);
    }

    private boolean relay(int postgresPort) throws IOException {
        try (Socket worker = server.accept(); Socket postgres = new Socket()) {
            postgres.connect(new InetSocketAddress(LOOPBACK, postgresPort), 5_000);
            postgres.setSoTimeout(8_000);
            threads.submit(() -> forwardWorker(worker, postgres));
            var fromPostgres = new DataInputStream(postgres.getInputStream());
            var toWorker = new DataOutputStream(worker.getOutputStream());
            for (int type; (type = fromPostgres.read()) != -1;) {
                int length = fromPostgres.readInt();
                if (length < 4 || length > 65_536) throw new IOException("Unexpected PostgreSQL fixture frame length");
                byte[] body = fromPostgres.readNBytes(length - 4);
                if (body.length != length - 4) throw new IOException("Incomplete PostgreSQL fixture frame");
                if (type == 'C' && Arrays.equals(body, COMMIT)) return true;
                toWorker.writeByte(type);
                toWorker.writeInt(length);
                toWorker.write(body);
            }
            return false;
        }
    }

    private static void forwardWorker(Socket worker, Socket postgres) {
        try {
            worker.getInputStream().transferTo(postgres.getOutputStream());
            postgres.shutdownOutput();
        } catch (IOException ignored) {
            // Closing both sockets at the fault also ends this direction of the relay.
        }
    }

    @Override
    public void close() throws IOException {
        try {
            server.close();
        } finally {
            threads.close();
        }
    }
}
