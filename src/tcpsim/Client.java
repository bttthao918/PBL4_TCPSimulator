package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Client.java (Buoc 4 - them truyen du lieu phan doan + ACK)
 * -------------------------------------------------------------
 * Sau khi bat tay xong (ESTABLISHED), client:
 *   1. Chia MESSAGE_TO_SEND thanh cac segment SEGMENT_SIZE byte
 *   2. Gui tung segment, CHO ACK roi moi gui segment tiep theo
 *      (stop-and-wait - chua co sliding window, chua co retransmission
 *      that cho du lieu - phan do de danh cho Buoc 5)
 *   3. Gui goi FIN de bao da gui het du lieu
 */
public class Client {

    static final String SERVER_HOST = "127.0.0.1";
    static final int SERVER_PORT = 5005;
    static final int TIMEOUT_MS = 2000;
    static final int MAX_RETRIES = 5;

    // Kich thuoc moi segment du lieu (giong MSS trong TCP that, nhung
    // de nho de ban thay ro nhieu segment trong log khi demo).
    static final int SEGMENT_SIZE = 8;

    // Du lieu mau se gui di - ban co the thay doi noi dung nay tuy y.
    static final String MESSAGE_TO_SEND = "Xin chao Server, day la bai PBL4 mo phong TCP!";

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    public static void main(String[] args) throws Exception {
        DatagramSocket clientSocket = new DatagramSocket();
        InetAddress serverAddress = InetAddress.getByName(SERVER_HOST);
        clientSocket.setSoTimeout(TIMEOUT_MS);

        int clientIsn = random.nextInt(1000, 9999);
        log("Client san sang, ISN = " + clientIsn);

        // ================= BAT TAY 3 BUOC (giong Buoc 3) =================
        Packet synAckReceived = null;
        int attempt = 0;

        while (attempt < MAX_RETRIES && synAckReceived == null) {
            attempt++;
            Packet syn = new Packet(clientIsn, 0, Packet.FLAG_SYN, new byte[0]);
            sendPacket(clientSocket, syn, serverAddress, SERVER_PORT);
            log("Da gui SYN (lan " + attempt + "), seq=" + clientIsn);

            try {
                Packet response = receivePacket(clientSocket);
                if (Packet.hasFlag(response.flags, Packet.FLAG_SYN)
                        && Packet.hasFlag(response.flags, Packet.FLAG_ACK)
                        && response.ackNum == clientIsn + 1) {
                    synAckReceived = response;
                    log("Nhan duoc SYN-ACK hop le: " + response);
                }
            } catch (SocketTimeoutException e) {
                log("Qua han, chua nhan duoc SYN-ACK. Thu gui lai...");
            }
        }

        if (synAckReceived == null) {
            log("LOI: Bat tay that bai sau " + MAX_RETRIES + " lan thu.");
            clientSocket.close();
            return;
        }

        int serverIsn = synAckReceived.seqNum;
        int currentSeq = clientIsn + 1; // seq bat dau cho du lieu, tiep theo ISN cua client

        Packet finalAck = new Packet(currentSeq, serverIsn + 1, Packet.FLAG_ACK, new byte[0]);
        sendPacket(clientSocket, finalAck, serverAddress, SERVER_PORT);
        log("Da gui ACK cuoi cung -> ESTABLISHED. Bat tay hoan tat!\n");

        // ================= TRUYEN DU LIEU PHAN DOAN (Buoc 4) =================
        byte[] fullData = MESSAGE_TO_SEND.getBytes();
        log("Chuan bi gui " + fullData.length + " byte du lieu, chia thanh cac segment "
                + SEGMENT_SIZE + " byte.\n");

        int offset = 0;
        int segmentIndex = 1;

        while (offset < fullData.length) {
            int end = Math.min(offset + SEGMENT_SIZE, fullData.length);
            byte[] segmentPayload = new byte[end - offset];
            System.arraycopy(fullData, offset, segmentPayload, 0, segmentPayload.length);

            Packet dataPacket = new Packet(currentSeq, 0, (byte) 0, segmentPayload);
            sendPacket(clientSocket, dataPacket, serverAddress, SERVER_PORT);
            log("Gui segment #" + segmentIndex + " (seq=" + currentSeq + "): \""
                    + new String(segmentPayload) + "\"");

            try {
                Packet ackResponse = receivePacket(clientSocket);
                if (Packet.hasFlag(ackResponse.flags, Packet.FLAG_ACK)) {
                    log("  -> Nhan ACK, server dang cho seq=" + ackResponse.ackNum);
                }
            } catch (SocketTimeoutException e) {
                // Buoc 5 se xu ly gui lai khi mat ACK - hien tai chi canh bao
                log("  -> CANH BAO: qua han khong nhan duoc ACK cho segment nay"
                        + " (co che gui lai se lam o Buoc 5).");
            }

            currentSeq += segmentPayload.length;
            offset = end;
            segmentIndex++;
        }

        // ================= GUI FIN BAO HET DU LIEU =================
        log("\nDa gui het " + (segmentIndex - 1) + " segment. Gui goi FIN de bao ket thuc.");
        Packet finPacket = new Packet(currentSeq, 0, Packet.FLAG_FIN, new byte[0]);
        sendPacket(clientSocket, finPacket, serverAddress, SERVER_PORT);

        try {
            Packet finAckResponse = receivePacket(clientSocket);
            if (Packet.hasFlag(finAckResponse.flags, Packet.FLAG_ACK)) {
                log("Server da xac nhan FIN. Phien truyen du lieu ket thuc thanh cong!");
            }
        } catch (SocketTimeoutException e) {
            log("CANH BAO: khong nhan duoc ACK cho goi FIN.");
        }

        clientSocket.close();
    }

    static void sendPacket(DatagramSocket socket, Packet packet, InetAddress address, int port) throws Exception {
        byte[] bytes = packet.encode();
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
    }

    static Packet receivePacket(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagram);
        return Packet.decode(datagram.getData(), datagram.getLength());
    }
}