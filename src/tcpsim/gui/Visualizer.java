package tcpsim.gui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;

/**
 * Visualizer.java
 * ---------------
 * Cua so do hoa (Swing) hien thi 2 phan:
 *   1. PHAN TREN - "so do trinh tu" (sequence diagram): Client ben trai,
 *      Server ben phai, moi goi tin la mot mui ten noi 2 duong doc,
 *      to mau theo trang thai (Sent/Acked/Lost/Retransmitted).
 *   2. PHAN DUOI - bang log dang dong thoi gian (giong Wireshark rut gon):
 *      Thoi gian | Chieu | Loai goi | Seq | Ack | Trang thai
 *
 * Cach chay: goi Visualizer.launch() tu Client.java SAU KHI da mo socket -
 * ham nay tu tao cua so va tu khoi dong 1 Timer doc du lieu tu EventBus.
 *
 * LUU Y QUAN TRONG VE THREADING TRONG SWING:
 * Swing KHONG an toan khi nhieu thread cung ve/cap nhat giao dien
 * (khac voi EventBus - noi nhieu thread duoc phep publish() thoai mai).
 * Vi vay moi thao tac cap nhat giao dien (them dong vao bang, ve lai
 * so do) DEU phai chay tren "Event Dispatch Thread" (EDT) - o day dam
 * bao dieu do bang cach dung javax.swing.Timer (ban than no da tu chay
 * tren EDT) thay vi dung Thread.sleep() + vong lap tay nhu cac thread
 * khac trong Client.java.
 */
public class Visualizer {

    private static final int MAX_ARROWS_SHOWN = 14; // so mui ten toi da ve tren so do
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final JFrame frame;
    private final DiagramPanel diagramPanel;
    private final DefaultTableModel tableModel;

    private Visualizer() {
        frame = new JFrame("Mo phong TCP - Truc quan hoa goi tin (PBL4)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(760, 640);
        frame.setLayout(new BorderLayout());

        diagramPanel = new DiagramPanel();
        diagramPanel.setPreferredSize(new Dimension(760, 380));
        frame.add(diagramPanel, BorderLayout.CENTER);

        String[] columns = {"Thoi gian", "Chieu", "Loai goi", "Seq", "Ack", "Trang thai", "Ghi chu"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // bang chi de xem, khong cho sua
            }
        };
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(760, 220));
        frame.add(scrollPane, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Timer cua Swing: ham actionPerformed duoc goi dinh ky TREN EDT,
        // nen ben trong duoc phep cap nhat giao dien truc tiep, an toan.
        Timer refreshTimer = new Timer(150, e -> refresh());
        refreshTimer.start();
    }

    /** Goi ham nay (tu bat ky thread nao) de mo cua so truc quan hoa. */
    public static void launch() {
        // Cac thao tac tao GUI LAN DAU cung nen chay tren EDT cho dung chuan,
        // dung SwingUtilities.invokeLater de dam bao dieu do.
        SwingUtilities.invokeLater(Visualizer::new);
    }

    /** Duoc goi dinh ky boi Timer (tren EDT): hut het su kien moi tu EventBus va ve lai. */
    private void refresh() {
        List<PacketEvent> newEvents = EventBus.drainAll();
        if (newEvents.isEmpty()) {
            return;
        }
        for (PacketEvent event : newEvents) {
            diagramPanel.addEvent(event);

            tableModel.insertRow(0, new Object[]{
                event.time.format(TIME_FORMAT),
                event.direction,
                event.packetType,
                event.seqNum,
                event.ackNum,
                statusLabel(event.status),
                event.note == null ? "" : event.note
            });
        }
        diagramPanel.repaint();
    }

    private static String statusLabel(PacketEvent.Status status) {
        switch (status) {
            case SENT: return "Da gui";
            case ACKED: return "Da xac nhan (ACK)";
            case LOST: return "MAT GOI";
            case RETRANSMITTED: return "GUI LAI";
            default: return status.toString();
        }
    }

    private static Color colorFor(PacketEvent.Status status) {
        switch (status) {
            case SENT: return new Color(120, 120, 120);       // xam
            case ACKED: return new Color(34, 139, 34);         // xanh la
            case LOST: return new Color(200, 30, 30);           // do
            case RETRANSMITTED: return new Color(230, 140, 0);  // cam
            default: return Color.BLACK;
        }
    }

    /** Panel tu ve so do trinh tu (sequence diagram) don gian. */
    private static class DiagramPanel extends JPanel {
        private final LinkedList<PacketEvent> recentEvents = new LinkedList<>();

        DiagramPanel() {
            setBackground(Color.WHITE);
        }

        void addEvent(PacketEvent event) {
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_ARROWS_SHOWN) {
                recentEvents.removeFirst();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int leftX = width / 4;
            int rightX = (width * 3) / 4;
            int topY = 40;
            int bottomY = height - 20;

            // Nhan va duong doc cho Client / Server
            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 15f));
            g2.drawString("CLIENT", leftX - 30, 25);
            g2.drawString("SERVER", rightX - 32, 25);

            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(leftX, topY, leftX, bottomY);
            g2.drawLine(rightX, topY, rightX, bottomY);

            if (recentEvents.isEmpty()) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
                g2.setColor(Color.GRAY);
                g2.drawString("Dang cho du lieu... hay chay Client de bat dau truyen.", leftX - 60, height / 2);
                return;
            }

            int rowHeight = (bottomY - topY - 20) / Math.max(recentEvents.size(), 1);
            rowHeight = Math.max(rowHeight, 24); // khong de mui ten qua sat nhau

            int y = topY + 30;
            for (PacketEvent event : recentEvents) {
                boolean clientToServer = event.direction.startsWith("Client");
                int fromX = clientToServer ? leftX : rightX;
                int toX = clientToServer ? rightX : leftX;

                g2.setColor(colorFor(event.status));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(fromX, y, toX, y);
                drawArrowHead(g2, fromX, toX, y);

                String label = event.packetType + " seq=" + event.seqNum
                        + (event.ackNum != 0 ? " ack=" + event.ackNum : "");
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
                int textX = Math.min(fromX, toX) + 10;
                g2.drawString(label, textX, y - 6);

                y += rowHeight;
            }
        }

        /** Ve mot mui ten nho o dau den cua duong thang, huong theo chieu di. */
        private void drawArrowHead(Graphics2D g2, int fromX, int toX, int y) {
            int direction = (toX > fromX) ? -1 : 1; // huong mui ten "lui lai" so voi diem den
            int size = 6;
            int[] xs = {toX, toX + direction * size, toX + direction * size};
            int[] ys = {y, y - size / 2, y + size / 2};
            g2.fillPolygon(xs, ys, 3);
        }
    }
}