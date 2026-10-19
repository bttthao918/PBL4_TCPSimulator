package tcpsim;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
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
 * Server.java (Buoc 6 + ho tro demo 2 may qua file cau hinh tuy chon)
 * ---------------------------------------------------------------------------
 * So voi Buoc 4, CHI THAY DOI 1 CHO: moi lan gui goi tin (SYN-ACK, ACK, FIN-ACK)
 * gio di qua NetworkSimulator.send(...) thay vi socket.send(...) truc tiep.
 * Toan bo logic bat tay / truyen du lieu / ACK giu nguyen y het Buoc 4-5.
 *
 * LUU Y VE 2 MAY: new DatagramSocket(PORT) o day MAC DINH da lang nghe tren
 * MOI dia chi mang cua may (khong chi localhost) - nen Server KHONG CAN sua
 * gi them de nhan ket noi tu may khac, chi can may Client biet dung IP LAN
 * cua may nay (xem client-config.txt) va tuong lua (firewall) khong chan cong 5005.
 *
 * Cach chay co mo phong mang (tuy chon, doc tu server-config.txt neu co,
 * hoac truyen tham so dong lenh de ghi de):
 *   java -cp bin tcpsim.Server <ti_le_mat_goi_%> <do_tre_min_ms> <do_tre_max_ms>
 *   Khong truyen gi va khong co server-config.txt -> mac dinh KHONG mo phong.
 */
public class Server {

    static final int PORT = 5005;
    static final int BUFFER_SIZE = 2048;

    static String connectionState = "CLOSED";
    static int serverIsn;
    static int expectedClientSeq;
    static int expectedDataSeq;
    // BUOC 4: bao cao 'toi da rap duoc N byte, CRC32 = ...' gui kem trong ACK cua FIN,
    // de Client tu kiem tra du lieu den noi co DUNG khong (ke ca khi demo 2 may).
    static byte[] lastFinAckPayload = new byte[0];
    static int lastFinSeq = -1; // seq cua goi FIN cua phien truoc (de tra loi lai neu ACK-cua-FIN bi mat)

    static final Map<Integer, byte[]> outOfOrderBuffer = new HashMap<>();
    static final StringBuilder receivedData = new StringBuilder();

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    /** Doc file cau hinh "key=value" tuy chon - giong het ham cung ten ben Client.java. */
    static Map<String, String> loadConfig(String fileName) {
        Map<String, String> result = new HashMap<>();
        java.io.File file = new java.io.File(fileName);
        if (!file.exists()) {
            return result; // khong co file -> dung mac dinh, khong can canh bao o Server
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
        // ---------------- Doc file cau hinh server-config.txt (tuy chon) ----------------
        Map<String, String> config = loadConfig("server-config.txt");
        double lossRate = Double.parseDouble(config.getOrDefault("loss_percent", "0")) / 100.0;
        int minDelay = Integer.parseInt(config.getOrDefault("delay_min_ms", "0"));
        int maxDelay = Integer.parseInt(config.getOrDefault("delay_max_ms", "0"));

        // Tham so dong lenh (neu co) ghi de len file cau hinh.
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

            // ---------------- SUA LOI 1: SYN-ACK bi mat -> Client gui lai SYN ----------------
            // Server dang o SYN_RECEIVED ma lai nhan SYN cung seq -> gui lai SYN-ACK cu.
            if (Packet.hasFlag(packet.flags, Packet.FLAG_SYN)
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_ACK)
                    && connectionState.equals("SYN_RECEIVED")
                    && packet.seqNum == expectedClientSeq) {
                Packet synAckAgain = new Packet(serverIsn, expectedClientSeq + 1,
                        (byte) (Packet.FLAG_SYN | Packet.FLAG_ACK), new byte[0]);
                NetworkSimulator.send(serverSocket, synAckAgain, clientAddress.getAddress(), clientAddress.getPort(),
                        "Server -> Client", PacketEvent.Status.RETRANSMITTED);
                log("Nhan lai SYN (SYN-ACK truoc do co the bi mat) -> gui lai SYN-ACK.");
                continue;
            }

            // ---------------- SUA LOI 2: ACK cuoi cua bat tay bi mat ----------------
            // Client da tuong la xong bat tay nen gui du lieu (hoac FIN) luon.
            // Neu goi do co dung seq = ISN_client + 1 thi coi nhu bat tay da xong.
            if (connectionState.equals("SYN_RECEIVED")
                    && !Packet.hasFlag(packet.flags, Packet.FLAG_SYN)
                    && packet.flags != Packet.FLAG_ACK
                    && packet.seqNum == expectedClientSeq + 1) {
                connectionState = "ESTABLISHED";
                expectedDataSeq = packet.seqNum;
                receivedData.setLength(0);
                outOfOrderBuffer.clear();
                log(">>> ACK cuoi cua bat tay bi mat, nhung nhan duoc du lieu hop le -> coi nhu ESTABLISHED <<<");
            }

            // ---------------- SUA LOI 3: ACK cua FIN bi mat -> Client gui lai FIN ----------------
            if (Packet.hasFlag(packet.flags, Packet.FLAG_FIN)
                    && connectionState.equals("CLOSED")
                    && packet.seqNum == lastFinSeq) {
                Packet finAckAgain = new Packet(0, packet.seqNum + 1, Packet.FLAG_ACK, lastFinAckPayload);
                NetworkSimulator.send(serverSocket, finAckAgain, clientAddress.getAddress(), clientAddress.getPort(),
                        "Server -> Client", PacketEvent.Status.RETRANSMITTED);
                log("Nhan lai FIN (ACK truoc do co the bi mat) -> gui lai ACK cua FIN.");
                continue;
            }

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

                byte[] assembled = receivedData.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(assembled);
                String report = "len=" + assembled.length + ";crc=" + Long.toHexString(crc.getValue());
                lastFinAckPayload = report.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                log("Bao cao gui kem ACK cua FIN: " + report);

                Packet finAck = new Packet(0, packet.seqNum + 1, Packet.FLAG_ACK, lastFinAckPayload);
                NetworkSimulator.send(serverSocket, finAck, clientAddress.getAddress(), clientAddress.getPort(),
                        "Server -> Client", PacketEvent.Status.SENT);
                log("Da gui ACK xac nhan FIN. Phien truyen du lieu ket thuc.");

                // SUA LOI 4: dong phien -> ve CLOSED de chap nhan Client moi (khong can tat/mo lai Server).
                lastFinSeq = packet.seqNum;
                connectionState = "CLOSED";
                log("Trang thai ket noi: " + connectionState + " (san sang nhan ket noi moi)");
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