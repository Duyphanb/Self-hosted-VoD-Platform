package com.vodplatform.worker.queue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.PreferQueryMode;

/** Disposable plaintext fixture. Backend assertions bypass it and query the real PostgreSQL state. */
final class OrchestrationPostgresFaults implements AutoCloseable {
    enum Cut { BEFORE_COMMIT, AFTER_COMMIT }

    private static final byte[] COMMIT = "COMMIT\0".getBytes(StandardCharsets.US_ASCII);
    private final ServerSocket listener;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private Cut cut;
    private int commitsToSkip;
    private boolean blocked;
    private int refused;
    private int dropped;

    OrchestrationPostgresFaults() throws IOException {
        listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        threads.submit(this::acceptConnections);
    }

    void route(PGSimpleDataSource source) {
        source.setServerNames(new String[]{"127.0.0.1"});
        source.setPortNumbers(new int[]{listener.getLocalPort()});
        source.setSslMode("disable");
        source.setGssEncMode("disable");
        source.setPreferQueryMode(PreferQueryMode.SIMPLE);
    }

    synchronized void cutCommit(Cut point, int skip) {
        cut = point;
        commitsToSkip = skip;
    }

    synchronized void block() { blocked = true; }
    synchronized void resume() { blocked = false; }
    synchronized int refusedConnections() { return refused; }
    synchronized int droppedCommits() { return dropped; }

    private synchronized boolean refuseConnection() {
        if (!blocked) return false;
        refused++;
        return true;
    }

    private synchronized boolean dropCommit(Cut point) {
        if (cut != point) return false;
        if (commitsToSkip > 0) {
            commitsToSkip--;
            return false;
        }
        cut = null;
        blocked = true;
        dropped++;
        return true;
    }

    private void acceptConnections() {
        try {
            while (!listener.isClosed()) {
                Socket worker = listener.accept();
                connections.add(worker);
                threads.submit(() -> relay(worker));
            }
        } catch (IOException ignored) {
            // Closing the listener ends the accept loop. A premature failure fails the bounded test assertion.
        }
    }

    private void relay(Socket worker) {
        try (worker; Socket postgres = new Socket()) {
            if (refuseConnection()) return;
            postgres.connect(new InetSocketAddress("postgres", 5432), 3_000);
            threads.submit(() -> forwardWorker(worker, postgres));
            var source = new DataInputStream(postgres.getInputStream());
            var destination = new DataOutputStream(worker.getOutputStream());
            for (int type; (type = source.read()) != -1;) {
                byte[] body = readBody(source);
                // A server CommandComplete with this tag proves the real COMMIT ran.
                if (type == 'C' && Arrays.equals(body, COMMIT) && dropCommit(Cut.AFTER_COMMIT)) return;
                writeFrame(destination, type, body);
            }
        } catch (IOException ignored) {
            // Worker connections are expected to close at the injected fault.
        } finally {
            connections.remove(worker);
        }
    }

    private void forwardWorker(Socket worker, Socket postgres) {
        try {
            var source = new DataInputStream(worker.getInputStream());
            var destination = new DataOutputStream(postgres.getOutputStream());
            // StartupMessage has a length but no type. TLS/GSS negotiation is disabled for this fixture.
            byte[] startup = readBody(source);
            destination.writeInt(startup.length + 4);
            destination.write(startup);
            for (int type; (type = source.read()) != -1;) {
                byte[] body = readBody(source);
                if (type == 'Q' && Arrays.equals(body, COMMIT) && dropCommit(Cut.BEFORE_COMMIT)) break;
                writeFrame(destination, type, body);
            }
        } catch (IOException ignored) {
            // The backend direction also closes the connection after a lost response.
        } finally {
            closeQuietly(worker);
            closeQuietly(postgres);
        }
    }

    private static byte[] readBody(DataInputStream stream) throws IOException {
        int length = stream.readInt();
        if (length < 4 || length > 65_536) throw new IOException("Invalid PostgreSQL fixture frame length");
        byte[] body = stream.readNBytes(length - 4);
        if (body.length != length - 4) throw new IOException("Truncated PostgreSQL fixture frame");
        return body;
    }

    private static void writeFrame(DataOutputStream stream, int type, byte[] body) throws IOException {
        stream.writeByte(type);
        stream.writeInt(body.length + 4);
        stream.write(body);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Fixture shutdown.
        }
    }

    @Override
    public void close() throws IOException {
        try {
            listener.close();
        } finally {
            connections.forEach(OrchestrationPostgresFaults::closeQuietly);
            threads.close();
        }
    }
}
