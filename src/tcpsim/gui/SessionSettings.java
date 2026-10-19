package tcpsim.gui;

/**
 * SessionSettings.java (Buoc 5)
 * -----------------------------
 * Gom TAT CA tham so cua MOT phien truyen du lieu. Gia tri ban dau doc tu
 * client-config.txt, sau do nguoi dung co the sua ngay tren GUI roi bam "Chay lai".
 */
public class SessionSettings {
    public String serverIp = "127.0.0.1";
    public int lossPercent = 0;          // ti le mat goi gia lap cua Client (%)
    public int delayMinMs = 0;           // do tre toi thieu moi goi (ms)
    public int delayMaxMs = 0;           // do tre toi da moi goi (ms)
    public int windowSize = 32;          // so segment toi da duoc dang bay
    public boolean congestionControl = true;
    public int ssthresh = 16;            // nguong ssthresh ban dau (segment)
    public int messageRepeat = 8;        // so lan lap cau mau (moi lan 47 byte)
}
