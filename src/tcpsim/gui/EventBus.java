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

    private static final ConcurrentLinkedQueue<PacketEvent> queue = new ConcurrentLinkedQueue<>();

    /** Goi ham nay bat cu luc nao co mot su kien goi tin can hien thi. */
    public static void publish(PacketEvent event) {
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