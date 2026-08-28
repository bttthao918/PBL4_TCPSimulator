package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Client.java (Buoc 5 - them Threading that + Timeout/Retransmission that)
 * ----------------------------------------------------------------------------
 * Diem khac biet lon nhat so voi Buoc 4: viec gui du lieu, nhan ACK, va kiem tra
 * timeout gio day chay tren 3 THREAD RIENG BIET, khong con tuan tu tren 1 luong:
 *
 *   - Main thread      : lan luot dua tung segment ra gui, roi CHO no duoc ACK
 *   - ReceiverThread    : lien tuc lang nghe ACK tu server, cham chi khong lam gi khac
 *   - TimeoutThread     : dinh ky kiem tra segment nao qua han (RTO) chua co ACK
 *                         -> tu dong gui lai, khong can main thread can thiep
 *
 * Ca 3 thread deu doc/ghi chung mot bang "unackedSegments" -> BAT BUOC dung
 * synchronized (giong Lock/Mutex trong He dieu hanh) de tranh race condition
 * (2 thread cung sua bang mot luc gay sai du lieu).
 */
public class Client {

    static final String SERVER_HOST = "127.0.0.1";
    static final int SERVER_PORT = 5005;
    static final int HANDSHAKE_TIMEOUT_MS = 2000;
    static final int MAX_HANDSHAKE_RETRIES = 5;

    static final int SEGMENT_SIZE = 8;
    static final String MESSAGE_TO_SEND = "Xin chao Server, day la bai PBL4 mo phong TCP!";

    // ---------------- Cau hinh cho retransmission ----------------
    static final long RTO_MS = 1000;                     // qua 1s khong co ACK -> gui lai
    static final long TIMEOUT_CHECK_INTERVAL_MS = 200;    // chu ky "thuc day" cua TimeoutThread
    static final int MAX_DATA_RETRIES = 5;                // so lan gui lai toi da cho 1 segment

    static DatagramSocket clientSocket;
    static InetAddress serverAddress;

    // ---------------- Du lieu DUNG CHUNG giua cac thread ----------------
    // key = seq_num cua segment CHUA duoc ACK, value = thong tin segment do.
    // Moi lan doc/ghi bang nay deu phai boc trong synchronized (unackedSegments) { ... }
    static final Map<Integer, SegmentInfo> unackedSegments = new HashMap<>();

    // volatile: dam bao moi thread deu thay gia tri MOI NHAT ngay lap tuc,
    // khong bi "cache" rieng gia tri cu trong tung thread.
    static volatile boolean transferFailed = false;

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] "
                + "[" + Thread.currentThread().getName() + "] " + message);
    }

    /** Thong tin 1 segment dang cho ACK: du lieu, co, thoi diem gui gan nhat, so lan da thu lai. */
    static class SegmentInfo {
        byte[] payload;
        byte flags;
        long sentTime;
        int retryCount;

        SegmentInfo(byte[] payload, byte flags, long sentTime) {
            this.payload = payload;
            this.flags = flags;
            this.sentTime = sentTime;
            this.retryCount = 0;
        }
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("MainThread");

        // ---------------- Doc tham so cau hinh mo phong mang (neu co) ----------------
        // Cach chay: java -cp bin tcpsim.Client <ti_le_mat_goi_%> <do_tre_min_ms> <do_tre_max_ms>
        // Vi du: java -cp bin tcpsim.Client 20 100 400
        // Khong truyen gi -> mac dinh KHONG mo phong (giong Buoc 4-5).
        double lossRate = 0.0;
        int minDelay = 0;
        int maxDelay = 0;
        if (args.length >= 3) {
            lossRate = Double.parseDouble(args[0]) / 100.0;
            minDelay = Integer.parseInt(args[1]);
            maxDelay = Integer.parseInt(args[2]);
        }
        NetworkSimulator.configure(lossRate, minDelay, maxDelay);

        clientSocket = new DatagramSocket();
        serverAddress = InetAddress.getByName(SERVER_HOST);

        int clientIsn = random.nextInt(1000, 9999);
        log("Client san sang, ISN = " + clientIsn);

        // ================= BAT TAY 3 BUOC (giong Buoc 3, chua can threading) =================
        clientSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        Packet synAckReceived = null;
        int attempt = 0;
        while (attempt < MAX_HANDSHAKE_RETRIES && synAckReceived == null) {
            attempt++;
            Packet syn = new Packet(clientIsn, 0, Packet.FLAG_SYN, new byte[0]);
            sendRaw(syn);
            log("Da gui SYN (lan " + attempt + "), seq=" + clientIsn);
            try {
                Packet response = receiveRaw();
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
            log("LOI: Bat tay that bai sau " + MAX_HANDSHAKE_RETRIES + " lan thu.");
            clientSocket.close();
            return;
        }

        int serverIsn = synAckReceived.seqNum;
        int currentSeq = clientIsn + 1;
        Packet finalAck = new Packet(currentSeq, serverIsn + 1, Packet.FLAG_ACK, new byte[0]);
        sendRaw(finalAck);
        log("Da gui ACK cuoi cung -> ESTABLISHED. Bat tay hoan tat!\n");

        // Tu day, KHONG dung socket timeout nua - ReceiverThread se tu chan
        // (block) cho goi tin den lien tuc, khong can timeout rieng cho no.
        clientSocket.setSoTimeout(0);

        // ================= KHOI DONG 2 THREAD NEN (Buoc 5) =================
        Thread receiverThread = new Thread(Client::receiverLoop, "ReceiverThread");
        receiverThread.setDaemon(true); // daemon: tu tat khi chuong trinh chinh ket thuc
        receiverThread.start();

        Thread timeoutThread = new Thread(Client::timeoutCheckerLoop, "TimeoutThread");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        // ================= TRUYEN DU LIEU (stop-and-wait, nhung gio da co retransmission that) =================
        byte[] fullData = MESSAGE_TO_SEND.getBytes();
        log("Chuan bi gui " + fullData.length + " byte, chia thanh cac segment " + SEGMENT_SIZE + " byte.\n");

        int offset = 0;
        int segmentIndex = 1;

        while (offset < fullData.length && !transferFailed) {
            int end = Math.min(offset + SEGMENT_SIZE, fullData.length);
            byte[] segmentPayload = new byte[end - offset];
            System.arraycopy(fullData, offset, segmentPayload, 0, segmentPayload.length);

            sendDataSegment(currentSeq, segmentPayload, (byte) 0, segmentIndex);

            // Main thread CHO o day cho toi khi ReceiverThread bao segment
            // nay da duoc ACK (hoac TimeoutThread bao that bai qua nhieu lan).
            waitUntilAcked(currentSeq);
            if (transferFailed) break;

            currentSeq += segmentPayload.length;
            offset = end;
            segmentIndex++;
        }

        if (transferFailed) {
            log("\nLOI: Truyen du lieu that bai sau nhieu lan gui lai khong thanh cong.");
            clientSocket.close();
            return;
        }

        // ================= GUI FIN =================
        log("\nDa gui het " + (segmentIndex - 1) + " segment. Gui goi FIN de bao ket thuc.");
        sendDataSegment(currentSeq, new byte[0], Packet.FLAG_FIN, -1);
        waitUntilAcked(currentSeq);

        if (!transferFailed) {
            log("Server da xac nhan FIN. Phien truyen du lieu ket thuc thanh cong!");
        }

        clientSocket.close();
    }

    /** Gui 1 segment (hoac FIN) va dang ky vao unackedSegments de 2 thread kia theo doi. */
    static void sendDataSegment(int seq, byte[] payload, byte flags, int segmentIndex) throws Exception {
        Packet packet = new Packet(seq, 0, flags, payload);
        sendRaw(packet);

        // synchronized: chi 1 thread duoc sua unackedSegments tai 1 thoi diem.
        synchronized (unackedSegments) {
            unackedSegments.put(seq, new SegmentInfo(payload, flags, System.currentTimeMillis()));
        }

        String label = (flags == Packet.FLAG_FIN) ? "FIN" : ("segment #" + segmentIndex);
        log("Gui " + label + " (seq=" + seq + ")"
                + (payload.length > 0 ? ": \"" + new String(payload) + "\"" : ""));
    }

    /**
     * Main thread goi ham nay de "cho" cho toi khi seq nay bien mat khoi
     * unackedSegments (nghia la ReceiverThread da nhan duoc ACK va xoa no).
     * Dung cach "polling" (kiem tra dinh ky) cho de hieu - cach lam chuyen
     * nghiep hon la dung wait()/notify(), ban co the tim hieu them sau nay.
     */
    static void waitUntilAcked(int seq) throws InterruptedException {
        while (!transferFailed) {
            synchronized (unackedSegments) {
                if (!unackedSegments.containsKey(seq)) {
                    return; // da duoc ACK, thoat cho
                }
            }
            Thread.sleep(50); // nghi ngan de khong "ngop" CPU khi kiem tra lien tuc
        }
    }

    /** ReceiverThread: viec DUY NHAT no lam la lang nghe ACK va cap nhat bang dung chung. */
    static void receiverLoop() {
        while (true) {
            try {
                Packet response = receiveRaw();
                if (Packet.hasFlag(response.flags, Packet.FLAG_ACK)) {
                    int ackNum = response.ackNum;
                    synchronized (unackedSegments) {
                        // Cumulative ACK: server bao "toi da nhan dung toi byte X",
                        // nen xoa MOI segment co seq < X (khong chi segment vua gui).
                        Iterator<Map.Entry<Integer, SegmentInfo>> it = unackedSegments.entrySet().iterator();
                        while (it.hasNext()) {
                            if (it.next().getKey() < ackNum) {
                                it.remove();
                            }
                        }
                    }
                    log("Nhan ACK, server dang cho seq=" + ackNum);
                }
            } catch (Exception e) {
                return; // socket dong (ket thuc chuong trinh) -> thoat thread trong yen lang
            }
        }
    }

    /** TimeoutThread: viec DUY NHAT no lam la ra soat dinh ky va gui lai khi qua han. */
    static void timeoutCheckerLoop() {
        while (true) {
            try {
                Thread.sleep(TIMEOUT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (transferFailed) return;

            long now = System.currentTimeMillis();
            synchronized (unackedSegments) {
                for (Map.Entry<Integer, SegmentInfo> entry : unackedSegments.entrySet()) {
                    int seq = entry.getKey();
                    SegmentInfo info = entry.getValue();

                    if (now - info.sentTime >= RTO_MS) {
                        if (info.retryCount >= MAX_DATA_RETRIES) {
                            log("LOI: seq=" + seq + " qua " + MAX_DATA_RETRIES
                                    + " lan gui lai van khong duoc ACK. Huy phien truyen.");
                            transferFailed = true;
                            break;
                        }
                        try {
                            Packet resend = new Packet(seq, 0, info.flags, info.payload);
                            sendRaw(resend);
                            info.retryCount++;
                            info.sentTime = now;
                            log("Qua RTO (" + RTO_MS + "ms) chua co ACK -> GUI LAI seq=" + seq
                                    + " (lan thu " + (info.retryCount + 1) + ")");
                        } catch (Exception e) {
                            log("Loi khi gui lai seq=" + seq + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    static void sendRaw(Packet packet) throws Exception {
        // Buoc 6: gui qua NetworkSimulator thay vi goi thang socket.send(...),
        // de goi tin co the bi mat/tre theo cau hinh mo phong mang.
        NetworkSimulator.send(clientSocket, packet, serverAddress, SERVER_PORT);
    }

    static Packet receiveRaw() throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        clientSocket.receive(datagram);
        return Packet.decode(datagram.getData(), datagram.getLength());
    }
}