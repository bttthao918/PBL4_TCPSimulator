package tcpsim;
import java.nio.ByteBuffer;

/**
 * Packet.java
 * -----------
 * Class dùng chung cho cả Client và Server.
 * Nhiệm vụ: định nghĩa "hình dạng" của một gói tin TCP giả lập,
 * và cung cấp hàm đóng gói (encode) / mở gói (decode).
 *
 * Không cần chạy file này trực tiếp trong đồ án — nó sẽ được dùng
 * bởi Server.java và Client.java. Nhưng file này CÓ hàm main() riêng
 * để bạn tự kiểm tra (xem cuối file).
 */
public class Packet {

    // -----------------------------------------------------------------
    // 1. ĐỊNH NGHĨA CÁC CỜ (FLAGS)
    // -----------------------------------------------------------------
    // Một gói tin có thể mang một hoặc nhiều "cờ" để báo mục đích của nó:
    //   SYN = xin bắt đầu kết nối (bước 1 của bắt tay 3 bước)
    //   ACK = xác nhận đã nhận được gói trước đó
    //   FIN = xin kết thúc kết nối
    //   RST = báo lỗi / hủy kết nối đột ngột
    //
    // Mỗi cờ chiếm 1 bit riêng trong 1 byte, để có thể "cộng dồn" nhiều cờ
    // cùng lúc (ví dụ gói SYN-ACK = bật cả cờ SYN và ACK cùng lúc).
    public static final byte FLAG_SYN = 0b0001; // = 1
    public static final byte FLAG_ACK = 0b0010; // = 2
    public static final byte FLAG_FIN = 0b0100; // = 4
    public static final byte FLAG_RST = 0b1000; // = 8

    // -----------------------------------------------------------------
    // 2. KÍCH THƯỚC HEADER
    // -----------------------------------------------------------------
    // Header gồm: seq_num (4 byte, int) + ack_num (4 byte, int)
    //           + flags (1 byte)         + checksum (4 byte, int)
    // => tổng cộng 13 byte, sau đó mới tới payload (dữ liệu thật).
    public static final int HEADER_SIZE = 4 + 4 + 1 + 4; // = 13

    // -----------------------------------------------------------------
    // 3. CÁC TRƯỜNG DỮ LIỆU CỦA MỘT GÓI TIN (giống object/dict trong Python)
    // -----------------------------------------------------------------
    public int seqNum;
    public int ackNum;
    public byte flags;
    public int checksum;
    public byte[] payload;
    public boolean checksumValid; // chỉ có ý nghĩa sau khi decode()

    // Constructor rỗng - dùng khi tự tạo packet để encode
    public Packet(int seqNum, int ackNum, byte flags, byte[] payload) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.flags = flags;
        this.payload = (payload != null) ? payload : new byte[0];
        this.checksum = calculateChecksum(this.payload);
    }

    // -----------------------------------------------------------------
    // 4. HÀM TÍNH CHECKSUM ĐƠN GIẢN
    // -----------------------------------------------------------------
    /**
     * Cộng dồn giá trị từng byte trong dữ liệu để ra một con số đại diện.
     * Nếu dữ liệu bị hỏng/thay đổi trên đường truyền, checksum tính lại
     * ở đầu nhận sẽ khác đi -> phát hiện được gói lỗi.
     * Đây là cách làm đơn giản (đủ cho đồ án); TCP thật dùng thuật toán
     * phức tạp hơn (one's complement), nhưng nguyên lý tương tự.
     */
    public static int calculateChecksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            // "& 0xFF" để lấy đúng giá trị byte không âm (Java byte có dấu)
            sum += (b & 0xFF);
        }
        return sum;
    }

    // -----------------------------------------------------------------
    // 5. HÀM ĐÓNG GÓI: encode() -> byte[]
    // -----------------------------------------------------------------
    /**
     * Biến gói tin thành một mảng byte để gửi qua mạng (DatagramPacket
     * chỉ nhận được byte[], không nhận được object Java trực tiếp).
     */
    public byte[] encode() {
        // ByteBuffer giúp ghi các số nguyên vào đúng vị trí, đúng kích thước,
        // giống "struct.pack" bên Python.
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.putInt(seqNum);      // 4 byte
        buffer.putInt(ackNum);      // 4 byte
        buffer.put(flags);          // 1 byte
        buffer.putInt(checksum);    // 4 byte
        buffer.put(payload);        // phần dữ liệu thật, dài bao nhiêu tùy payload
        return buffer.array();
    }

    // -----------------------------------------------------------------
    // 6. HÀM MỞ GÓI: decode(byte[], length) -> Packet
    // -----------------------------------------------------------------
    /**
     * Nhận vào bytes thô lấy được từ socket (kèm độ dài thật sự nhận được -
     * quan trọng vì UDP có thể trả về mảng dài hơn dữ liệu thật),
     * tách ra thành một object Packet dễ đọc.
     */
    public static Packet decode(byte[] rawData, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(rawData, 0, length);

        int seqNum = buffer.getInt();     // đọc 4 byte đầu
        int ackNum = buffer.getInt();     // đọc 4 byte tiếp theo
        byte flags = buffer.get();        // đọc 1 byte
        int checksum = buffer.getInt();   // đọc 4 byte tiếp theo

        // Phần còn lại (từ vị trí hiện tại tới hết "length") là payload
        int payloadLength = length - HEADER_SIZE;
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        Packet packet = new Packet(seqNum, ackNum, flags, payload);
        // Ghi đè checksum bằng giá trị ĐỌC ĐƯỢC (không tính lại),
        // rồi so sánh với checksum tính từ payload thật để kiểm tra lỗi.
        packet.checksum = checksum;
        packet.checksumValid = (calculateChecksum(payload) == checksum);
        return packet;
    }

    // -----------------------------------------------------------------
    // 7. HÀM TIỆN ÍCH
    // -----------------------------------------------------------------
    public static boolean hasFlag(byte flags, byte flag) {
        return (flags & flag) != 0;
    }

    public String flagsToString() {
        StringBuilder sb = new StringBuilder();
        if (hasFlag(flags, FLAG_SYN)) sb.append("SYN+");
        if (hasFlag(flags, FLAG_ACK)) sb.append("ACK+");
        if (hasFlag(flags, FLAG_FIN)) sb.append("FIN+");
        if (hasFlag(flags, FLAG_RST)) sb.append("RST+");
        if (sb.length() == 0) return "DATA";
        return sb.substring(0, sb.length() - 1); // bỏ dấu "+" thừa ở cuối
    }

    @Override
    public String toString() {
        return String.format(
            "loai=%s | seq=%d | ack=%d | payload=%s | checksum hop le=%b",
            flagsToString(), seqNum, ackNum,
            new String(payload), checksumValid
        );
    }

    // -----------------------------------------------------------------
    // 8. TỰ KIỂM TRA (chạy: java Packet)
    // -----------------------------------------------------------------
    public static void main(String[] args) {
        System.out.println("Kich thuoc header: " + HEADER_SIZE + " byte\n");

        // Thử đóng gói một gói SYN
        Packet syn = new Packet(1000, 0, FLAG_SYN, new byte[0]);
        byte[] encoded = syn.encode();
        System.out.println("Goi SYN da dong goi (" + encoded.length + " byte)");

        // Thử mở gói ra lại xem có đúng như ban đầu không
        Packet decoded = Packet.decode(encoded, encoded.length);
        System.out.println("Sau khi decode: " + decoded);

        System.out.println("\n--- Thu voi du lieu that ---");
        Packet dataPkt = new Packet(1, 0, (byte) 0, "Xin chao TCP".getBytes());
        byte[] encoded2 = dataPkt.encode();
        Packet decoded2 = Packet.decode(encoded2, encoded2.length);
        System.out.println("Sau khi decode: " + decoded2);
    }
}