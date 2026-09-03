package tcpsim.gui;

import java.time.LocalTime;

/**
 * PacketEvent.java
 * ----------------
 * Mot "su kien" ve goi tin, dung de gui tu Client/NetworkSimulator sang
 * giao dien do hoa (Visualizer). Khong chua logic gi ca, chi la noi
 * dong goi thong tin de hien thi.
 */
public class PacketEvent {

    /** Trang thai cua goi tin, dung de quyet dinh mau sac ve tren giao dien. */
    public enum Status {
        SENT,            // vua gui di, dang cho ACK - mau xam/vang
        ACKED,           // da duoc xac nhan - mau xanh la
        LOST,            // bi mat tren duong truyen (NetworkSimulator biet duoc) - mau do
        RETRANSMITTED    // bi gui lai do qua han (RTO) - mau cam
    }

    public final LocalTime time;
    public final Status status;
    public final String direction;   // "Client -> Server" hoac "Server -> Client"
    public final String packetType;  // vi du: "SYN", "SYN+ACK", "DATA", "FIN", "ACK"
    public final int seqNum;
    public final int ackNum;
    public final String note;        // ghi chu them, vi du noi dung payload

    public PacketEvent(Status status, String direction, String packetType,
                        int seqNum, int ackNum, String note) {
        this.time = LocalTime.now();
        this.status = status;
        this.direction = direction;
        this.packetType = packetType;
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.note = note;
    }
}