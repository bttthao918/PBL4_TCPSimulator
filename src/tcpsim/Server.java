package tcpsim;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import tcpsim.gui.PacketEvent;

/**
 * Mo phong TCP tren UDP: mot socket lang nghe, moi IP + cong Client co phien rieng.
 * Cac goi tin duoc xu ly xen ke, khong cho mot Client ket thuc moi nhan Client khac.
 * Cau hinh mang doc tu server-config.txt hoac ba tham so: loss%, delayMin, delayMax.
 */
public class Server {

    static final int PORT = 5005;
    static final int BUFFER_SIZE = 2048;
    static final int SESSION_CLEANUP_INTERVAL_MS = 1000;
    static final long SESSION_IDLE_TIMEOUT_NS = TimeUnit.MINUTES.toNanos(2);

    private enum ConnectionState { SYN_RECEIVED, ESTABLISHED, CLOSED }

    /** Tuyet doi khong chia se trang thai/buffer giua hai dia chi Client. */
    private static class ClientSession {
        final InetSocketAddress address;
        final int clientIsn;
        final int serverIsn;
        ConnectionState state = ConnectionState.SYN_RECEIVED;
        int expectedDataSeq;
        final Map<Integer, byte[]> outOfOrderBuffer = new HashMap<>();
        ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
        byte[] lastFinAckPayload = new byte[0];
        int lastFinSeq = -1;
        long lastActivityNs = System.nanoTime();

        ClientSession(InetSocketAddress address, int clientIsn) {
            this.address = address;
            this.clientIsn = clientIsn;
            this.serverIsn = random.nextInt(10000, 99999);
            this.expectedDataSeq = clientIsn + 1;
        }

        void log(String message) {
            Server.log("[Client " + address + "] " + message);
        }
    }

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    /** Doc file cau hinh key=value tuy chon. */
    static Map<String, String> loadConfig(String fileName) {
        Map<String, String> result = new HashMap<>();
        java.io.File file = new java.io.File(fileName);
        if (!file.exists()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    result.put(line.substring(0, equalsIndex).trim(), line.substring(equalsIndex + 1).trim());
                }
            }
        } catch (IOException e) {
            log("CANH BAO: loi khi doc " + fileName + ": " + e.getMessage());
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> config = loadConfig("server-config.txt");
        double lossRate = Double.parseDouble(config.getOrDefault("loss_percent", "0")) / 100.0;
        int minDelay = Integer.parseInt(config.getOrDefault("delay_min_ms", "0"));
        int maxDelay = Integer.parseInt(config.getOrDefault("delay_max_ms", "0"));
        if (args.length >= 3) {
            lossRate = Double.parseDouble(args[0]) / 100.0;
            minDelay = Integer.parseInt(args[1]);
            maxDelay = Integer.parseInt(args[2]);
        }
        NetworkSimulator.configure(lossRate, minDelay, maxDelay);

        try (DatagramSocket serverSocket = new DatagramSocket(PORT)) {
            serve(serverSocket);
        }
    }

    static void serve(DatagramSocket serverSocket) throws IOException {
        // Chi vong lap nay sua sessions, khong can tao thread/khoa cho tung Client.
        Map<InetSocketAddress, ClientSession> sessions = new HashMap<>();
        serverSocket.setSoTimeout(SESSION_CLEANUP_INTERVAL_MS);
        long lastCleanupNs = System.nanoTime();
        log("Server dang lang nghe tai cong " + serverSocket.getLocalPort()
                + " | san sang phuc vu nhieu Client dong thoi.");
        byte[] receiveBuffer = new byte[BUFFER_SIZE];

        while (!serverSocket.isClosed()) {
            long now = System.nanoTime();
            if (now - lastCleanupNs >= TimeUnit.MILLISECONDS.toNanos(SESSION_CLEANUP_INTERVAL_MS)) {
                sessions.values().removeIf(session -> {
                    if (now - session.lastActivityNs < SESSION_IDLE_TIMEOUT_NS) return false;
                    session.log("Xoa phien khong hoat dong trong 2 phut.");
                    return true;
                });
                lastCleanupNs = now;
            }

            DatagramPacket datagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            try {
                serverSocket.receive(datagram);
            } catch (SocketTimeoutException e) {
                continue;
            }
            InetSocketAddress clientAddress = (InetSocketAddress) datagram.getSocketAddress();
            if (datagram.getLength() < Packet.HEADER_SIZE) {
                log("[Client " + clientAddress + "] Bo qua goi tin thieu header.");
                continue;
            }
            Packet packet = Packet.decode(datagram.getData(), datagram.getLength());
            if (!packet.checksumValid) {
                log("[Client " + clientAddress + "] Bo qua goi tin sai checksum.");
                continue;
            }

            ClientSession session = sessions.get(clientAddress);
            if (packet.flags == Packet.FLAG_SYN && packet.payload.length == 0
                    && (session == null || session.state == ConnectionState.CLOSED)) {
                session = new ClientSession(clientAddress, packet.seqNum);
                sessions.put(clientAddress, session);
                session.log("Tao phien moi -> SYN_RECEIVED.");
                sendSynAck(serverSocket, session, PacketEvent.Status.SENT);
                continue;
            }
            if (session == null) {
                log("[Client " + clientAddress + "] Chua co phien, can gui SYN truoc.");
                continue;
            }
            session.lastActivityNs = System.nanoTime();
            handlePacket(serverSocket, session, packet);
        }
    }

    private static void handlePacket(DatagramSocket socket, ClientSession session, Packet packet) {
        session.log("Nhan goi | " + packet + " | state=" + session.state);

        // SYN-ACK bi mat: gui lai dung SYN-ACK cua Client nay, khong reset buffer.
        if (packet.flags == Packet.FLAG_SYN && packet.payload.length == 0
                && packet.seqNum == session.clientIsn && session.state != ConnectionState.CLOSED) {
            sendSynAck(socket, session, PacketEvent.Status.RETRANSMITTED);
            return;
        }

        // Giu ACK cua FIN theo tung Client de phuc hoi khi ACK cuoi bi mat.
        if (session.state == ConnectionState.CLOSED) {
            if (packet.flags == Packet.FLAG_FIN && packet.seqNum == session.lastFinSeq) {
                send(socket, session, new Packet(0, packet.seqNum + 1, Packet.FLAG_ACK,
                        session.lastFinAckPayload), PacketEvent.Status.RETRANSMITTED);
                session.log("Nhan lai FIN -> gui lai ACK cua FIN.");
            }
            return;
        }

        if (session.state == ConnectionState.SYN_RECEIVED) {
            if (packet.flags == Packet.FLAG_ACK && packet.payload.length == 0
                    && packet.ackNum == session.serverIsn + 1
                    && packet.seqNum == session.clientIsn + 1) {
                session.state = ConnectionState.ESTABLISHED;
                session.log(">>> BAT TAY HOAN TAT! -> ESTABLISHED <<<");
                return;
            }
            // ACK cuoi bi mat/den cham: du lieu van bat dau tu ISN + 1,
            // ke ca khi segment dau tien nhan duoc khong dung thu tu.
            if ((packet.flags == 0 || packet.flags == Packet.FLAG_FIN)
                    && packet.seqNum >= session.clientIsn + 1) {
                session.state = ConnectionState.ESTABLISHED;
                session.log("Nhan du lieu sau SYN-ACK -> ESTABLISHED (ACK bat tay co the bi mat).");
            } else {
                session.log("Goi tin khong hop le khi dang cho ACK bat tay, bo qua.");
                return;
            }
        }

        if (packet.flags == Packet.FLAG_FIN) {
            if (packet.seqNum != session.expectedDataSeq || !session.outOfOrderBuffer.isEmpty()) {
                session.log("Chua du du lieu, tiep tuc cho seq=" + session.expectedDataSeq);
                sendDataAck(socket, session);
                return;
            }
            byte[] assembled = session.receivedData.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(assembled);
            String report = "len=" + assembled.length + ";crc=" + Long.toHexString(crc.getValue());
            session.lastFinAckPayload = report.getBytes(StandardCharsets.UTF_8);
            session.lastFinSeq = packet.seqNum;
            session.state = ConnectionState.CLOSED;
            session.log("=== DU LIEU DA RAP DUOC (" + assembled.length + " byte) ===");
            session.log("\"" + new String(assembled, StandardCharsets.UTF_8) + "\"");
            session.log("Bao cao gui kem ACK cua FIN: " + report);
            send(socket, session, new Packet(0, packet.seqNum + 1, Packet.FLAG_ACK,
                    session.lastFinAckPayload), PacketEvent.Status.SENT);
            // Chi giu bao cao de ACK lai FIN; giai phong du lieu cua phien da dong.
            session.receivedData = new ByteArrayOutputStream();
            session.outOfOrderBuffer.clear();
            session.log("Phien ket thuc -> CLOSED. Cac Client khac van tiep tuc.");
            return;
        }

        if (packet.flags != 0 || packet.payload.length == 0) {
            session.log("Bo qua goi dieu khien khong phai du lieu.");
            return;
        }
        if (packet.seqNum == session.expectedDataSeq) {
            session.receivedData.write(packet.payload, 0, packet.payload.length);
            session.expectedDataSeq += packet.payload.length;
            while (session.outOfOrderBuffer.containsKey(session.expectedDataSeq)) {
                byte[] bufferedPayload = session.outOfOrderBuffer.remove(session.expectedDataSeq);
                session.receivedData.write(bufferedPayload, 0, bufferedPayload.length);
                session.expectedDataSeq += bufferedPayload.length;
            }
            session.log("Da rap du lieu, cho tiep seq=" + session.expectedDataSeq);
        } else if (packet.seqNum > session.expectedDataSeq) {
            session.outOfOrderBuffer.putIfAbsent(packet.seqNum, packet.payload);
            session.log("Segment khong dung thu tu -> luu tam, dang cho seq=" + session.expectedDataSeq);
        } else {
            session.log("Segment trung lap -> bo qua.");
        }
        sendDataAck(socket, session);
    }

    private static void sendSynAck(DatagramSocket socket, ClientSession session, PacketEvent.Status status) {
        send(socket, session, new Packet(session.serverIsn, session.clientIsn + 1,
                (byte) (Packet.FLAG_SYN | Packet.FLAG_ACK), new byte[0]), status);
        session.log("Da gui SYN-ACK (seq=" + session.serverIsn + ", ack=" + (session.clientIsn + 1) + ").");
    }

    private static void sendDataAck(DatagramSocket socket, ClientSession session) {
        send(socket, session, new Packet(0, session.expectedDataSeq, Packet.FLAG_ACK, new byte[0]),
                PacketEvent.Status.SENT);
        session.log("Da gui ACK, ack=" + session.expectedDataSeq);
    }

    private static void send(DatagramSocket socket, ClientSession session, Packet packet,
                             PacketEvent.Status status) {
        NetworkSimulator.send(socket, packet, session.address.getAddress(), session.address.getPort(),
                "Server -> Client " + session.address, status);
    }
}
