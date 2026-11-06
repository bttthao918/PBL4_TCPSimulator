package tcpsim.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * EventBus.java
 * -------------
 * "Hop thu" dung chung, dung de cac lop mang (Client, NetworkSimulator -
 * dang chay tren nhieu thread khac nhau) BAO TIN cho giao dien do hoa
 * ma khong can biet giao dien co dang mo hay khong.
 *
 * Dung ConcurrentLinkedQueue vi day la cau truc du lieu AN TOAN VOI
 * NHIEU THREAD san co trong Java - khong can tu viet synchronized nhu
 * o Client.java, vi ban than class nay da duoc thiet ke de nhieu thread
 * cung publish() dong thoi ma khong sao.
 *
 * Neu khong co giao dien nao dang chay de "hut" du lieu ra, publish()
 * van chay binh thuong (khong loi), chi la khong ai doc su kien do -
 * dieu nay dam bao Client/Server van chay dung ke ca khi khong mo GUI.
 */
public class EventBus {
    private static final int MAX_EVENTS = 4000;
    private static final int MAX_SAMPLES = 4000;
    private static final ConcurrentLinkedQueue<double[]> progressQueue = new ConcurrentLinkedQueue<>();

    public static void publishProgress(long ackedBytes, long totalBytes, long startMs) {
        while (progressQueue.size() >= 8) progressQueue.poll();
        progressQueue.add(new double[]{ackedBytes, totalBytes, Math.max(0, System.currentTimeMillis() - startMs)});
    }

    public static double[] pollProgress() { return progressQueue.poll(); }

    private static final ConcurrentLinkedQueue<PacketEvent> queue = new ConcurrentLinkedQueue<>();

    // BUOC 3: hang doi rieng cho cac mau do cwnd, de GUI ve bieu do.
    // Moi mau la double[]{thoi_gian_ms, cwnd, ssthresh, loai}
    // voi loai: 0 = SLOW START, 1 = CONGESTION AVOIDANCE, 2 = TIMEOUT (mat goi).
    private static final ConcurrentLinkedQueue<double[]> cwndQueue = new ConcurrentLinkedQueue<>();

    public static void publishCwnd(double timeMs, double cwnd, double ssthresh, int type) {
        if (cwndQueue.size() < MAX_SAMPLES) cwndQueue.add(new double[]{timeMs, cwnd, ssthresh, type});
    }

    public static List<double[]> drainCwnd() {
        List<double[]> result = new ArrayList<>();
        double[] sample;
        while ((sample = cwndQueue.poll()) != null) {
            result.add(sample);
        }
        return result;
    }

    // Moi mau la double[]{thoi_gian_ms, SRTT_ms, RTO_ms}.
    private static final ConcurrentLinkedQueue<double[]> rtoQueue = new ConcurrentLinkedQueue<>();

    // Samples are double[]{time_ms, rwnd_bytes} for the flow-control graph.
    private static final ConcurrentLinkedQueue<double[]> rwndQueue = new ConcurrentLinkedQueue<>();

    public static void publishRwnd(double timeMs, int rwndBytes) {
        if (rwndQueue.size() < MAX_SAMPLES) rwndQueue.add(new double[]{timeMs, rwndBytes});
    }

    public static List<double[]> drainRwnd() {
        List<double[]> result = new ArrayList<>();
        double[] sample;
        while ((sample = rwndQueue.poll()) != null) result.add(sample);
        return result;
    }

    public static void publishRto(double timeMs, double srttMs, double rtoMs) {
        if (rtoQueue.size() < MAX_SAMPLES) rtoQueue.add(new double[]{timeMs, srttMs, rtoMs});
    }

    public static List<double[]> drainRto() {
        List<double[]> result = new ArrayList<>();
        double[] sample;
        while ((sample = rtoQueue.poll()) != null) result.add(sample);
        return result;
    }

    // BUOC 4: hang doi bao cao ket qua phien (moi phien ket thuc dua len 1 bao cao).
    private static final ConcurrentLinkedQueue<SessionReport> reportQueue = new ConcurrentLinkedQueue<>();

    public static void publishReport(SessionReport report) {
        reportQueue.add(report);
    }

    /** Lay bao cao cu nhat dang cho (hoac null neu chua co). */
    public static SessionReport pollReport() {
        return reportQueue.poll();
    }

    /** BUOC 5: xoa sach moi du lieu cu truoc khi chay phien moi. */
    public static void clearAll() {
        drainAll();
        drainCwnd();
        drainRto();
        drainRwnd();
        reportQueue.clear();
        progressQueue.clear();
    }

    /** Goi ham nay bat cu luc nao co mot su kien goi tin can hien thi. */
    public static void publish(PacketEvent event) {
        while (queue.size() >= MAX_EVENTS) queue.poll();
        queue.add(event);
    }

    /**
     * Lay TOAN BO su kien dang cho trong hang doi va xoa chung khoi hang doi.
     * Giao dien do hoa se goi ham nay dinh ky (vi du moi 150ms) de cap nhat
     * man hinh, thay vi phai "khoa" (lock) hang doi moi lan doc.
     */
    public static List<PacketEvent> drainAll() {
        List<PacketEvent> result = new ArrayList<>();
        PacketEvent event;
        while ((event = queue.poll()) != null) {
            result.add(event);
        }
        return result;
    }
}
