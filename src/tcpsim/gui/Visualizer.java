package tcpsim.gui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Visualizer.java (Buoc 3)
 * ------------------------
 * Cua so do hoa (Swing) gom 4 phan:
 *   1. THANH TRANG THAI (tren cung): cwnd, ssthresh, giai doan hien tai,
 *      va so lieu dem: da gui / gui lai / mat goi / ACK nhan.
 *   2. SO DO TRINH TU (giua, ben trai): Client o trai, Server o phai, moi goi tin
 *      la 1 mui ten, to mau theo trang thai (xam=da gui, xanh=ACK, do=mat, cam=gui lai).
 *   3. BIEU DO cwnd (giua, ben phai): duong xanh = cwnd, duong cam net dut = ssthresh,
 *      cham do = thoi diem timeout (mat goi).
 *   4. BANG LOG (duoi cung): moi su kien 1 dong, moi nhat o tren.
 *
 * Du lieu den tu EventBus (hang doi an toan da luong). Moi thao tac cap nhat giao dien
 * deu chay tren Event Dispatch Thread nho dung javax.swing.Timer.
 */
public class Visualizer {

    private static final int MAX_EVENTS_KEPT = 500;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final JFrame frame;
    private final DiagramPanel diagramPanel;
    private final ChartPanel chartPanel;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;

    // So lieu dem hien tren thanh trang thai
    private int sentCount = 0;
    private int retransCount = 0;
    private int lostCount = 0;
    private int ackCount = 0;
    private double[] lastSample = null;

    private Visualizer() {
        frame = new JFrame("Mo phong TCP - Truc quan hoa goi tin (PBL4)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1100, 760);
        frame.setLayout(new BorderLayout());

        // ---- 1. Thanh trang thai ----
        statusLabel = new JLabel(" Dang cho du lieu...");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(235, 241, 250));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        frame.add(statusLabel, BorderLayout.NORTH);

        // ---- 2 + 3. So do trinh tu (trai) va bieu do cwnd (phai) ----
        diagramPanel = new DiagramPanel();
        chartPanel = new ChartPanel();
        JPanel center = new JPanel(new GridLayout(1, 2, 6, 0));
        center.add(diagramPanel);
        center.add(chartPanel);
        frame.add(center, BorderLayout.CENTER);

        // ---- 4. Bang log ----
        String[] columns = {"Thoi gian", "Chieu", "Loai goi", "Seq", "Ack", "Trang thai", "Ghi chu"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(1100, 190));
        frame.add(scrollPane, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Timer refreshTimer = new Timer(150, e -> refresh());
        refreshTimer.start();
    }

    /** Goi ham nay (tu bat ky thread nao) de mo cua so truc quan hoa. */
    public static void launch() {
        SwingUtilities.invokeLater(Visualizer::new);
    }

    /** Duoc goi dinh ky boi Timer (tren EDT): hut het du lieu moi tu EventBus va ve lai. */
    private void refresh() {
        List<PacketEvent> newEvents = EventBus.drainAll();
        List<double[]> newSamples = EventBus.drainCwnd();
        if (newEvents.isEmpty() && newSamples.isEmpty()) {
            return;
        }

        for (PacketEvent event : newEvents) {
            diagramPanel.addEvent(event);
            countEvent(event);
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
        for (double[] sample : newSamples) {
            chartPanel.addSample(sample);
            lastSample = sample;
        }

        updateStatusBar();
        diagramPanel.repaint();
        chartPanel.repaint();
    }

    private void countEvent(PacketEvent event) {
        switch (event.status) {
            case SENT:
                if (event.direction.startsWith("Client")) sentCount++;
                break;
            case RETRANSMITTED:
                retransCount++;
                break;
            case LOST:
                lostCount++;
                break;
            case ACKED:
                if (event.packetType.equals("ACK")) ackCount++;
                break;
            default:
                break;
        }
    }

    private void updateStatusBar() {
        String cc;
        if (lastSample == null) {
            cc = "Dieu khien tac nghen: chua co du lieu (hoac dang TAT)";
        } else {
            cc = String.format("cwnd = %.2f   |   ssthresh = %.0f   |   %s",
                    lastSample[1], lastSample[2], phaseName((int) lastSample[3]));
        }
        statusLabel.setText(" " + cc + "          ||          Da gui: " + sentCount
                + "   Gui lai: " + retransCount + "   Mat goi: " + lostCount + "   ACK nhan: " + ackCount);
    }

    private static String phaseName(int type) {
        switch (type) {
            case 0: return "SLOW START";
            case 1: return "CONGESTION AVOIDANCE";
            case 2: return "TIMEOUT -> cwnd ve 1";
            default: return "?";
        }
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
            case SENT: return new Color(120, 120, 120);
            case ACKED: return new Color(34, 139, 34);
            case LOST: return new Color(200, 30, 30);
            case RETRANSMITTED: return new Color(230, 140, 0);
            default: return Color.BLACK;
        }
    }

    // =====================================================================
    // SO DO TRINH TU
    // =====================================================================
    private static class DiagramPanel extends JPanel {
        private static final int ROW_HEIGHT = 22;
        private final List<PacketEvent> events = new ArrayList<>();

        DiagramPanel() {
            setBackground(Color.WHITE);
        }

        void addEvent(PacketEvent event) {
            events.add(event);
            while (events.size() > MAX_EVENTS_KEPT) {
                events.remove(0);
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
            int bottomY = height - 30; // chua cho cho chu thich mau o day

            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 15f));
            g2.drawString("CLIENT", leftX - 30, 25);
            g2.drawString("SERVER", rightX - 32, 25);

            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(leftX, topY, leftX, bottomY);
            g2.drawLine(rightX, topY, rightX, bottomY);

            drawLegend(g2, height);

            if (events.isEmpty()) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
                g2.setColor(Color.GRAY);
                g2.drawString("Dang cho du lieu...", leftX + 20, height / 2);
                return;
            }

            // So mui ten ve vua khit chieu cao khung -> khong bao gio bi che
            int slots = Math.max(1, (bottomY - topY - 30) / ROW_HEIGHT);
            int start = Math.max(0, events.size() - slots);

            int y = topY + 24;
            for (PacketEvent event : events.subList(start, events.size())) {
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
                g2.drawString(label, Math.min(fromX, toX) + 10, y - 5);

                y += ROW_HEIGHT;
            }
        }

        private void drawLegend(Graphics2D g2, int height) {
            String[] names = {"Da gui", "ACK", "Mat goi", "Gui lai"};
            PacketEvent.Status[] statuses = {PacketEvent.Status.SENT, PacketEvent.Status.ACKED,
                    PacketEvent.Status.LOST, PacketEvent.Status.RETRANSMITTED};
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            int x = 12;
            int y = height - 10;
            for (int i = 0; i < names.length; i++) {
                g2.setColor(colorFor(statuses[i]));
                g2.fillRect(x, y - 9, 12, 10);
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(names[i], x + 16, y);
                x += 16 + g2.getFontMetrics().stringWidth(names[i]) + 14;
            }
        }

        private void drawArrowHead(Graphics2D g2, int fromX, int toX, int y) {
            int direction = (toX > fromX) ? -1 : 1;
            int size = 6;
            int[] xs = {toX, toX + direction * size, toX + direction * size};
            int[] ys = {y, y - size / 2, y + size / 2};
            g2.fillPolygon(xs, ys, 3);
        }
    }

    // =====================================================================
    // BIEU DO cwnd
    // =====================================================================
    private static class ChartPanel extends JPanel {
        private final List<double[]> samples = new ArrayList<>();

        ChartPanel() {
            setBackground(Color.WHITE);
        }

        void addSample(double[] sample) {
            samples.add(sample);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int left = 48, right = 18, top = 48, bottom = 44;
            int pw = w - left - right;
            int ph = h - top - bottom;

            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 15f));
            g2.drawString("BIEU DO cwnd (so segment)", left, 24);

            if (samples.size() < 2) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
                g2.setColor(Color.GRAY);
                g2.drawString("Chua co du lieu cwnd", left + 10, h / 2);
                g2.drawString("(neu congestion_control=off thi khong co bieu do)", left + 10, h / 2 + 20);
                return;
            }

            double maxT = Math.max(samples.get(samples.size() - 1)[0], 1);
            double maxY = 4;
            for (double[] s : samples) {
                maxY = Math.max(maxY, Math.max(s[1], s[2]));
            }
            maxY = Math.ceil(maxY + 1);

            // Luoi ngang + nhan truc Y
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            int yTicks = 5;
            for (int i = 0; i <= yTicks; i++) {
                int y = top + ph - (ph * i) / yTicks;
                g2.setColor(new Color(225, 225, 225));
                g2.drawLine(left, y, left + pw, y);
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(String.format("%.0f", maxY * i / yTicks), 12, y + 4);
            }
            // Nhan truc X (giay)
            for (int i = 0; i <= 5; i++) {
                int x = left + (pw * i) / 5;
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(String.format("%.1fs", maxT * i / 5 / 1000.0), x - 12, top + ph + 16);
            }
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(left, top, left, top + ph);
            g2.drawLine(left, top + ph, left + pw, top + ph);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString("thoi gian (giay)", left + pw / 2 - 40, h - 8);

            // ssthresh: duong bac thang net dut mau cam
            g2.setColor(new Color(230, 140, 0));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{6f, 5f}, 0f));
            for (int i = 1; i < samples.size(); i++) {
                double[] a = samples.get(i - 1);
                double[] b = samples.get(i);
                int xa = px(a[0], maxT, left, pw);
                int xb = px(b[0], maxT, left, pw);
                int ya = py(a[2], maxY, top, ph);
                int yb = py(b[2], maxY, top, ph);
                g2.drawLine(xa, ya, xb, ya);
                g2.drawLine(xb, ya, xb, yb);
            }

            // cwnd: duong lien mau xanh duong
            g2.setColor(new Color(30, 90, 200));
            g2.setStroke(new BasicStroke(2.2f));
            for (int i = 1; i < samples.size(); i++) {
                double[] a = samples.get(i - 1);
                double[] b = samples.get(i);
                g2.drawLine(px(a[0], maxT, left, pw), py(a[1], maxY, top, ph),
                        px(b[0], maxT, left, pw), py(b[1], maxY, top, ph));
            }

            // Cham do tai moi lan timeout
            g2.setColor(new Color(200, 30, 30));
            for (double[] s : samples) {
                if ((int) s[3] == 2) {
                    int x = px(s[0], maxT, left, pw);
                    int y = py(s[1], maxY, top, ph);
                    g2.fillOval(x - 5, y - 5, 10, 10);
                }
            }

            drawLegend(g2, left, 38);
        }

        private void drawLegend(Graphics2D g2, int x, int y) {
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            g2.setStroke(new BasicStroke(2.2f));
            g2.setColor(new Color(30, 90, 200));
            g2.drawLine(x, y - 4, x + 18, y - 4);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString("cwnd", x + 22, y);
            x += 70;
            g2.setColor(new Color(230, 140, 0));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{6f, 5f}, 0f));
            g2.drawLine(x, y - 4, x + 18, y - 4);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString("ssthresh", x + 22, y);
            x += 90;
            g2.setColor(new Color(200, 30, 30));
            g2.fillOval(x, y - 9, 10, 10);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString("timeout (mat goi)", x + 15, y);
        }

        private static int px(double t, double maxT, int left, int pw) {
            return left + (int) Math.round(pw * (t / maxT));
        }

        private static int py(double v, double maxY, int top, int ph) {
            return top + ph - (int) Math.round(ph * (v / maxY));
        }
    }
}
