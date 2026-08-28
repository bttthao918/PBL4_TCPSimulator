package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * NetworkSimulator.java (Buoc 6)
 * -------------------------------
 * Lop trung gian dung CHUNG cho ca Client va Server. Moi goi tin truoc khi
 * thuc su duoc gui di deu phai goi qua NetworkSimulator.send(...) thay vi
 * goi thang socket.send(...) nhu truoc.
 *
 * Nhiem vu:
 *   1. Voi xac suat packetLossRate -> HUY goi tin (khong gui, khong bao ai ca,
 *      dung ban chat that cua UDP khi mang loi).
 *   2. Neu khong bi huy -> co the lam TRE goi tin mot khoang ngau nhien
 *      trong [minDelayMs, maxDelayMs] truoc khi thuc su gui.
 *
 * Cau hinh mac dinh la KHONG mo phong gi ca (loss=0%, delay=0ms) de code
 * cu (Buoc 1-5) van chay dung y het khi chua cau hinh gi them.
 */
public class NetworkSimulator {

    // ---------------- Cau hinh (co the doi bang configure(...)) ----------------
    static double packetLossRate = 0.0; // ti le mat goi, vi du 0.2 = 20%
    static int minDelayMs = 0;          // do tre toi thieu (ms)
    static int maxDelayMs = 0;          // do tre toi da (ms)

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Goi ham nay 1 lan luc chuong trinh bat dau de thiet lap thong so mo phong. */
    public static void configure(double lossRate, int minDelay, int maxDelay) {
        packetLossRate = lossRate;
        minDelayMs = minDelay;
        maxDelayMs = maxDelay;
        log(String.format(
            "Cau hinh mang: ti le mat goi=%.0f%%, do tre=%d-%dms",
            lossRate * 100, minDelay, maxDelay
        ));
    }

    /**
     * Ham thay the cho socket.send(...) truc tiep. Tra ve true neu goi tin
     * DUOC LEN LICH gui (co the van dang bi delay o thread rieng), false neu
     * bi huy ngay tu dau (mat goi).
     */
    public static boolean send(DatagramSocket socket, Packet packet, InetAddress address, int port) {
        byte[] bytes = packet.encode();

        // ---- Buoc 1: quyet dinh co huy goi nay khong ----
        if (random.nextDouble() < packetLossRate) {
            log("[DROPPED] seq=" + packet.seqNum + " " + packet.flagsToString()
                    + " -> goi tin bi mat tren duong truyen (mo phong).");
            return false;
        }

        // ---- Buoc 2: tinh do tre ngau nhien (neu co cau hinh) ----
        int delay = 0;
        if (maxDelayMs > 0) {
            delay = minDelayMs + random.nextInt(maxDelayMs - minDelayMs + 1);
        }

        if (delay == 0) {
            // Khong tre - gui ngay lap tuc, khong can thread rieng.
            sendNow(socket, bytes, address, port);
        } else {
            // Co tre - phai gui tren MOT THREAD RIENG, neu khong ca chuong
            // trinh chinh se bi "dung hinh" (block) trong luc cho het tre.
            log("[DELAYED] seq=" + packet.seqNum + " " + packet.flagsToString()
                    + " -> se den sau " + delay + "ms.");
            final int finalDelay = delay;
            Thread delayThread = new Thread(() -> {
                try {
                    Thread.sleep(finalDelay);
                    sendNow(socket, bytes, address, port);
                } catch (InterruptedException ignored) {
                }
            }, "NetworkDelayThread");
            delayThread.setDaemon(true);
            delayThread.start();
        }
        return true;
    }

    private static void sendNow(DatagramSocket socket, byte[] bytes, InetAddress address, int port) {
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, address, port));
        } catch (Exception e) {
            log("Loi khi gui goi tin: " + e.getMessage());
        }
    }

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] [NetworkSimulator] " + message);
    }
}