package tcpsim.gui;

/**
 * SessionReport.java (Buoc 4)
 * ---------------------------
 * Ket qua thong ke cua MOT phien, Client dien vao khi phien ket thuc roi gui
 * cho GUI hien thi.
 */
public class SessionReport {
    public boolean success = false;
    public String failReason = "";

    /** "DUNG" | "SAI" | "KHONG_XAC_MINH" - so sanh du lieu Server rap duoc voi du lieu da gui. */
    public String dataCheck = "KHONG_XAC_MINH";

    public int payloadBytes = 0;         // so byte du lieu da gui
    public int segments = 0;             // so segment du lieu (khong tinh gui lai)
    public int dataTransmissions = 0;    // tong so luot gui segment du lieu (ke ca gui lai)
    public int retransmissions = 0;      // so luot gui lai
    public int lostPackets = 0;          // so goi bi mat do mo phong tren Client
    public int cwndReductions = 0;       // so lan cwnd bi giam do timeout
    public double maxCwnd = 0;           // cwnd lon nhat dat duoc
    public boolean ccEnabled = true;     // dieu khien tac nghen co bat khong

    public long handshakeMs = 0;         // thoi gian bat tay
    public long transferMs = 0;          // thoi gian truyen du lieu (tu sau bat tay den khi FIN duoc ACK)
}
