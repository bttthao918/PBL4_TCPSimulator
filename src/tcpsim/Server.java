package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import tcpsim.gui.PacketEvent;

/**
 * Server.java (Buoc 6 - gui qua NetworkSimulator de mo phong mat goi/tre)
 * ---------------------------------------------------------------------------
 * So voi Buoc 4, CHI THAY DOI 1 CHO: moi lan gui goi tin (SYN-ACK, ACK, FIN-ACK)
 * gio di qua NetworkSimulator.send(...) thay vi socket.send(...) truc tiep.
 * Toan bo logic bat tay / truyen du lieu / ACK giu nguyen y het Buoc 4-5.
 *
 * Cach chay co mo phong mang:
 *   java -cp bin tcpsim.Server <ti_le_mat_goi_%> <do_tre_min_ms> <do_tre_max_ms>
 *   Vi du: java -cp bin tcpsim.Server 20 100 400
 *          (20% goi bi mat, moi goi con lai tre ngau nhien 100-400ms)
 *   Khong truyen tham so -> mac dinh KHONG mo phong gi (giong Buoc 4-5).
 */
public class Server {

    static final int PORT = 5005;
    static final int BUFFER_SIZE = 2048;

    static String connectionState = "CLOSED";
    static int serverIsn;
    static int expectedClientSeq;
    static int expectedDataSeq;

    static final Map<Integer, byte[]> outOfOrderBuffer = new HashMap<>();
    static final StringBuilder receivedData = new StringBuilder();

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    public static void main(String[] args) throws Exception {
        // ---------------- Doc tham so cau hinh mo phong mang (neu co) ----------------
        double lossRate = 0.0;
        int minDelay = 0;
        int maxDelay = 0;
        if (args.length >= 3) {
            lossRate = Double.parseDouble(args[0]) / 100.0;
            minDelay = Integer.parseInt(args[1]);
            maxDelay = Integer.parseInt(args[2]);
        }
        NetworkSimulator.configure(lossRate, minDelay, maxDelay);

        DatagramSocket serverSocket = new DatagramSocket(PORT);
        log("Server dang lang nghe tai cong " + PORT + " ...");
        log("Trang thai ket noi: " + connectionState);

        byte[] receiveBuffer = new byte[BUFFER_SIZE];

        while (true) {
            DatagramPacket receivedDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            serverSocket.receive(receivedDatagram);

            byte[] rawData = receivedDatagram.getData();
            int length = receivedDatagram.getLength();
            InetSocketAddress clientAddress =
                (InetSocketAddress) receivedDatagram.getSocketAddress();

            Packet packet = Packet.decode(rawData, length);
            log("Nhan goi tu " + clientAddress + " | " + packet + " | state=" + connectionState);

            // ---------------- BAT TAY (giong Buoc 3) ----------------
            if (Packet.hasFlag(packet.flags, Packet.FLAG_SYN)
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_ACK)
                    && connectionState.equals("CLOSED")) {

                expectedClientSeq = packet.seqNum;
                serverIsn = random.nextInt(10000, 99999);

                byte synAckFlags = (byte) (Packet.FLAG_SYN | Packet.FLAG_ACK);
                Packet synAck = new Packet(serverIsn, expectedClientSeq + 1, synAckFlags, new byte[0]);
                NetworkSimulator.send(serverSocket, synAck, clientAddress.getAddress(), clientAddress.getPort(),
                        "Server -> Client", PacketEvent.Status.SENT);

                connectionState = "SYN_RECEIVED";
                log("Da gui SYN-ACK (seq=" + serverIsn + ", ack=" + (expectedClientSeq + 1)
                        + ") | chuyen trang thai -> " + connectionState);
            }

            else if (Packet.hasFlag(packet.flags, Packet.FLAG_ACK)
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_SYN)
                    && connectionState.equals("SYN_RECEIVED")) {

                if (packet.ackNum == serverIsn + 1) {
                    connectionState = "ESTABLISHED";
                    expectedDataSeq = packet.seqNum;
                    receivedData.setLength(0);
                    outOfOrderBuffer.clear();
                    log(">>> BAT TAY HOAN TAT! Trang thai -> " + connectionState
                            + " | cho du lieu tu seq=" + expectedDataSeq + " <<<");
                } else {
                    log("CANH BAO: ack_num khong khop, bo qua goi nay.");
                }
            }

            // ---------------- GOI FIN: bao het du lieu ----------------
            else if (Packet.hasFlag(packet.flags, Packet.FLAG_FIN) && connectionState.equals("ESTABLISHED")) {
                log("Nhan goi FIN (seq=" + packet.seqNum + ") -> client bao da gui het du lieu.");
                log("=== DU LIEU DA RAP DUOC (" + receivedData.length() + " ky tu) ===");
                log("\"" + receivedData + "\"");

                Packet finAck = new Packet(0, packet.seqNum + 1, Packet.FLAG_ACK, new byte[0]);
                NetworkSimulator.send(serverSocket, finAck, clientAddress.getAddress(), clientAddress.getPort(),
                        "Server -> Client", PacketEvent.Status.SENT);
                log("Da gui ACK xac nhan FIN. Phien truyen du lieu ket thuc.");
            }

            // ---------------- GOI DU LIEU BINH THUONG ----------------
            else if (connectionState.equals("ESTABLISHED")
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_SYN)) {

                if (packet.seqNum == expectedDataSeq) {
                    receivedData.append(new String(packet.payload));
                    expectedDataSeq += packet.payload.length;
                    log("Segment DUNG thu tu (seq=" + packet.seqNum + ", " + packet.payload.length
                            + " byte) -> da rap. Cho tiep seq=" + expectedDataSeq);

                    while (outOfOrderBuffer.containsKey(expectedDataSeq)) {
                        byte[] bufferedPayload = outOfOrderBuffer.remove(expectedDataSeq);
                        receivedData.append(new String(bufferedPayload));
                        log("  -> Rap tiep segment da luu tam truoc do (seq=" + expectedDataSeq + ")");
                        expectedDataSeq += bufferedPayload.length;
                    }
                } else if (packet.seqNum > expectedDataSeq) {
                    outOfOrderBuffer.put(packet.seqNum, packet.payload);
                    log("Segment KHONG dung thu tu (seq=" + packet.seqNum + ", dang cho seq="
                            + expectedDataSeq + ") -> tam luu lai.");
                } else {
                    log("Segment TRUNG LAP (seq=" + packet.seqNum + " < dang cho " + expectedDataSeq + ") -> bo qua.");
                }

                Packet ack = new Packet(0, expectedDataSeq, Packet.FLAG_ACK, new byte[0]);
                NetworkSimulator.send(serverSocket, ack, clientAddress.getAddress(), clientAddress.getPort(),
                        "Server -> Client", PacketEvent.Status.SENT);
                log("Da gui ACK, ack=" + expectedDataSeq);
            }

            else {
                log("Goi tin khong hop le voi trang thai hien tai, bo qua.");
            }
        }
    }
}