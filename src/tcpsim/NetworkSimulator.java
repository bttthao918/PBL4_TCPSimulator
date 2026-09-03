package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import tcpsim.gui.EventBus;
import tcpsim.gui.PacketEvent;

/**
 * NetworkSimulator.java (Buoc 6, cap nhat o Buoc 7 de bao su kien cho GUI)
 * ---------------------------------------------------------------------------
 * Ngoai viec mo phong mat goi/tre nhu Buoc 6, gio day moi lan gui (hoac huy)
 * mot goi tin, class nay se goi EventBus.publish(...) de "bao tin" cho giao
 * dien do hoa (neu co dang mo) biet chuyen gi vua xay ra.
 *
 * Neu khong co Visualizer nao dang chay, cac publish() nay khong gay hai gi -
 * chi la khong ai doc su kien do (xem giai thich trong EventBus.java).
 */
public class NetworkSimulator {

    static double packetLossRate = 0.0;
    static int minDelayMs = 0;
    static int maxDelayMs = 0;

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

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
     * Gui mot goi tin, co mo phong mat goi/tre, va bao su kien cho GUI.
     *
     * @param direction       "Client -> Server" hoac "Server -> Client" - dung de
     *                        GUI biet ve mui ten theo chieu nao.
     * @param statusIfSent    trang thai se bao cho GUI NEU goi tin thuc su duoc gui
     *                        (thuong la SENT cho lan gui dau, RETRANSMITTED cho lan gui lai).
     *                        Neu goi tin bi mat, GUI se luon nhan trang thai LOST bat ke
     *                        tham so nay truyen gi vao.
     */
    public static boolean send(DatagramSocket socket, Packet packet, InetAddress address, int port,
                                String direction, PacketEvent.Status statusIfSent) {
        byte[] bytes = packet.encode();
        String note = (packet.payload.length > 0) ? new String(packet.payload) : "";

        // ---- Buoc 1: quyet dinh co huy goi nay khong ----
        if (random.nextDouble() < packetLossRate) {
            log("[DROPPED] seq=" + packet.seqNum + " " + packet.flagsToString()
                    + " -> goi tin bi mat tren duong truyen (mo phong).");
            EventBus.publish(new PacketEvent(
                PacketEvent.Status.LOST, direction, packet.flagsToString(),
                packet.seqNum, packet.ackNum, "Mo phong mang - " + note
            ));
            return false;
        }

        // ---- Buoc 2: tinh do tre ngau nhien (neu co cau hinh) ----
        int delay = 0;
        if (maxDelayMs > 0) {
            delay = minDelayMs + random.nextInt(maxDelayMs - minDelayMs + 1);
        }

        // Bao su kien cho GUI NGAY LUC QUYET DINH GUI (giong thoi diem log
        // console "Da gui..." trong Client.java) - du goi tin co the con
        // dang "tren duong" (dang tre) thi vi tri ve tren so do van hop ly.
        EventBus.publish(new PacketEvent(statusIfSent, direction, packet.flagsToString(),
                packet.seqNum, packet.ackNum, note));

        if (delay == 0) {
            sendNow(socket, bytes, address, port);
        } else {
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