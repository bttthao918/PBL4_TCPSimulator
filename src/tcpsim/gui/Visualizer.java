package tcpsim.gui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Visualizer.java (Buoc 3 + 4 + 5)
 * --------------------------------
 * Cua so do hoa (Swing) gom, tu tren xuong duoi:
 *   0. BANG DIEU KHIEN (Buoc 5): sua IP Server, ti le mat goi, do tre, cua so toi da,
 *      ssthresh, do dai du lieu, bat/tat dieu khien tac nghen, va nut "Chay lai".
 *   1. THANH TRANG THAI: cwnd, ssthresh, giai doan hien tai + bo dem goi tin.
 *   2. KHUNG KET QUA (Buoc 4): thong ke cuoi phien (thanh cong?, du lieu dung?, thoi gian,
 *      toc do, so lan gui lai...).
 *   3. SO DO TRINH TU (trai) va BIEU DO cwnd (phai).
 *   4. BANG LOG (duoi cung).
 *
 * GUI khong biet gi ve TCP: khi bam "Chay lai" no chi goi SessionRunner.runSession(...)
 * tren mot thread rieng (do Client cung cap) va doi bao cao ket qua tu EventBus.
 */
public class Visualizer {

    private static final int MAX_EVENTS_KEPT = 500;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final SessionRunner runner;

    private final JFrame frame;
    private final DiagramPanel diagramPanel;
    private final ChartPanel chartPanel;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;
    private final JLabel resultLabel;

    // ---- Bang dieu khien ----
    private final JTextField ipField;
    private final JSpinner lossSpinner;
    private final JSpinner delayMinSpinner;
    private final JSpinner delayMaxSpinner;
    private final JSpinner windowSpinner;
    private final JSpinner ssthreshSpinner;
    private final JSpinner sizeSpinner;
    private final JCheckBox ccCheck;
    private final JButton runButton;

    // So lieu dem hien tren thanh trang thai
    private int sentCount = 0;
    private int retransCount = 0;
    private int lostCount = 0;
    private int ackCount = 0;
    private double[] lastSample = null;

    private Visualizer(SessionSettings initial, SessionRunner runner) {
        this.runner = runner;

        frame = new JFrame("Mo phong TCP - Truc quan hoa goi tin (PBL4)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1100, 840);
        frame.setLayout(new BorderLayout());

        // ---- 0. Bang dieu khien (2 hang) ----
        ipField = new JTextField(initial.serverIp, 11);
        ipField.setName("ipField");
        lossSpinner = spinner("lossSpinner", initial.lossPercent, 0, 90, 5);
        delayMinSpinner = spinner("delayMinSpinner", initial.delayMinMs, 0, 3000, 50);
        delayMaxSpinner = spinner("delayMaxSpinner", initial.delayMaxMs, 0, 3000, 50);
        windowSpinner = spinner("windowSpinner", initial.windowSize, 1, 64, 1);
        ssthreshSpinner = spinner("ssthreshSpinner", initial.ssthresh, 2, 64, 1);
        sizeSpinner = spinner("sizeSpinner", initial.messageRepeat, 1, 100, 1);
        ccCheck = new JCheckBox("Dieu khien tac nghen (slow start)", initial.congestionControl);
        ccCheck.setName("ccCheck");
        runButton = new JButton("Chay lai");
        runButton.setName("runButton");
        runButton.setFont(runButton.getFont().deriveFont(Font.BOLD, 13f));
        runButton.addActionListener(e -> startSession());

        sizeSpinner.setToolTipText("Moi don vi = 47 byte (1 cau mau). Vi du 8 -> 376 byte -> 47 segment.");
        delayMaxSpinner.setToolTipText("Neu nho hon do tre min thi se duoc nang len bang do tre min.");
        windowSpinner.setToolTipText("Gioi han tren cua cwnd (so segment toi da dang bay).");

        JPanel row1 = row();
        row1.add(new JLabel("Server IP:"));
        row1.add(ipField);
        row1.add(new JLabel("   Mat goi (%):"));
        row1.add(lossSpinner);
        row1.add(new JLabel("   Tre min (ms):"));
        row1.add(delayMinSpinner);
        row1.add(new JLabel("   Tre max (ms):"));
        row1.add(delayMaxSpinner);

        JPanel row2 = row();
        row2.add(new JLabel("Cua so toi da:"));
        row2.add(windowSpinner);
        row2.add(new JLabel("   ssthresh:"));
        row2.add(ssthreshSpinner);
        row2.add(new JLabel("   Du lieu (x47 byte):"));
        row2.add(sizeSpinner);
        row2.add(Box.createHorizontalStrut(10));
        row2.add(ccCheck);
        row2.add(Box.createHorizontalStrut(14));
        row2.add(runButton);

        // ---- 1. Thanh trang thai + 2. Khung ket qua ----
        statusLabel = new JLabel(" Dang cho du lieu...");
        statusLabel.setName("statusLabel");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(235, 241, 250));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));

        resultLabel = new JLabel(resultPlaceholder("Ket qua phien se hien o day sau khi truyen xong."));
        resultLabel.setName("resultLabel");
        resultLabel.setFont(resultLabel.getFont().deriveFont(Font.PLAIN, 13f));
        resultLabel.setOpaque(true);
        resultLabel.setBackground(new Color(245, 245, 245));
        resultLabel.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));

        Box top = Box.createVerticalBox();
        for (JComponent c : new JComponent[]{row1, row2, statusLabel, resultLabel}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
            top.add(c);
        }
        frame.add(top, BorderLayout.NORTH);

        // ---- 3. So do trinh tu (trai) va bieu do cwnd (phai) ----
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
        scrollPane.setPreferredSize(new Dimension(1100, 170));
        frame.add(scrollPane, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Timer refreshTimer = new Timer(150, e -> refresh());
        refreshTimer.start();

        // Phien dau tien chay ngay khi cua so hien (giong hanh vi cu: mo Client la chay).
        startSession();
    }

    private static JSpinner spinner(String name, int value, int min, int max, int step) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
        sp.setName(name);
        ((JSpinner.DefaultEditor) sp.getEditor()).getTextField().setColumns(4);
        return sp;
    }

    private static JPanel row() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    }

    /** Mo cua so truc quan hoa (goi tu bat ky thread nao). Phien dau tien tu chay ngay. */
    public static void launch(SessionSettings initial, SessionRunner runner) {
        SwingUtilities.invokeLater(() -> new Visualizer(initial, runner));
    }

    // =====================================================================
    // CHAY / CHAY LAI MOT PHIEN
    // =====================================================================
    private int intValue(JSpinner sp) {
        try {
            sp.commitEdit(); // lay gia tri nguoi dung vua go (neu chua nhan Enter)
        } catch (java.text.ParseException ignored) {
        }
        return ((Number) sp.getValue()).intValue();
    }

    private SessionSettings readSettings() {
        SessionSettings s = new SessionSettings();
        s.serverIp = ipField.getText().trim().isEmpty() ? "127.0.0.1" : ipField.getText().trim();
        s.lossPercent = intValue(lossSpinner);
        s.delayMinMs = intValue(delayMinSpinner);
        s.delayMaxMs = intValue(delayMaxSpinner);
        s.windowSize = intValue(windowSpinner);
        s.ssthresh = intValue(ssthreshSpinner);
        s.messageRepeat = intValue(sizeSpinner);
        s.congestionControl = ccCheck.isSelected();
        return s;
    }

    private void startSession() {
        SessionSettings settings = readSettings();
        reset();
        runButton.setEnabled(false);
        resultLabel.setBackground(new Color(255, 248, 225));
        resultLabel.setText(resultPlaceholder("Dang chay..."));

        Thread worker = new Thread(() -> {
            try {
                runner.runSession(settings);
            } catch (Throwable ex) {
                SessionReport r = new SessionReport();
                r.success = false;
                r.failReason = "Loi khong mong doi: " + ex;
                EventBus.publishReport(r);
            }
        }, "MainThread");
        worker.setDaemon(true);
        worker.start();
    }

    /** Xoa sach man hinh cua phien truoc (chay tren EDT, ngay khi bam nut). */
    private void reset() {
        EventBus.clearAll();
        diagramPanel.clear();
        chartPanel.clear();
        tableModel.setRowCount(0);
        sentCount = retransCount = lostCount = ackCount = 0;
        lastSample = null;
        updateStatusBar();
        diagramPanel.repaint();
        chartPanel.repaint();
    }

    // =====================================================================
    // CAP NHAT GIAO DIEN DINH KY
    // =====================================================================
    private void refresh() {
        List<PacketEvent> newEvents = EventBus.drainAll();
        List<double[]> newSamples = EventBus.drainCwnd();

        for (PacketEvent event : newEvents) {
            diagramPanel.addEvent(event);
            countEvent(event);
            tableModel.insertRow(0, new Object[]{
                event.time.format(TIME_FORMAT),
                event.direction,
                event.packetType,
                event.seqNum,
                event.ackNum,
                statusText(event.status),
                event.note == null ? "" : event.note
            });
        }
        for (double[] sample : newSamples) {
            chartPanel.addSample(sample);
            lastSample = sample;
        }
        if (!newEvents.isEmpty() || !newSamples.isEmpty()) {
            updateStatusBar();
            diagramPanel.repaint();
            chartPanel.repaint();
        }

        SessionReport report = EventBus.pollReport();
        if (report != null) {
            showReport(report);
            runButton.setEnabled(true);
        }
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

    // =====================================================================
    // KHUNG KET QUA (BUOC 4)
    // =====================================================================
    private static String resultPlaceholder(String text) {
        return "<html>&nbsp;" + text + "<br>&nbsp;</html>";
    }

    private void showReport(SessionReport r) {
        String ok = "#1b7a1b";
        String bad = "#c62828";
        String line1;
        if (r.success) {
            line1 = "<b><font color='" + ok + "'>KET QUA: THANH CONG</font></b>";
        } else {
            line1 = "<b><font color='" + bad + "'>KET QUA: THAT BAI</font></b> - " + r.failReason;
        }

        if (r.success || !r.dataCheck.equals("KHONG_XAC_MINH")) {
            String check;
            switch (r.dataCheck) {
                case "DUNG":
                    check = "<b><font color='" + ok + "'>DUNG</font></b> (" + r.payloadBytes
                            + " byte, CRC32 khop voi Server)";
                    break;
                case "SAI":
                    check = "<b><font color='" + bad + "'>SAI</font></b>";
                    break;
                default:
                    check = "chua xac minh (Server khong gui bao cao)";
                    break;
            }
            line1 += "   |   Du lieu: " + check;
        }

        if (r.transferMs > 0) {
            double sec = r.transferMs / 1000.0;
            line1 += String.format("   |   Thoi gian: %.1f s (bat tay %.1f s + truyen %.1f s)",
                    (r.handshakeMs + r.transferMs) / 1000.0, r.handshakeMs / 1000.0, sec);
            if (r.success) {
                line1 += String.format("   |   Toc do: %.0f byte/s", r.payloadBytes / Math.max(sec, 0.001));
            }
        }

        double retransPct = r.dataTransmissions == 0 ? 0 : 100.0 * r.retransmissions / r.dataTransmissions;
        String line2 = String.format("Segment: %d   |   Tong luot gui: %d   |   Gui lai: %d (%.0f%%)   |   Mat goi gia lap: %d",
                r.segments, r.dataTransmissions, r.retransmissions, retransPct, r.lostPackets);
        if (r.ccEnabled) {
            line2 += String.format("   |   cwnd giam do timeout: %d lan   |   cwnd lon nhat: %.1f",
                    r.cwndReductions, r.maxCwnd);
        }

        resultLabel.setBackground(r.success ? new Color(232, 245, 233) : new Color(253, 236, 234));
        resultLabel.setText("<html>&nbsp;" + line1 + "<br>&nbsp;" + line2 + "</html>");
    }

    // =====================================================================
    // Tien ich
    // =====================================================================
    private static String phaseName(int type) {
        switch (type) {
            case 0: return "SLOW START";
            case 1: return "CONGESTION AVOIDANCE";
            case 2: return "TIMEOUT -> cwnd ve 1";
            default: return "?";
        }
    }

    private static String statusText(PacketEvent.Status status) {
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

        void clear() {
            events.clear();
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

        void clear() {
            samples.clear();
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
                g2.drawString("(neu tat dieu khien tac nghen thi khong co bieu do)", left + 10, h / 2 + 20);
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
