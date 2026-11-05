package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    /** BUOC 4: so goi bi mat (gia lap) ke tu lan configure() gan nhat - de thong ke cuoi phien. */
    public static final java.util.concurrent.atomic.AtomicInteger lostCount = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger corruptedCount = new java.util.concurrent.atomic.AtomicInteger();

    static double packetLossRate = 0.0;
    static double packetCorruptRate = 0.0;
    static int minDelayMs = 0;
    static int maxDelayMs = 0;

    static final Random random = new Random();
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DelayQueue<DelayedPacket> delayedPackets = new DelayQueue<>();
    private static final AtomicLong sendOrder = new AtomicLong();

    static {
        Thread dispatcher = new Thread(() -> {
            while (true) {
                try {
                    DelayedPacket delayed = delayedPackets.take();
                    sendNow(delayed.socket, delayed.bytes, delayed.address, delayed.port);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "NetworkDelayDispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    private static final class DelayedPacket implements Delayed {
        final DatagramSocket socket;
        final byte[] bytes;
        final InetAddress address;
        final int port;
        final long deadlineNanos;
        final long order;

        DelayedPacket(DatagramSocket socket, byte[] bytes, InetAddress address, int port,
                      long deadlineNanos, long order) {
            this.socket = socket;
            this.bytes = bytes;
            this.address = address;
            this.port = port;
            this.deadlineNanos = deadlineNanos;
            this.order = order;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            DelayedPacket that = (DelayedPacket) other;
            int byDeadline = Long.compare(deadlineNanos, that.deadlineNanos);
            return byDeadline != 0 ? byDeadline : Long.compare(order, that.order);
        }
    }

    public static void configure(double lossRate, double corruptRate, int minDelay, int maxDelay) {
        packetLossRate = lossRate;
        packetCorruptRate = corruptRate;
        lostCount.set(0);
        corruptedCount.set(0);
        minDelayMs = minDelay;
        maxDelayMs = maxDelay;
        log(String.format(
            "Cau hinh mang: mat goi=%.0f%%, hong goi=%.0f%%, do tre=%d-%dms",
            lossRate * 100, corruptRate * 100, minDelay, maxDelay
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
            lostCount.incrementAndGet();
            log("[DROPPED] seq=" + packet.seqNum + " " + packet.flagsToString()
                    + " -> goi tin bi mat tren duong truyen (mo phong).");
            EventBus.publish(new PacketEvent(
                PacketEvent.Status.LOST, direction, packet.flagsToString(),
                packet.seqNum, packet.ackNum, packet.window, "Mo phong mang - " + note,
                statusIfSent == PacketEvent.Status.RETRANSMITTED
            ));
            return false;
        }

        // ---- Buoc 2: lat ngau nhien 1 bit roi van gui goi di ----
        boolean corrupted = random.nextDouble() < packetCorruptRate;
        if (corrupted && bytes.length > 0) {
            int byteIndex = random.nextInt(bytes.length);
            int bitMask = 1 << random.nextInt(8);
            bytes[byteIndex] ^= (byte) bitMask;
            corruptedCount.incrementAndGet();
            log("[CORRUPTED] seq=" + packet.seqNum + " " + packet.flagsToString()
                    + " -> lat 1 bit tai byte " + byteIndex + ".");
        }

        // ---- Buoc 3: tinh do tre ngau nhien (neu co cau hinh) ----
        int delay = 0;
        if (maxDelayMs > 0) {
            delay = minDelayMs + random.nextInt(maxDelayMs - minDelayMs + 1);
        }

        // Bao su kien cho GUI NGAY LUC QUYET DINH GUI (giong thoi diem log
        // console "Da gui..." trong Client.java) - du goi tin co the con
        // dang "tren duong" (dang tre) thi vi tri ve tren so do van hop ly.
        PacketEvent.Status eventStatus = corrupted ? PacketEvent.Status.CORRUPTED : statusIfSent;
        EventBus.publish(new PacketEvent(eventStatus, direction, packet.flagsToString(),
                packet.seqNum, packet.ackNum, packet.window, note,
                statusIfSent == PacketEvent.Status.RETRANSMITTED));

        if (delay == 0) {
            sendNow(socket, bytes, address, port);
        } else {
            log("[DELAYED] seq=" + packet.seqNum + " " + packet.flagsToString()
                    + " -> se den sau " + delay + "ms.");
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
            delayedPackets.offer(new DelayedPacket(socket, bytes, address, port,
                    deadlineNanos, sendOrder.getAndIncrement()));
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
