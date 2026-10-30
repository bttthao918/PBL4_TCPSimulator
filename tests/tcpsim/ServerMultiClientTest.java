package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

/** Integration tests using real UDP sockets; no external test dependencies. */
public class ServerMultiClientTest {
    private static final byte[] EMPTY = new byte[0];
    private static final int ISN = 7000;
    private static final int START_SEQ = ISN + 1;

    public static void main(String[] args) throws Exception {
        NetworkSimulator.configure(0, 0, 0);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread serverThread = new Thread(() -> {
                try {
                    Server.serve(server);
                } catch (Throwable e) {
                    if (!server.isClosed()) serverFailure.set(e);
                }
            }, "TestServer");
            serverThread.setDaemon(true);
            serverThread.start();
            try {
                testInterleavedSessions(server.getLocalPort());
                testConcurrentSessions(server.getLocalPort());
            } finally {
                server.close();
                serverThread.join(3000);
            }
        }
        if (serverFailure.get() != null) throw new AssertionError("Server failed", serverFailure.get());
        System.out.println("PASS: independent sessions, retries, reconnects, UTF-8, and 8 concurrent clients.");
    }

    private static void testInterleavedSessions(int port) throws Exception {
        byte[] aData = "Client A: Xin ch\u00e0o m\u1ecdi ng\u01b0\u1eddi!".getBytes(StandardCharsets.UTF_8);
        byte[] bData = "Client B: d\u1eef li\u1ec7u ri\u00eang, kh\u00e1c Client A.".getBytes(StandardCharsets.UTF_8);
        try (Peer a = new Peer(port); Peer b = new Peer(port); Peer waiting = new Peer(port)) {
            // These malformed/unconnected datagrams must not stop the server or create a session.
            waiting.sendBytes(new byte[]{1, 2, 3});
            waiting.send(new Packet(START_SEQ, 0, (byte) 0, new byte[]{42}));
            a.open(true);
            b.open(false); // Deliberately lose the final handshake ACK.
            waiting.open(false); // An idle half-open client must not block anyone.

            // All clients use the same ISN: routing must depend on IP AND source port.
            a.sendAndExpectAck(START_SEQ + 8, Arrays.copyOfRange(aData, 8, aData.length), START_SEQ);
            b.sendAndExpectAck(START_SEQ + 8, Arrays.copyOfRange(bData, 8, bData.length), START_SEQ);
            a.retrySyn(); // A duplicate SYN must preserve already buffered data.

            // A premature FIN must not close a session that still has a gap.
            a.send(new Packet(START_SEQ + aData.length, 0, Packet.FLAG_FIN, EMPTY));
            a.expectAck(START_SEQ);

            // Corrupt data from one established client must be discarded without disturbing others.
            Packet corrupt = new Packet(START_SEQ, 0, (byte) 0, new byte[]{42});
            corrupt.checksum++;
            a.send(corrupt);
            a.sendBytes(new byte[]{1});
            a.sendAndExpectAck(START_SEQ, Arrays.copyOfRange(aData, 0, 8), START_SEQ + aData.length);
            a.finish(aData);

            // A has closed, but B can still fill its gap and finish with its own checksum.
            b.sendAndExpectAck(START_SEQ, Arrays.copyOfRange(bData, 0, 8), START_SEQ + bData.length);
            b.sendAndExpectAck(START_SEQ, Arrays.copyOfRange(bData, 0, 8), START_SEQ + bData.length);
            b.finish(bData);
            a.finish(aData); // Repeat A's FIN AFTER B finishes: A's report must remain intact.
            b.finish(bData);

            // The same socket can start another session without restarting the server.
            byte[] nextData = "Phien moi tren cung cong Client A".getBytes(StandardCharsets.UTF_8);
            a.open(true);
            a.sendAndExpectAck(START_SEQ, nextData, START_SEQ + nextData.length);
            a.finish(nextData);
        }
    }

    private static void testConcurrentSessions(int port) throws Exception {
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        ExecutorService workers = Executors.newFixedThreadPool(count);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int id = i;
                tasks.add(workers.submit(() -> {
                    try (Peer peer = new Peer(port)) {
                        peer.open(true);
                        ready.countDown();
                        check(ready.await(5, TimeUnit.SECONDS), "All clients must connect before any finishes");
                        byte[] data = ("Client " + id + ": d\u1eef li\u1ec7u kh\u00e1c nhau. ")
                                .repeat(id + 1).getBytes(StandardCharsets.UTF_8);
                        // One-byte segments split UTF-8 characters; assembly must preserve raw bytes.
                        for (int offset = 0; offset < data.length; offset++) {
                            peer.sendAndExpectAck(START_SEQ + offset, new byte[]{data[offset]}, START_SEQ + offset + 1);
                        }
                        peer.finish(data);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            check(workers.awaitTermination(5, TimeUnit.SECONDS), "Client workers must stop");
        }
    }

    private static class Peer implements AutoCloseable {
        final DatagramSocket socket;
        int serverIsn;

        Peer(int port) throws Exception {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            socket.connect(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(2000);
        }

        void open(boolean finalAck) throws Exception {
            send(new Packet(ISN, 0, Packet.FLAG_SYN, EMPTY));
            Packet response = receive();
            check(response.flags == (Packet.FLAG_SYN | Packet.FLAG_ACK), "Expected SYN-ACK");
            check(response.ackNum == START_SEQ, "Incorrect SYN acknowledgement");
            serverIsn = response.seqNum;
            retrySyn();
            if (finalAck) send(new Packet(START_SEQ, serverIsn + 1, Packet.FLAG_ACK, EMPTY));
        }

        void retrySyn() throws Exception {
            send(new Packet(ISN, 0, Packet.FLAG_SYN, EMPTY));
            Packet response = receive();
            check(response.flags == (Packet.FLAG_SYN | Packet.FLAG_ACK), "Expected repeated SYN-ACK");
            check(response.seqNum == serverIsn && response.ackNum == START_SEQ,
                    "SYN retry must retain this client's ISN");
        }

        void sendAndExpectAck(int seq, byte[] data, int expectedAck) throws Exception {
            send(new Packet(seq, 0, (byte) 0, data));
            expectAck(expectedAck);
        }

        void expectAck(int expectedAck) throws Exception {
            Packet ack = receive();
            check(ack.flags == Packet.FLAG_ACK && ack.ackNum == expectedAck,
                    "Expected cumulative ACK " + expectedAck + ", got " + ack);
        }

        void finish(byte[] data) throws Exception {
            send(new Packet(START_SEQ + data.length, 0, Packet.FLAG_FIN, EMPTY));
            Packet ack = receive();
            CRC32 crc = new CRC32();
            crc.update(data);
            String expected = "len=" + data.length + ";crc=" + Long.toHexString(crc.getValue());
            check(ack.flags == Packet.FLAG_ACK && ack.ackNum == START_SEQ + data.length + 1,
                    "Incorrect FIN acknowledgement");
            check(expected.equals(new String(ack.payload, StandardCharsets.UTF_8)),
                    "FIN report must match this client's exact bytes: " + expected);
        }

        void send(Packet packet) throws Exception {
            sendBytes(packet.encode());
        }

        void sendBytes(byte[] bytes) throws Exception {
            socket.send(new DatagramPacket(bytes, bytes.length));
        }

        Packet receive() throws Exception {
            byte[] bytes = new byte[2048];
            DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
            socket.receive(datagram);
            check(datagram.getLength() >= Packet.HEADER_SIZE, "Response header missing");
            Packet packet = Packet.decode(datagram.getData(), datagram.getLength());
            check(packet.checksumValid, "Response checksum invalid");
            return packet;
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
