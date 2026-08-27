package tcpsim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Server.java
 * -----------
 * Server UDP đơn giản: lắng nghe ở một cổng cố định, nhận gói tin thô,
 * decode bằng Packet.java, và in log ra màn hình.
 *
 * Cách chạy:
 *   javac Packet.java Server.java Client.java
 *   java Server
 * Server sẽ chạy mãi cho tới khi bạn nhấn Ctrl+C để dừng.
 */
public class Server {

    // -----------------------------------------------------------------
    // 1. THÔNG SỐ CẤU HÌNH
    // -----------------------------------------------------------------
    static final int PORT = 5005;          // client sẽ gửi tới đúng cổng này
    static final int BUFFER_SIZE = 2048;   // số byte tối đa đọc mỗi lần nhận

    // -----------------------------------------------------------------
    // 2. TRẠNG THÁI KẾT NỐI (state machine) — chuẩn bị cho Bước 3
    // -----------------------------------------------------------------
    // Ở bước này ta chưa xử lý logic chuyển trạng thái thật,
    // chỉ khai báo biến để log cho quen, Bước 3 sẽ dùng biến này.
    static String connectionState = "CLOSED";

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
    }

    public static void main(String[] args) throws Exception {
        // Bước a: Tạo một UDP socket và "bind" (gắn) vào cổng cố định
        // ngay trong lúc khởi tạo — new DatagramSocket(PORT) đã tự làm việc đó.
        // Giống như đăng ký "tôi lắng nghe ở cổng này", client mới gửi tới được.
        DatagramSocket serverSocket = new DatagramSocket(PORT);

        log("Server dang lang nghe tai cong " + PORT + " ...");
        log("Trang thai ket noi: " + connectionState);

        // Bước b: chuẩn bị sẵn một vùng nhớ đệm (buffer) để chứa dữ liệu nhận về
        byte[] receiveBuffer = new byte[BUFFER_SIZE];

        // Bước c: Vòng lặp vô hạn - server luôn chờ nhận gói tin mới.
        while (true) {
            // DatagramPacket là "cái hộp rỗng" để socket đổ dữ liệu nhận được vào
            DatagramPacket receivedDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            // receive() sẽ "đứng chờ" (blocking) cho tới khi có gói tin đến —
            // giống recvfrom() bên Python.
            serverSocket.receive(receivedDatagram);

            // Lấy đúng phần dữ liệu thật sự nhận được (receivedDatagram.getLength())
            // vì receiveBuffer có thể dài hơn dữ liệu thật.
            byte[] rawData = receivedDatagram.getData();
            int length = receivedDatagram.getLength();

            // Địa chỉ + cổng của người gửi (client) — dùng khi cần gửi phản hồi lại
            InetSocketAddress clientAddress =
                (InetSocketAddress) receivedDatagram.getSocketAddress();

            // Dùng Packet.decode() để "mở phong bì" ra đọc.
            Packet packet = Packet.decode(rawData, length);

            log("Nhan goi tu " + clientAddress + " | " + packet);

            // Ở Bước 3, đây chính là chỗ ta sẽ thêm logic:
            // "nếu gói có cờ SYN thì phản hồi lại SYN-ACK", v.v.
            // Hiện tại ta chưa làm gì thêm, chỉ nhận và in ra để kiểm tra đường truyền.
        }
    }
}