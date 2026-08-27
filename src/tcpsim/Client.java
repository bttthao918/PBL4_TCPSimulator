package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Client.java
 * -----------
 * Client UDP đơn giản: gửi thử một vài gói tin sang Server để kiểm tra
 * đường truyền hoạt động đúng.
 *
 * Cách chạy (SAU KHI Server đã chạy ở một terminal khác):
 *   java Client
 */
public class Client {

    // -----------------------------------------------------------------
    // 1. THÔNG SỐ CẤU HÌNH - phải khớp với Server.java
    // -----------------------------------------------------------------
    static final String SERVER_HOST = "127.0.0.1";
    static final int SERVER_PORT = 5005;

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    public static void main(String[] args) throws Exception {
        // Bước a: Tạo UDP socket - KHÔNG truyền số cổng, để hệ điều hành
        // tự cấp cho client một cổng "tạm" (ephemeral port).
        DatagramSocket clientSocket = new DatagramSocket();

        // Bước b: chuyển địa chỉ IP dạng chữ ("127.0.0.1") thành đối tượng InetAddress
        InetAddress serverAddress = InetAddress.getByName(SERVER_HOST);

        log("Client san sang, se gui goi tin toi " + SERVER_HOST + ":" + SERVER_PORT);

        // Bước c: Thử đóng gói MỘT gói tin có cờ SYN (giống bước đầu của bắt tay,
        // nhưng ở đây ta CHƯA xử lý phản hồi - chỉ để kiểm tra gửi/nhận thô).
        Packet synPacket = new Packet(1000, 0, Packet.FLAG_SYN, new byte[0]);
        byte[] synBytes = synPacket.encode();

        // DatagramPacket ở đây đóng vai trò "phong bì" mang dữ liệu + địa chỉ đích
        DatagramPacket synDatagram =
            new DatagramPacket(synBytes, synBytes.length, serverAddress, SERVER_PORT);

        // Bước d: send() - gửi bytes tới đúng địa chỉ server (giống sendto() bên Python)
        clientSocket.send(synDatagram);
        log("Da gui goi SYN, seq=1000");

        // Bước e: Thử gửi thêm một gói mang dữ liệu thật, để kiểm tra payload.
        byte[] payload = "Hello Server!".getBytes();
        Packet dataPacket = new Packet(1001, 0, (byte) 0, payload);
        byte[] dataBytes = dataPacket.encode();
        DatagramPacket dataDatagram =
            new DatagramPacket(dataBytes, dataBytes.length, serverAddress, SERVER_PORT);
        clientSocket.send(dataDatagram);
        log("Da gui goi DATA, seq=1001, payload=\"Hello Server!\"");

        log("Client da gui xong. Kiem tra terminal cua Server de xem da nhan dung chua.");

        clientSocket.close();
    }
}