package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Server.java (Buoc 4 - them truyen du lieu phan doan + ACK)
 * -------------------------------------------------------------
 * So voi Buoc 3, them phan xu ly khi da ESTABLISHED:
 *   - Nhan tung segment du lieu, kiem tra dung thu tu (seq_num)
 *   - Neu dung thu tu: rap vao buffer, gui ACK, tang expectedDataSeq
 *   - Neu segment den KHONG dung thu tu (out-of-order): tam luu lai,
 *     khong tang expectedDataSeq, van gui ACK bao "toi dang cho seq nao"
 *   - Khi nhan goi FIN: in ra toan bo du lieu da rap duoc
 *
 * Server hien van chi phuc vu 1 client tai 1 thoi diem (giong Buoc 3).
 */
public class Server {

    static final int PORT = 5005;
    static final int BUFFER_SIZE = 2048;

    static String connectionState = "CLOSED";
    static int serverIsn;
    static int expectedClientSeq;     // seq client dung trong goi SYN (truoc handshake)
    static int expectedDataSeq;       // seq ke tiep server dang cho nhan (sau handshake)

    // Buffer tam cho cac segment den KHONG dung thu tu (out-of-order),
    // key = seq_num cua segment, value = du lieu (payload) cua segment do.
    static final Map<Integer, byte[]> outOfOrderBuffer = new HashMap<>();

    // Noi rap du lieu da nhan dung thu tu, dung StringBuilder cho de doc log
    // (thuc te nen dung mot mang byte dong, nhung StringBuilder de hinh dung hon).
    static final StringBuilder receivedData = new StringBuilder();

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    public static void main(String[] args) throws Exception {
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
                sendPacket(serverSocket, synAck, clientAddress);

                connectionState = "SYN_RECEIVED";
                log("Da gui SYN-ACK (seq=" + serverIsn + ", ack=" + (expectedClientSeq + 1)
                        + ") | chuyen trang thai -> " + connectionState);
            }

            else if (Packet.hasFlag(packet.flags, Packet.FLAG_ACK)
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_SYN)
                    && connectionState.equals("SYN_RECEIVED")) {

                if (packet.ackNum == serverIsn + 1) {
                    connectionState = "ESTABLISHED";
                    // Ngay khi ESTABLISHED, segment du lieu dau tien se co
                    // seq bat dau bang chinh seq cua goi ACK nay (= clientIsn + 1)
                    expectedDataSeq = packet.seqNum;
                    receivedData.setLength(0); // don buffer cu (neu co) truoc phien moi
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

                // Gui ACK xac nhan da nhan FIN
                Packet finAck = new Packet(0, packet.seqNum + 1, Packet.FLAG_ACK, new byte[0]);
                sendPacket(serverSocket, finAck, clientAddress);
                log("Da gui ACK xac nhan FIN. Phien truyen du lieu ket thuc.");
            }

            // ---------------- GOI DU LIEU BINH THUONG ----------------
            else if (connectionState.equals("ESTABLISHED")
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_SYN)) {

                if (packet.seqNum == expectedDataSeq) {
                    // Dung thu tu mong doi -> rap ngay vao du lieu
                    receivedData.append(new String(packet.payload));
                    expectedDataSeq += packet.payload.length;
                    log("Segment DUNG thu tu (seq=" + packet.seqNum + ", " + packet.payload.length
                            + " byte) -> da rap. Cho tiep seq=" + expectedDataSeq);

                    // Sau khi rap segment nay, kiem tra xem trong outOfOrderBuffer
                    // co san segment nao la "tiep theo ngay" khong -> rap luon (flush).
                    while (outOfOrderBuffer.containsKey(expectedDataSeq)) {
                        byte[] bufferedPayload = outOfOrderBuffer.remove(expectedDataSeq);
                        receivedData.append(new String(bufferedPayload));
                        log("  -> Rap tiep segment da luu tam truoc do (seq=" + expectedDataSeq + ")");
                        expectedDataSeq += bufferedPayload.length;
                    }
                } else if (packet.seqNum > expectedDataSeq) {
                    // Den SOM hon mong doi (out-of-order) -> tam luu lai, chua rap
                    outOfOrderBuffer.put(packet.seqNum, packet.payload);
                    log("Segment KHONG dung thu tu (seq=" + packet.seqNum + ", dang cho seq="
                            + expectedDataSeq + ") -> tam luu lai.");
                } else {
                    // seq nho hon mong doi -> segment da nhan roi (trung lap), bo qua phan rap
                    log("Segment TRUNG LAP (seq=" + packet.seqNum + " < dang cho " + expectedDataSeq + ") -> bo qua.");
                }

                // Du segment dung thu tu hay khong, van gui lai ACK bao
                // "server dang cho seq nao tiep theo" (cumulative ACK, giong TCP that).
                Packet ack = new Packet(0, expectedDataSeq, Packet.FLAG_ACK, new byte[0]);
                sendPacket(serverSocket, ack, clientAddress);
                log("Da gui ACK, ack=" + expectedDataSeq);
            }

            else {
                log("Goi tin khong hop le voi trang thai hien tai, bo qua.");
            }
        }
    }

    /** Ham tien ich: dong goi va gui mot Packet toi dia chi dich. */
    static void sendPacket(DatagramSocket socket, Packet packet, InetSocketAddress destination) throws Exception {
        byte[] bytes = packet.encode();
        socket.send(new DatagramPacket(bytes, bytes.length, destination.getAddress(), destination.getPort()));
    }
}