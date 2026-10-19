package tcpsim;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import tcpsim.gui.EventBus;
import tcpsim.gui.PacketEvent;
import tcpsim.gui.SessionReport;
import tcpsim.gui.SessionSettings;
import tcpsim.gui.Visualizer;

/**
 * Client.java (Buoc 7 + ho tro demo 2 may qua file cau hinh)
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
 *
 * KHAC BIET MOI: dia chi IP cua Server KHONG CON HARDCODE "127.0.0.1" nua.
 * Client doc dia chi do tu file client-config.txt (nam cung thu muc chay
 * chuong trinh) - de khi demo tren 2 may that, chi can sua 1 dong trong
 * file .txt do thay vi sua code va bien dich lai.
 */
public class Client {

    static final int SERVER_PORT = 5005;
    static final int HANDSHAKE_TIMEOUT_MS = 2000;
    static final int MAX_HANDSHAKE_RETRIES = 5;

    static final int SEGMENT_SIZE = 8;
    // Cau mau 47 byte. Moi phien lap lai no 'message_repeat' lan (chinh duoc tren GUI)
    // de co nhieu segment -> thay ro hieu qua cua cua so truot va slow start.
    static final String BASE_MESSAGE = "Xin chao Server, day la bai PBL4 mo phong TCP! ";

    // BUOC 1 (cua so truot): so segment toi da duoc phep "dang bay" (da gui, chua duoc ACK).
    // Doc tu client-config.txt (window_size=...). Bang 1 nghia la quay ve stop-and-wait.
    static int windowSize = 4;

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

    // ---------------- BUOC 4 + 5: thong ke va quan ly nhieu phien ----------------
    static final AtomicInteger statFirstSends = new AtomicInteger();   // so segment du lieu gui lan dau
    static final AtomicInteger statRetransmits = new AtomicInteger();  // so luot gui lai segment du lieu
    static volatile String serverReport = null;  // "len=..;crc=.." Server gui kem trong ACK cua FIN
    // Moi phien co 1 so rieng. Cac thread nen thay so nay doi (phien da ket thuc / phien moi bat dau)
    // thi tu thoat, nen chay lai nhieu lan khong bi nhan doi thread.
    static volatile int sessionGen = 0;

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] "
                + "[" + Thread.currentThread().getName() + "] " + message);
    }

    /**
     * Doc mot file cau hinh dang "key=value" (moi dong 1 cap). Dong rong hoac
     * bat dau bang '#' duoc bo qua (coi la ghi chu). Neu file khong ton tai,
     * tra ve mot Map rong - cac gia tri mac dinh se duoc dung o noi goi ham nay.
     */
    static Map<String, String> loadConfig(String fileName) {
        Map<String, String> result = new HashMap<>();
        java.io.File file = new java.io.File(fileName);
        if (!file.exists()) {
            log("Khong tim thay " + fileName + " -> dung cau hinh mac dinh (127.0.0.1, khong mo phong mang).");
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
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            log("CANH BAO: loi khi doc " + fileName + ": " + e.getMessage() + " -> dung gia tri mac dinh.");
        }
        return result;
    }

    // =====================================================================
    // BUOC 2: DIEU KHIEN TAC NGHEN (slow start + congestion avoidance)
    // =====================================================================
    // cwnd (congestion window) = so segment duoc phep "dang bay" TAI THOI DIEM NAY.
    // Bat dau cwnd = 1, roi tu tang len khi mang on, tut xuong khi phat hien mat goi.
    //   - SLOW START (cwnd < ssthresh):  moi ACK moi -> cwnd += 1  (gap doi moi vong RTT)
    //   - CONGESTION AVOIDANCE (cwnd >= ssthresh): moi ACK moi -> cwnd += 1/cwnd (+1 moi RTT)
    //   - TIMEOUT (mat goi): ssthresh = cwnd / 2, cwnd = 1 -> quay lai SLOW START
    static class CongestionControl {
        final boolean enabled;
        final long startTime = System.currentTimeMillis();
        double cwnd = 1;
        double ssthresh;
        long lastReductionTime = 0;
        int reductions = 0;      // BUOC 4: so lan cwnd bi giam do timeout
        double maxCwnd = 1;      // BUOC 4: cwnd lon nhat dat duoc
        // Lich su (thoi gian ms, cwnd, ssthresh) - de Buoc 3 ve bieu do.
        final List<double[]> history = new ArrayList<>();

        CongestionControl(boolean enabled, double initialSsthresh) {
            this.enabled = enabled;
            this.ssthresh = initialSsthresh;
            history.add(new double[]{0, cwnd, ssthresh});
            if (enabled) EventBus.publishCwnd(0, cwnd, ssthresh, 0);
        }

        /** Kich thuoc cua so thuc te: min(cwnd, window_size). Neu tat CC thi chi la window_size. */
        synchronized int window(int maxWindow) {
            if (!enabled) return maxWindow;
            return Math.max(1, Math.min((int) Math.floor(cwnd), maxWindow));
        }

        String phase() {
            return (cwnd < ssthresh) ? "SLOW START" : "CONGESTION AVOIDANCE";
        }

        /** ReceiverThread goi khi co 'count' segment du lieu vua duoc ACK moi. */
        synchronized void onAcked(int count, int maxWindow) {
            if (!enabled) return;
            for (int i = 0; i < count; i++) {
                if (cwnd < ssthresh) cwnd += 1;
                else cwnd += 1.0 / cwnd;
            }
            cwnd = Math.min(cwnd, maxWindow);
            maxCwnd = Math.max(maxCwnd, cwnd);
            record(cwnd < ssthresh ? 0 : 1);
            log(String.format("[CC] +%d ACK moi -> cwnd=%.2f ssthresh=%.0f (%s)",
                    count, cwnd, ssthresh, phase()));
        }

        /** TimeoutThread goi khi co segment qua han RTO. Chi giam 1 lan cho moi dot mat goi. */
        synchronized void onTimeout(long now) {
            if (!enabled) return;
            if (lastReductionTime != 0 && now - lastReductionTime < RTO_MS) return;
            ssthresh = Math.max(cwnd / 2, 2);
            cwnd = 1;
            lastReductionTime = now;
            reductions++;
            record(2);
            log(String.format("[CC] TIMEOUT (mat goi) -> ssthresh=%.0f, cwnd ve 1 (%s)", ssthresh, phase()));
        }

        // type: 0 = slow start, 1 = congestion avoidance, 2 = timeout. Dong thoi bao cho GUI ve bieu do.
        private void record(int type) {
            double t = System.currentTimeMillis() - startTime;
            history.add(new double[]{t, cwnd, ssthresh});
            EventBus.publishCwnd(t, cwnd, ssthresh, type);
        }
    }

    static CongestionControl congestion = new CongestionControl(false, 16);

    static int currentWindow() {
        return congestion.window(windowSize);
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

        // ---------------- Doc file cau hinh client-config.txt ----------------
        // Day chi la GIA TRI BAN DAU hien tren GUI. Tu BUOC 5, co the sua truc tiep tren GUI roi
        // bam "Chay lai", khong can sua file nua.
        Map<String, String> config = loadConfig("client-config.txt");
        SessionSettings initial = new SessionSettings();
        initial.serverIp = config.getOrDefault("server_ip", "127.0.0.1");
        initial.lossPercent = (int) Math.round(Double.parseDouble(config.getOrDefault("loss_percent", "0")));
        initial.delayMinMs = Integer.parseInt(config.getOrDefault("delay_min_ms", "0"));
        initial.delayMaxMs = Integer.parseInt(config.getOrDefault("delay_max_ms", "0"));
        initial.windowSize = Math.max(1, Integer.parseInt(config.getOrDefault("window_size", "32")));
        initial.congestionControl = !config.getOrDefault("congestion_control", "on").equalsIgnoreCase("off");
        initial.ssthresh = (int) Math.round(Double.parseDouble(config.getOrDefault("ssthresh", "16")));
        initial.messageRepeat = Math.max(1, Integer.parseInt(config.getOrDefault("message_repeat", "8")));

        // Tham so dong lenh (neu co) GHI DE len file cau hinh - tien khi tu test nhanh.
        if (args.length >= 3) {
            initial.lossPercent = (int) Math.round(Double.parseDouble(args[0]));
            initial.delayMinMs = Integer.parseInt(args[1]);
            initial.delayMaxMs = Integer.parseInt(args[2]);
        }

        // May khong co man hinh (vd chay tren server/CI): chay 1 phien, khong co GUI.
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            runSession(initial);
            return;
        }

        // Mo cua so GUI. GUI tu chay phien dau tien ngay khi hien, va cho phep
        // doi tham so + bam "Chay lai" de chay phien tiep theo.
        Visualizer.launch(initial, Client::runSession);
    }

    /**
     * Chay MOT phien truyen du lieu tron ven (bat tay -> truyen -> FIN) va luon bao ket qua
     * thong ke len GUI o cuoi, du thanh cong hay that bai.
     */
    static void runSession(SessionSettings s) {
        Thread.currentThread().setName("MainThread");
        SessionReport report = new SessionReport();
        report.ccEnabled = s.congestionControl;
        int gen = ++sessionGen;
        try {
            doSession(s, report, gen);
        } catch (Exception e) {
            report.success = false;
            if (report.failReason.isEmpty()) {
                report.failReason = "Loi: " + e;
            }
            log("LOI phien: " + e);
        } finally {
            sessionGen++; // bao 2 thread nen (Receiver, Timeout) dung lai
            try {
                if (clientSocket != null) clientSocket.close();
            } catch (Exception ignored) {
            }
            report.segments = statFirstSends.get();
            report.retransmissions = statRetransmits.get();
            report.dataTransmissions = report.segments + report.retransmissions;
            report.lostPackets = NetworkSimulator.lostCount.get();
            report.cwndReductions = congestion.reductions;
            report.maxCwnd = congestion.maxCwnd;
            EventBus.publishReport(report);
        }
    }

    static void doSession(SessionSettings s, SessionReport report, int gen) throws Exception {
        // ---------------- Dat lai trang thai cho phien moi ----------------
        synchronized (unackedSegments) {
            unackedSegments.clear();
        }
        transferFailed = false;
        serverReport = null;
        statFirstSends.set(0);
        statRetransmits.set(0);

        windowSize = Math.max(1, s.windowSize);
        congestion = new CongestionControl(s.congestionControl, s.ssthresh);
        int minDelay = Math.max(0, s.delayMinMs);
        int maxDelay = Math.max(minDelay, s.delayMaxMs);
        NetworkSimulator.configure(s.lossPercent / 100.0, minDelay, maxDelay);

        log("Cua so toi da (window_size) = " + windowSize + " segment | dieu khien tac nghen: "
                + (s.congestionControl ? "BAT (ssthresh ban dau=" + s.ssthresh + ")" : "TAT")
                + " | du lieu = " + (BASE_MESSAGE.length() * s.messageRepeat) + " byte");
        if (s.congestionControl && windowSize < 16) {
            log("LUU Y: window_size=" + windowSize + " dang chan cwnd. Dat window_size=32 de thay slow start ro hon.");
        }
        log("Dia chi Server se ket noi toi: " + s.serverIp + ":" + SERVER_PORT);

        long sessionStart = System.currentTimeMillis();
        clientSocket = new DatagramSocket();
        serverAddress = InetAddress.getByName(s.serverIp);

        int clientIsn = random.nextInt(1000, 9999);
        log("Client san sang, ISN = " + clientIsn);

        // ================= BAT TAY 3 BUOC =================
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
                    EventBus.publish(new PacketEvent(PacketEvent.Status.ACKED, "Server -> Client",
                            response.flagsToString(), response.seqNum, response.ackNum, "Phan hoi bat tay"));
                }
            } catch (SocketTimeoutException e) {
                log("Qua han, chua nhan duoc SYN-ACK. Thu gui lai...");
            }
        }
        if (synAckReceived == null) {
            log("LOI: Bat tay that bai sau " + MAX_HANDSHAKE_RETRIES + " lan thu.");
            report.failReason = "Bat tay that bai (Server co dang chay va dung IP khong?)";
            return;
        }

        int serverIsn = synAckReceived.seqNum;
        int currentSeq = clientIsn + 1;
        Packet finalAck = new Packet(currentSeq, serverIsn + 1, Packet.FLAG_ACK, new byte[0]);
        sendRaw(finalAck);
        log("Da gui ACK cuoi cung -> ESTABLISHED. Bat tay hoan tat!\n");
        long handshakeDone = System.currentTimeMillis();
        report.handshakeMs = handshakeDone - sessionStart;

        // Tu day, KHONG dung socket timeout nua - ReceiverThread se tu chan cho goi tin den.
        clientSocket.setSoTimeout(0);

        // ================= KHOI DONG 2 THREAD NEN =================
        Thread receiverThread = new Thread(() -> receiverLoop(gen), "ReceiverThread");
        receiverThread.setDaemon(true);
        receiverThread.start();

        Thread timeoutThread = new Thread(() -> timeoutCheckerLoop(gen), "TimeoutThread");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        // ================= TRUYEN DU LIEU (cua so truot + dieu khien tac nghen) =================
        byte[] fullData = BASE_MESSAGE.repeat(Math.max(1, s.messageRepeat)).getBytes(StandardCharsets.UTF_8);
        report.payloadBytes = fullData.length;
        log("Chuan bi gui " + fullData.length + " byte, chia thanh cac segment " + SEGMENT_SIZE + " byte.\n");

        int offset = 0;
        int segmentIndex = 1;

        while (offset < fullData.length && !transferFailed) {
            // Chi cho khi cua so DAY (so segment chua ACK da dat cua so hien tai).
            // Con cho trong cua so thi gui tiep NGAY, khong cho ACK cua goi truoc.
            waitForWindowRoom();
            if (transferFailed) break;

            int end = Math.min(offset + SEGMENT_SIZE, fullData.length);
            byte[] segmentPayload = new byte[end - offset];
            System.arraycopy(fullData, offset, segmentPayload, 0, segmentPayload.length);

            sendDataSegment(currentSeq, segmentPayload, (byte) 0, segmentIndex);

            currentSeq += segmentPayload.length;
            offset = end;
            segmentIndex++;
        }

        // Da dua het du lieu ra, cho TAT CA segment con lai duoc ACK roi moi gui FIN.
        waitUntilAllAcked();

        if (transferFailed) {
            log("\nLOI: Truyen du lieu that bai sau nhieu lan gui lai khong thanh cong.");
            report.failReason = "Mot segment gui lai qua " + MAX_DATA_RETRIES + " lan van khong duoc ACK";
            report.transferMs = System.currentTimeMillis() - handshakeDone;
            return;
        }

        // ================= GUI FIN =================
        log("\nDa gui het " + (segmentIndex - 1) + " segment. Gui goi FIN de bao ket thuc.");
        sendDataSegment(currentSeq, new byte[0], Packet.FLAG_FIN, -1);
        waitUntilAcked(currentSeq);
        report.transferMs = System.currentTimeMillis() - handshakeDone;

        if (transferFailed) {
            report.failReason = "Gui FIN that bai (khong nhan duoc ACK cua FIN)";
            return;
        }
        log("Server da xac nhan FIN. Phien truyen du lieu ket thuc thanh cong!");

        // ================= BUOC 4: KIEM TRA DU LIEU DEN NOI CO DUNG KHONG =================
        // Server gui kem trong ACK cua FIN: "len=<so byte>;crc=<CRC32>" cua du lieu no rap duoc.
        // Client tu tinh cung 2 so do tren du lieu da gui roi so sanh.
        CRC32 crc = new CRC32();
        crc.update(fullData);
        String expected = "len=" + fullData.length + ";crc=" + Long.toHexString(crc.getValue());
        String got = serverReport;
        if (got == null) {
            report.dataCheck = "KHONG_XAC_MINH";
            log("Kiem tra du lieu: Server khong gui bao cao (dang dung Server.java ban cu?).");
        } else if (got.equals(expected)) {
            report.dataCheck = "DUNG";
            log("Kiem tra du lieu: DUNG - Server rap duoc " + got + " (khop voi du lieu da gui).");
        } else {
            report.dataCheck = "SAI";
            log("Kiem tra du lieu: SAI! Da gui " + expected + " nhung Server bao " + got);
        }
        if (report.dataCheck.equals("SAI")) {
            report.failReason = "Du lieu Server rap duoc KHAC du lieu da gui";
            return;
        }
        report.success = true;
    }

    /** Gui 1 segment (hoac FIN) va dang ky vao unackedSegments de 2 thread kia theo doi. */
    static void sendDataSegment(int seq, byte[] payload, byte flags, int segmentIndex) throws Exception {
        Packet packet = new Packet(seq, 0, flags, payload);

        // Ghi vao bang TRUOC khi gui: neu gui truoc, ACK co the ve nhanh hon luc ta ghi
        // vao bang -> ReceiverThread khong tim thay gi de xoa -> segment ket lai o bang
        // va bi gui lai thua. synchronized: chi 1 thread duoc sua bang tai 1 thoi diem.
        int inflight;
        synchronized (unackedSegments) {
            unackedSegments.put(seq, new SegmentInfo(payload, flags, System.currentTimeMillis()));
            inflight = unackedSegments.size();
        }
        sendRaw(packet);
        if (payload.length > 0) statFirstSends.incrementAndGet();

        String label = (flags == Packet.FLAG_FIN) ? "FIN" : ("segment #" + segmentIndex);
        log("Gui " + label + " (seq=" + seq + ")"
                + (payload.length > 0 ? ": \"" + new String(payload) + "\"" : "")
                + " | dang bay=" + inflight + "/" + currentWindow());
    }

    /** BUOC 1: main thread dung o day cho toi khi cua so con cho trong (hoac truyen that bai). */
    static void waitForWindowRoom() throws InterruptedException {
        while (!transferFailed) {
            int inflight;
            synchronized (unackedSegments) {
                inflight = unackedSegments.size();
            }
            if (inflight < currentWindow()) {
                return;
            }
            Thread.sleep(5);
        }
    }

    /** Cho toi khi khong con segment nao chua duoc ACK. */
    static void waitUntilAllAcked() throws InterruptedException {
        while (!transferFailed) {
            synchronized (unackedSegments) {
                if (unackedSegments.isEmpty()) {
                    return;
                }
            }
            Thread.sleep(5);
        }
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
    static void receiverLoop(int gen) {
        while (sessionGen == gen) {
            try {
                Packet response = receiveRaw();
                if (Packet.hasFlag(response.flags, Packet.FLAG_ACK)) {
                    int ackNum = response.ackNum;
                    // BUOC 4: ACK cua FIN co kem bao cao cua Server -> luu lai de doi chieu cuoi phien.
                    if (response.payload.length > 0) {
                        serverReport = new String(response.payload, StandardCharsets.UTF_8);
                    }
                    int ackedData = 0; // so segment DU LIEU vua duoc ACK (de tang cwnd)
                    synchronized (unackedSegments) {
                        // Cumulative ACK: server bao "toi da nhan dung toi byte X",
                        // nen xoa MOI segment co seq < X (khong chi segment vua gui).
                        Iterator<Map.Entry<Integer, SegmentInfo>> it = unackedSegments.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<Integer, SegmentInfo> acked = it.next();
                            if (acked.getKey() < ackNum) {
                                if (acked.getValue().payload.length > 0) ackedData++;
                                it.remove();
                            }
                        }
                    }
                    if (ackedData > 0) {
                        congestion.onAcked(ackedData, windowSize);
                    }
                    log("Nhan ACK, server dang cho seq=" + ackNum);
                    EventBus.publish(new PacketEvent(PacketEvent.Status.ACKED, "Server -> Client",
                            "ACK", response.seqNum, ackNum, ""));
                }
            } catch (Exception e) {
                return; // socket dong (ket thuc chuong trinh) -> thoat thread trong yen lang
            }
        }
    }

    /** TimeoutThread: viec DUY NHAT no lam la ra soat dinh ky va gui lai khi qua han. */
    static void timeoutCheckerLoop(int gen) {
        while (sessionGen == gen) {
            try {
                Thread.sleep(TIMEOUT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (transferFailed || sessionGen != gen) return;

            long now = System.currentTimeMillis();
            synchronized (unackedSegments) {
                for (Map.Entry<Integer, SegmentInfo> entry : unackedSegments.entrySet()) {
                    int seq = entry.getKey();
                    SegmentInfo info = entry.getValue();

                    if (now - info.sentTime >= RTO_MS) {
                        congestion.onTimeout(now); // giam cwnd (tu gioi han: chi 1 lan moi dot mat goi)
                        if (info.retryCount >= MAX_DATA_RETRIES) {
                            log("LOI: seq=" + seq + " qua " + MAX_DATA_RETRIES
                                    + " lan gui lai van khong duoc ACK. Huy phien truyen.");
                            transferFailed = true;
                            break;
                        }
                        try {
                            Packet resend = new Packet(seq, 0, info.flags, info.payload);
                            // Goi truc tiep NetworkSimulator.send (khong qua sendRaw) de
                            // bao dung trang thai RETRANSMITTED thay vi SENT cho GUI.
                            NetworkSimulator.send(clientSocket, resend, serverAddress, SERVER_PORT,
                                    "Client -> Server", PacketEvent.Status.RETRANSMITTED);
                            info.retryCount++;
                            if (info.payload.length > 0) statRetransmits.incrementAndGet();
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

    /** Dung cho LAN GUI DAU cua moi goi tin (SYN, ACK, segment, FIN) - bao GUI la SENT. */
    static void sendRaw(Packet packet) throws Exception {
        NetworkSimulator.send(clientSocket, packet, serverAddress, SERVER_PORT,
                "Client -> Server", PacketEvent.Status.SENT);
    }

    static Packet receiveRaw() throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        clientSocket.receive(datagram);
        return Packet.decode(datagram.getData(), datagram.getLength());
    }
}