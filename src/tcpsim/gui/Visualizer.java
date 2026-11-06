package tcpsim.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import tcpsim.Client;

/**
 * Visualizer.java (giao dien ban 6 - gon gang hon)
 * ------------------------------------------------
 * Bo cuc:
 *   - COT TRAI:   CAU HINH (sua tham so, nut "Chay lai") va KET QUA (thong ke cuoi phien).
 *   - DAI TREN:   trang thai SONG: giai doan (slow start / congestion avoidance / timeout),
 *                 cwnd, ssthresh va bo dem goi tin.
 *   - O GIUA:     2 tab. Tab 1 "Truc quan hoa" = so do trinh tu + bieu do cwnd.
 *                 Tab 2 "Nhat ky goi tin" = bang log chi tiet.
 *
 * GUI khong biet gi ve TCP: khi bam "Chay lai" no chi goi SessionRunner.runSession(...)
 * tren mot thread rieng (do Client cung cap) va doi bao cao ket qua tu EventBus.
 */
public class Visualizer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Bang mau thong nhat
    private static final Color BLUE = new Color(33, 102, 214);
    private static final Color GREEN = new Color(46, 125, 50);
    private static final Color RED = new Color(198, 40, 40);
    private static final Color ORANGE = new Color(239, 130, 0);
    private static final Color PURPLE = new Color(123, 63, 160);
    private static final Color GRAY = new Color(130, 130, 130);
    private static final Color TEXT_MUTED = new Color(110, 110, 110);
    private static final Color PANEL_BG = new Color(246, 247, 249);
    private static final Color LINE = new Color(220, 223, 228);

    private final SessionRunner runner;

    private final JFrame frame;
    private final DiagramPanel diagramPanel;
    private final JScrollPane diagramScroll;
    private final ChartPanel chartPanel;
    private final DefaultTableModel tableModel;

    // ---- Cot cau hinh ----
    private final JTextField ipField;
    private final JSpinner lossSpinner;
    private final JSpinner corruptSpinner;
    private final JSpinner delayMinSpinner;
    private final JSpinner delayMaxSpinner;
    private final JSpinner windowSpinner;
    private final JSpinner ssthreshSpinner;
    private final JSpinner sizeSpinner;
    private final JSpinner timeWaitSpinner;
    private final JSpinner segmentSpinner;
    private final JComboBox<String> sourceCombo;
    private final JTextField messageField;
    private final JTextField fileField;
    private final JCheckBox ccCheck;
    private final JButton runButton;
    private final JButton stopButton;
    private final JButton openReceivedButton;

    // ---- Cot ket qua ----
    private final JLabel resultBadge;
    private final JLabel reasonLabel;
    private final JLabel receivedFileLabel;
    private final JLabel valData, valTime, valPhases, valSpeed, valSegments, valSends, valRetrans, valFast,
            valLost, valCorrupt, valCwndDrops, valCwndMax, valRto;

    // ---- Dai trang thai song ----
    private final JLabel phaseBadge;
    private final JLabel cwndLabel;
    private final JLabel ssthreshLabel;
    private final JLabel rwndLabel;
    private final JLabel countersLabel;
    private final JLabel progressLabel;

    // So lieu dem
    private int sentCount = 0;
    private int retransCount = 0;
    private int lostCount = 0;
    private int corruptCount = 0;
    private int ackCount = 0;
    private double[] lastSample = null;
    private String connectionState = "CLOSED";
    private int currentRwnd = 65535;

    private Visualizer(SessionSettings initial, SessionRunner runner) {
        this.runner = runner;

        frame = new JFrame("Mo phong TCP - Truc quan hoa goi tin (PBL4)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1260, 800);
        frame.setMinimumSize(new Dimension(1000, 640));
        frame.setLayout(new BorderLayout());

        // ================= Cac o nhap =================
        ipField = new JTextField(initial.serverIp);
        ipField.setName("ipField");
        lossSpinner = spinner("lossSpinner", initial.lossPercent, 0, 90, 5);
        corruptSpinner = spinner("corruptSpinner", initial.corruptPercent, 0, 100, 5);
        delayMinSpinner = spinner("delayMinSpinner", initial.delayMinMs, 0, 3000, 50);
        delayMaxSpinner = spinner("delayMaxSpinner", initial.delayMaxMs, 0, 3000, 50);
        windowSpinner = spinner("windowSpinner", initial.windowSize, 1, 64, 1);
        ssthreshSpinner = spinner("ssthreshSpinner", initial.ssthresh, 2, 64, 1);
        sizeSpinner = spinner("sizeSpinner", initial.messageRepeat, 1, 100, 1);
        segmentSpinner = spinner("segmentSpinner", initial.segmentSize, 8, 1024, 1);
        sourceCombo = new JComboBox<>(new String[]{SessionSettings.SAMPLE, SessionSettings.TEXT, SessionSettings.FILE});
        sourceCombo.setSelectedItem(initial.dataSource);
        messageField = new JTextField(initial.messageText);
        fileField = new JTextField(initial.filePath);
        JButton browseButton = new JButton("...");
        browseButton.addActionListener(e -> chooseFile());
        sourceCombo.addActionListener(e -> updateSourceFields());
        timeWaitSpinner = spinner("timeWaitSpinner", initial.timeWaitMs, 0, 30000, 100);
        ccCheck = new JCheckBox("Bat dieu khien tac nghen", initial.congestionControl);
        ccCheck.setName("ccCheck");
        ccCheck.setOpaque(false);
        runButton = new AccentButton("Chay lai");
        runButton.setName("runButton");
        runButton.addActionListener(e -> startSession());
        stopButton = new JButton("Dung");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopSession());

        sizeSpinner.setToolTipText("Moi don vi = 47 byte (1 cau mau). Vi du 8 -> 376 byte -> 47 segment.");
        delayMaxSpinner.setToolTipText("Neu nho hon tre min thi se duoc nang len bang tre min.");
        windowSpinner.setToolTipText("Gioi han tren cua cwnd (so segment toi da dang bay).");

        // ================= Cot ben trai =================
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(PANEL_BG);
        side.setBorder(new EmptyBorder(14, 16, 14, 14));
        side.setPreferredSize(new Dimension(290, 100));

        side.add(sectionTitle("CAU HINH"));
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        int r = 0;
        addFormRow(form, r++, "Server IP", ipField);
        addFormRow(form, r++, "Mat goi (%)", lossSpinner);
        addFormRow(form, r++, "Hong goi (%)", corruptSpinner);
        addFormRow(form, r++, "Tre min (ms)", delayMinSpinner);
        addFormRow(form, r++, "Tre max (ms)", delayMaxSpinner);
        addFormRow(form, r++, "Cua so toi da", windowSpinner);
        addFormRow(form, r++, "ssthresh", ssthreshSpinner);
        addFormRow(form, r++, "Du lieu (x47 byte)", sizeSpinner);
        addFormRow(form, r++, "Nguon du lieu", sourceCombo);
        addFormRow(form, r++, "Tin nhan", messageField);
        JPanel fileChooser = new JPanel(new BorderLayout(4, 0));
        fileChooser.add(fileField, BorderLayout.CENTER);
        fileChooser.add(browseButton, BorderLayout.EAST);
        addFormRow(form, r++, "File (<=200 KB)", fileChooser);
        addFormRow(form, r++, "Segment (byte)", segmentSpinner);
        addFormRow(form, r++, "TIME_WAIT (ms)", timeWaitSpinner);
        left(form);
        side.add(form);
        side.add(Box.createVerticalStrut(4));
        left(ccCheck);
        side.add(ccCheck);
        side.add(Box.createVerticalStrut(10));
        left(runButton);
        runButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        side.add(runButton);
        stopButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        side.add(stopButton);
        openReceivedButton = new JButton("Mo thu muc received");
        openReceivedButton.setEnabled(false);
        openReceivedButton.addActionListener(e -> openReceivedFolder());
        left(openReceivedButton);
        side.add(Box.createVerticalStrut(5));
        side.add(openReceivedButton);
        updateReceivedButtonAvailability(initial.serverIp);
        updateSourceFields();

        side.add(Box.createVerticalStrut(20));
        side.add(sectionTitle("KET QUA"));

        resultBadge = new JLabel("SAN SANG");
        resultBadge.setName("resultBadge");
        resultBadge.setOpaque(true);
        resultBadge.setHorizontalAlignment(SwingConstants.CENTER);
        resultBadge.setFont(resultBadge.getFont().deriveFont(Font.BOLD, 15f));
        resultBadge.setForeground(Color.WHITE);
        resultBadge.setBackground(GRAY);
        resultBadge.setBorder(new EmptyBorder(7, 8, 7, 8));
        left(resultBadge);
        resultBadge.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        side.add(resultBadge);

        reasonLabel = new JLabel(" ");
        reasonLabel.setName("reasonLabel");
        reasonLabel.setFont(reasonLabel.getFont().deriveFont(Font.PLAIN, 12f));
        reasonLabel.setForeground(RED);
        reasonLabel.setBorder(new EmptyBorder(4, 2, 2, 2));
        reasonLabel.setVisible(false);
        left(reasonLabel);
        side.add(reasonLabel);

        JPanel metrics = new JPanel(new GridBagLayout());
        metrics.setOpaque(false);
        int m = 0;
        valData = metricRow(metrics, m++, "Du lieu", "valData");
        receivedFileLabel = metricRow(metrics, m++, "File luu tren may Server", "receivedFileLabel");
        valTime = metricRow(metrics, m++, "Tong thoi gian", "valTime");
        valPhases = metricRow(metrics, m++, "Bat tay + truyen", "valPhases");
        valSpeed = metricRow(metrics, m++, "Toc do", "valSpeed");
        valSegments = metricRow(metrics, m++, "So segment", "valSegments");
        valSends = metricRow(metrics, m++, "Tong luot gui", "valSends");
        valRetrans = metricRow(metrics, m++, "Gui lai", "valRetrans");
        valFast = metricRow(metrics, m++, "Fast retransmit", "valFast");
        valLost = metricRow(metrics, m++, "Mat goi gia lap", "valLost");
        valCorrupt = metricRow(metrics, m++, "Goi bi hong", "valCorrupt");
        valCwndDrops = metricRow(metrics, m++, "cwnd giam (timeout)", "valCwndDrops");
        valCwndMax = metricRow(metrics, m++, "cwnd lon nhat", "valCwndMax");
        valRto = metricRow(metrics, m++, "RTO cuoi / SRTT", "valRto");
        metrics.setBorder(new EmptyBorder(8, 0, 0, 0));
        left(metrics);
        side.add(metrics);
        side.add(Box.createVerticalGlue());

        frame.add(side, BorderLayout.WEST);

        // ================= Dai trang thai song (phia tren) =================
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 9));
        strip.setBackground(Color.WHITE);
        strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));
        phaseBadge = new JLabel("CHUA CHAY");
        phaseBadge.setOpaque(true);
        phaseBadge.setForeground(Color.WHITE);
        phaseBadge.setBackground(GRAY);
        phaseBadge.setFont(phaseBadge.getFont().deriveFont(Font.BOLD, 12f));
        phaseBadge.setBorder(new EmptyBorder(4, 10, 4, 10));
        cwndLabel = new JLabel();
        ssthreshLabel = new JLabel();
        rwndLabel = new JLabel();
        countersLabel = new JLabel();
        progressLabel = new JLabel("Tien do: 0% | 0 byte/s");
        countersLabel.setForeground(TEXT_MUTED);
        strip.add(phaseBadge);
        strip.add(cwndLabel);
        strip.add(ssthreshLabel);
        strip.add(rwndLabel);
        strip.add(Box.createHorizontalStrut(30));
        strip.add(countersLabel);
        strip.add(progressLabel);
        updateStatusBar();

        // ================= Phan giua: 2 tab =================
        diagramPanel = new DiagramPanel();
        diagramScroll = new JScrollPane(diagramPanel);
        diagramScroll.setBorder(null);
        diagramScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        diagramScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        diagramScroll.getViewport().setBackground(Color.WHITE);
        diagramScroll.getVerticalScrollBar().setUnitIncrement(DiagramPanel.ROW_HEIGHT);
        chartPanel = new ChartPanel();
        chartPanel.setWindowLimit(intValue(windowSpinner));

        JPanel visual = new JPanel(new GridBagLayout());
        visual.setBackground(Color.WHITE);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH;
        gc.weighty = 1;
        gc.gridy = 0;
        gc.gridx = 0;
        gc.weightx = 0.42;
        visual.add(diagramScroll, gc);
        gc.gridx = 1;
        gc.weightx = 0.58;
        visual.add(chartPanel, gc);

        String[] columns = {"Thoi gian", "Chieu", "Loai goi", "Seq", "Ack", "Trang thai", "Ghi chu"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        JScrollPane logScroll = new JScrollPane(table);
        logScroll.setBorder(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Truc quan hoa", visual);
        tabs.addTab("Nhat ky goi tin", logScroll);

        JPanel centerWrap = new JPanel(new BorderLayout());
        centerWrap.add(strip, BorderLayout.NORTH);
        centerWrap.add(tabs, BorderLayout.CENTER);
        frame.add(centerWrap, BorderLayout.CENTER);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Timer refreshTimer = new Timer(150, e -> refresh());
        refreshTimer.start();

        // Phien dau tien chay ngay khi cua so hien (giong hanh vi cu: mo Client la chay).
        startSession();
    }

    // =====================================================================
    // Dung giao dien
    // =====================================================================
    private static JSpinner spinner(String name, int value, int min, int max, int step) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
        sp.setName(name);
        return sp;
    }

    private static void left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(TEXT_MUTED);
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(0, 0, 6, 0)));
        left(l);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return l;
    }

    private static void addFormRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(5, 0, 5, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setForeground(new Color(60, 60, 60));
        form.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 0, 5, 0);
        form.add(field, c);
    }

    private static JLabel metricRow(JPanel panel, int row, String label, String valueName) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 10);
        c.gridx = 0;
        JLabel l = new JLabel(label);
        l.setForeground(TEXT_MUTED);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
        panel.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        JLabel v = new JLabel("-");
        v.setName(valueName);
        v.setFont(v.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(v, c);
        return v;
    }

    /** Nut chinh mau xanh, bo goc (tu ve de khong phu thuoc kieu giao dien he thong). */
    private static class AccentButton extends JButton {
        AccentButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 14f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? new Color(176, 180, 186)
                    : getModel().isPressed() ? BLUE.darker() : BLUE;
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.setColor(Color.WHITE);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            String t = getText();
            g2.drawString(t, (getWidth() - fm.stringWidth(t)) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Mo cua so truc quan hoa (goi tu bat ky thread nao). Phien dau tien tu chay ngay. */
    public static void launch(SessionSettings initial, SessionRunner runner) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Nimbus: hien dai hon kieu Swing mac dinh va nhu nhau tren moi may.
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Khong doi duoc kieu giao dien thi dung kieu mac dinh, khong sao ca.
            }
            new Visualizer(initial, runner);
        });
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
        s.serverIp = ipField.getText().trim().isEmpty() ? "auto" : ipField.getText().trim();
        s.lossPercent = intValue(lossSpinner);
        s.corruptPercent = intValue(corruptSpinner);
        s.delayMinMs = intValue(delayMinSpinner);
        s.delayMaxMs = intValue(delayMaxSpinner);
        s.windowSize = intValue(windowSpinner);
        s.ssthresh = intValue(ssthreshSpinner);
        s.messageRepeat = intValue(sizeSpinner);
        s.dataSource = (String) sourceCombo.getSelectedItem();
        s.messageText = messageField.getText();
        s.filePath = fileField.getText().trim();
        s.segmentSize = intValue(segmentSpinner);
        s.timeWaitMs = intValue(timeWaitSpinner);
        s.congestionControl = ccCheck.isSelected();
        updateReceivedButtonAvailability(s.serverIp);
        return s;
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) fileField.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void updateSourceFields() {
        boolean sample = SessionSettings.SAMPLE.equals(sourceCombo.getSelectedItem());
        boolean text = SessionSettings.TEXT.equals(sourceCombo.getSelectedItem());
        sizeSpinner.setEnabled(sample);
        messageField.setEnabled(text);
        fileField.setEnabled(!sample && !text);
        segmentSpinner.setValue(sample ? 8 : 1000);
        segmentSpinner.setToolTipText(sample ? "Cau mau dung 8 byte" : "8..1000 byte (header UDP 15 byte)");
    }

    private void openReceivedFolder() {
        try {
            Files.createDirectories(Paths.get("received"));
            Desktop.getDesktop().open(new File("received"));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame,
                    "Khong mo duoc thu muc received tren may Client. " + e.getMessage(),
                    "Khong mo duoc thu muc", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateReceivedButtonAvailability(String serverIp) {
        boolean local = isLocalServerAddress(serverIp);
        openReceivedButton.setEnabled(local);
        openReceivedButton.setToolTipText(local ? "Mo received tren may nay"
                : "Server chay tren may khac: mo thu muc received tren may Server");
    }

    private boolean isLocalServerAddress(String configured) {
        if (configured == null || configured.trim().isEmpty() || "auto".equalsIgnoreCase(configured.trim())) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(configured.trim())) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return true;
                NetworkInterface network = NetworkInterface.getByInetAddress(address);
                if (network != null) return true;
            }
        } catch (Exception ignored) {
            // Khong xac dinh duoc -> coi la server may khac de tranh mo nham thu muc.
        }
        return false;
    }

    private void startSession() {
        SessionSettings settings = readSettings();
        chartPanel.setWindowLimit(settings.windowSize);
        reset();
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        resultBadge.setText("DANG CHAY...");
        resultBadge.setBackground(ORANGE);
        reasonLabel.setText(" ");
        reasonLabel.setVisible(false);
        clearMetrics();

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

    private void stopSession() {
        Client.cancelSession();
        stopButton.setEnabled(false);
        reasonLabel.setText("Nguoi dung huy phien");
        reasonLabel.setVisible(true);
    }

    /** Xoa sach man hinh cua phien truoc (chay tren EDT, ngay khi bam nut). */
    private void reset() {
        EventBus.clearAll();
        diagramPanel.clear();
        chartPanel.clear();
        tableModel.setRowCount(0);
        sentCount = retransCount = lostCount = ackCount = 0;
        corruptCount = 0;
        lastSample = null;
        connectionState = "CLOSED";
        currentRwnd = 65535;
        updateStatusBar();
        diagramPanel.repaint();
        chartPanel.repaint();
    }

    private boolean diagramIsAtBottom() {
        JScrollBar bar = diagramScroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - DiagramPanel.ROW_HEIGHT;
    }

    private void scrollDiagramToBottom() {
        JScrollBar bar = diagramScroll.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }

    // =====================================================================
    // CAP NHAT GIAO DIEN DINH KY
    // =====================================================================
    private void refresh() {
        List<PacketEvent> newEvents = EventBus.drainAll();
        List<double[]> newSamples = EventBus.drainCwnd();
        List<double[]> newRtoSamples = EventBus.drainRto();
        List<double[]> newRwndSamples = EventBus.drainRwnd();
        double[] progress;
        boolean followDiagram = diagramIsAtBottom();

        for (PacketEvent event : newEvents) {
            if ("STATE".equals(event.packetType) && event.note != null
                    && event.direction.startsWith("Client")) {
                int arrow = event.note.lastIndexOf(" -> ");
                connectionState = arrow >= 0 ? event.note.substring(arrow + 4) : event.note;
            }
            if ("WARNING".equals(event.packetType)) {
                reasonLabel.setText("<html><div style='width:230px'>" + event.note + "</div></html>");
                reasonLabel.setVisible(true);
            }
            if (!"STATE".equals(event.packetType)) {
                diagramPanel.addEvent(event);
            }
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
            while (tableModel.getRowCount() > 2000) tableModel.removeRow(tableModel.getRowCount() - 1);
        }
        for (double[] sample : newSamples) {
            chartPanel.addSample(sample);
            lastSample = sample;
        }
        for (double[] sample : newRtoSamples) chartPanel.addRtoSample(sample);
        for (double[] sample : newRwndSamples) {
            chartPanel.addRwndSample(sample);
            currentRwnd = (int) sample[1];
        }
        while ((progress = EventBus.pollProgress()) != null) {
            double pct = progress[1] == 0 ? 100 : 100.0 * progress[0] / progress[1];
            double speed = progress[2] <= 0 ? 0 : progress[0] * 1000.0 / progress[2];
            progressLabel.setText(String.format("Tien do: %.0f%% | %.0f byte/s", pct, speed));
        }
        if (!newEvents.isEmpty() || !newSamples.isEmpty() || !newRtoSamples.isEmpty() || !newRwndSamples.isEmpty()) {
            updateStatusBar();
            diagramPanel.revalidate();
            diagramPanel.repaint();
            chartPanel.repaint();
            if (!newEvents.isEmpty() && followDiagram) {
                SwingUtilities.invokeLater(this::scrollDiagramToBottom);
            }
        }

        SessionReport report = EventBus.pollReport();
        if (report != null) {
            showReport(report);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
        updateReceivedButtonAvailability(ipField.getText().trim());
        }
    }

    private void countEvent(PacketEvent event) {
        // Dem cung mot y nghia voi SessionReport: chi dem cac segment DATA
        // do Client gui lai, ke ca lan gui lai do tiep tuc bi mat/hong.
        if (event.retransmissionAttempt
                && event.direction.startsWith("Client")
                && "DATA".equals(event.packetType)) {
            retransCount++;
        }
        switch (event.status) {
            case SENT:
                if (event.direction.startsWith("Client")) sentCount++;
                break;
            case RETRANSMITTED:
                break;
            case LOST:
                lostCount++;
                break;
            case CORRUPTED:
                corruptCount++;
                break;
            case ACKED:
                if (event.packetType.equals("ACK")) ackCount++;
                break;
            default:
                break;
        }
    }

    private void updateStatusBar() {
        phaseBadge.setText("STATE: " + connectionState);
        phaseBadge.setBackground(stateColor(connectionState));
        if (lastSample == null) {
            cwndLabel.setText("");
            ssthreshLabel.setText("");
        } else {
            int type = (int) lastSample[3];
            cwndLabel.setText(String.format("<html>cwnd <b>%.2f</b> (%s)</html>", lastSample[1], phaseName(type)));
            ssthreshLabel.setText(String.format("<html>ssthresh <b>%.0f</b></html>", lastSample[2]));
        }
        rwndLabel.setText(String.format("<html>rwnd <b>%d</b> byte</html>", currentRwnd));
        countersLabel.setText("Da gui " + sentCount + "     Gui lai " + retransCount
                + "     Mat goi " + lostCount + "     Hong " + corruptCount
                + "     ACK nhan " + ackCount);
    }

    private static Color stateColor(String state) {
        if ("ESTABLISHED".equals(state)) return GREEN;
        if (state.startsWith("FIN_WAIT") || "TIME_WAIT".equals(state)) return ORANGE;
        if ("SYN_SENT".equals(state)) return BLUE;
        return GRAY;
    }

    // =====================================================================
    // CAT KET QUA (BUOC 4)
    // =====================================================================
    private void clearMetrics() {
        for (JLabel v : new JLabel[]{valData, receivedFileLabel, valTime, valPhases, valSpeed, valSegments, valSends,
                valRetrans, valFast, valLost, valCorrupt, valCwndDrops, valCwndMax, valRto}) {
            v.setText("-");
            v.setForeground(Color.BLACK);
        }
    }

    private void showReport(SessionReport r) {
        clearMetrics();
        resultBadge.setText(r.success ? "THANH CONG" : "THAT BAI");
        resultBadge.setBackground(r.success ? GREEN : RED);
        reasonLabel.setText(r.success ? " "
                : "<html><div style='width:230px'>" + r.failReason + "</div></html>");
        reasonLabel.setVisible(!r.success);

        switch (r.dataCheck) {
            case "DUNG":
                valData.setText("DUNG (khop)");
                valData.setToolTipText("Server rap duoc dung do dai va CRC32 cua du lieu da gui");
                valData.setForeground(GREEN);
                break;
            case "SAI":
                valData.setText("SAI");
                valData.setForeground(RED);
                break;
            default:
                valData.setText("chua xac minh");
                valData.setForeground(TEXT_MUTED);
                break;
        }
        receivedFileLabel.setText(r.receivedFilePath.isEmpty() ? "khong ro" : r.receivedFilePath);
        receivedFileLabel.setToolTipText(r.receivedFilePath.isEmpty()
                ? "Server cu co the khong gui ten file" : r.receivedFilePath);

        if (r.transferMs > 0) {
            double hs = r.handshakeMs / 1000.0;
            double tr = r.transferMs / 1000.0;
            valTime.setText(String.format("%.1f s", hs + tr));
            valPhases.setText(String.format("%.1f + %.1f s", hs, tr));
            if (r.success) {
                valSpeed.setText(String.format("%.0f byte/s", r.payloadBytes / Math.max(tr, 0.001)));
            }
        }

        double retransPct = r.dataTransmissions == 0 ? 0 : 100.0 * r.retransmissions / r.dataTransmissions;
        valSegments.setText(r.segments + "  (" + r.payloadBytes + " B)");
        valSends.setText(String.valueOf(r.dataTransmissions));
        valRetrans.setText(String.format("%d  (%.0f%%)", r.retransmissions, retransPct));
        valFast.setText(r.fastRetransmits + " lan");
        valLost.setText(String.valueOf(r.lostPackets));
        valCorrupt.setText(String.valueOf(r.corruptedPackets));
        valRto.setText(String.format("%.0f / %.0f ms", r.finalRtoMs, r.finalSrttMs));
        if (r.ccEnabled) {
            valCwndDrops.setText(r.cwndReductions + " lan");
            valCwndMax.setText(String.format("%.1f", r.maxCwnd));
        } else {
            valCwndDrops.setText("tat");
            valCwndMax.setText("tat");
        }
    }

    // =====================================================================
    // Tien ich
    // =====================================================================
    private static String phaseName(int type) {
        switch (type) {
            case 0: return "SLOW START";
            case 1: return "CONGESTION AVOIDANCE";
            case 2: return "TIMEOUT - cwnd ve 1";
            case 3: return "FAST RETRANSMIT - cwnd giam 1/2";
            default: return "?";
        }
    }

    private static String statusText(PacketEvent.Status status) {
        switch (status) {
            case SENT: return "Da gui";
            case ACKED: return "Da xac nhan (ACK)";
            case LOST: return "MAT GOI";
            case CORRUPTED: return "GOI BI HONG";
            case RETRANSMITTED: return "GUI LAI";
            default: return status.toString();
        }
    }

    private static Color colorFor(PacketEvent.Status status) {
        switch (status) {
            case SENT: return new Color(110, 118, 130);
            case ACKED: return GREEN;
            case LOST: return RED;
            case CORRUPTED: return PURPLE;
            case RETRANSMITTED: return ORANGE;
            default: return Color.BLACK;
        }
    }

    // =====================================================================
    // SO DO TRINH TU
    // =====================================================================
    private static class DiagramPanel extends JPanel {
        private static final int ROW_HEIGHT = 30;
        private final List<PacketEvent> events = new ArrayList<>();

        DiagramPanel() {
            setBackground(Color.WHITE);
            setBorder(new EmptyBorder(0, 0, 0, 0));
        }

        void addEvent(PacketEvent event) {
            events.add(event);
            if (events.size() > 2000) events.remove(0);
        }

        void clear() {
            events.clear();
            revalidate();
        }

        @Override
        public Dimension getPreferredSize() {
            int rows = Math.max(1, events.size());
            int height = 100 + rows * ROW_HEIGHT;
            return new Dimension(Math.max(420, super.getPreferredSize().width), height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int leftX = 70;
            int rightX = width - 70;
            int topY = 52;
            int bottomY = height - 40;

            // Tieu de hai dau
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
            g2.setColor(new Color(40, 40, 40));
            centerText(g2, "CLIENT", leftX, 28);
            centerText(g2, "SERVER", rightX, 28);

            g2.setColor(new Color(190, 195, 202));
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(leftX, topY - 8, leftX, bottomY);
            g2.drawLine(rightX, topY - 8, rightX, bottomY);

            drawLegend(g2, height, width);

            if (events.isEmpty()) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
                g2.setColor(GRAY);
                centerText(g2, "Dang cho du lieu...", width / 2, height / 2);
                return;
            }

            int y = topY + 14;
            for (PacketEvent event : events) {
                boolean clientToServer = event.direction.startsWith("Client");
                int fromX = clientToServer ? leftX : rightX;
                int toX = clientToServer ? rightX : leftX;
                Color color = colorFor(event.status);
                boolean lost = event.status == PacketEvent.Status.LOST;

                // Goi bi mat: ve net dut va dung giua duong, danh dau X (khong cham toi dich)
                int endX = lost ? fromX + (int) ((toX - fromX) * 0.58) : toX;

                g2.setColor(color);
                if (lost) {
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                            new float[]{6f, 5f}, 0f));
                    g2.drawLine(fromX, y, endX, y);
                    g2.setStroke(new BasicStroke(2.6f));
                    g2.drawLine(endX - 6, y - 6, endX + 6, y + 6);
                    g2.drawLine(endX - 6, y + 6, endX + 6, y - 6);
                } else {
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawLine(fromX, y, endX, y);
                    drawArrowHead(g2, fromX, endX, y);
                }

                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
                String label = labelFor(event);
                int labelWidth = g2.getFontMetrics().stringWidth(label);
                int labelX = (Math.min(fromX, endX) + Math.max(fromX, endX)) / 2 - labelWidth / 2;
                g2.drawString(label, labelX, y - 7);

                y += ROW_HEIGHT;
            }
        }

        private static String labelFor(PacketEvent e) {
            StringBuilder sb = new StringBuilder(e.packetType);
            if (e.seqNum != 0) sb.append("  seq=").append(e.seqNum);
            if (e.ackNum != 0) sb.append("  ack=").append(e.ackNum);
            if (e.status == PacketEvent.Status.LOST) sb.append("  (mat)");
            if (e.status == PacketEvent.Status.RETRANSMITTED) sb.append("  (gui lai)");
            return sb.toString();
        }

        private void centerText(Graphics2D g2, String text, int cx, int y) {
            g2.drawString(text, cx - g2.getFontMetrics().stringWidth(text) / 2, y);
        }

        private void drawLegend(Graphics2D g2, int height, int width) {
            String[] names = {"Da gui", "ACK", "Mat goi", "Hong", "Gui lai"};
            PacketEvent.Status[] statuses = {PacketEvent.Status.SENT, PacketEvent.Status.ACKED,
                    PacketEvent.Status.LOST, PacketEvent.Status.CORRUPTED, PacketEvent.Status.RETRANSMITTED};
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
            FontMetrics fm = g2.getFontMetrics();
            int total = 0;
            for (String n : names) total += 22 + fm.stringWidth(n) + 16;
            int x = (width - total) / 2;
            int y = height - 14;
            for (int i = 0; i < names.length; i++) {
                g2.setColor(colorFor(statuses[i]));
                g2.fillRoundRect(x, y - 9, 12, 12, 4, 4);
                g2.setColor(new Color(70, 70, 70));
                g2.drawString(names[i], x + 18, y + 1);
                x += 22 + fm.stringWidth(names[i]) + 16;
            }
        }

        private void drawArrowHead(Graphics2D g2, int fromX, int toX, int y) {
            int direction = (toX > fromX) ? -1 : 1;
            int size = 8;
            int[] xs = {toX, toX + direction * size, toX + direction * size};
            int[] ys = {y, y - size / 2 - 1, y + size / 2 + 1};
            g2.fillPolygon(xs, ys, 3);
        }
    }

    // =====================================================================
    // BIEU DO cwnd
    // =====================================================================
    private static class ChartPanel extends JPanel {
        private final List<double[]> samples = new ArrayList<>();
        private final List<double[]> rtoSamples = new ArrayList<>();
        private final List<double[]> rwndSamples = new ArrayList<>();
        private int windowLimit = 32;

        ChartPanel() {
            setBackground(Color.WHITE);
        }

        void addSample(double[] sample) {
            samples.add(sample);
            if (samples.size() > 2000) samples.remove(0);
        }

        void addRtoSample(double[] sample) {
            rtoSamples.add(sample);
            if (rtoSamples.size() > 2000) rtoSamples.remove(0);
        }

        void addRwndSample(double[] sample) {
            rwndSamples.add(sample);
            if (rwndSamples.size() > 2000) rwndSamples.remove(0);
        }

        void clear() {
            samples.clear();
            rtoSamples.clear();
            rwndSamples.clear();
        }

        void setWindowLimit(int limit) {
            windowLimit = Math.max(1, limit);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int left = 52, right = 24, top = 56, bottom = 52;
            int pw = w - left - right;
            int ph = h - top - bottom;

            g2.setColor(new Color(40, 40, 40));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
            g2.drawString("cwnd, rwnd va RTO (rwnd theo segment, gioi han cua so)", left, 28);

            if (samples.size() < 2 && rtoSamples.size() < 2 && rwndSamples.size() < 2) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
                g2.setColor(GRAY);
                g2.drawString("Chua co du lieu cwnd", left + 10, h / 2);
                g2.drawString("(neu tat dieu khien tac nghen thi khong co bieu do)", left + 10, h / 2 + 22);
                return;
            }

            double maxT = 1;
            if (!samples.isEmpty()) maxT = Math.max(maxT, samples.get(samples.size() - 1)[0]);
            if (!rtoSamples.isEmpty()) maxT = Math.max(maxT, rtoSamples.get(rtoSamples.size() - 1)[0]);
            if (!rwndSamples.isEmpty()) maxT = Math.max(maxT, rwndSamples.get(rwndSamples.size() - 1)[0]);
            double maxY = 4;
            for (double[] s : samples) {
                maxY = Math.max(maxY, Math.max(s[1], s[2]));
            }
            for (double[] s : rtoSamples) {
                maxY = Math.max(maxY, Math.max(s[1], s[2]) / 100.0);
            }
            maxY = Math.ceil(maxY + 1);

            // Luoi ngang + nhan truc Y
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
            int yTicks = 5;
            for (int i = 0; i <= yTicks; i++) {
                int y = top + ph - (ph * i) / yTicks;
                g2.setColor(new Color(232, 234, 238));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(left, y, left + pw, y);
                g2.setColor(TEXT_MUTED);
                String t = String.format("%.0f", maxY * i / yTicks);
                g2.drawString(t, left - 10 - g2.getFontMetrics().stringWidth(t), y + 4);
            }
            // Nhan truc X (giay)
            for (int i = 0; i <= 5; i++) {
                int x = left + (pw * i) / 5;
                g2.setColor(TEXT_MUTED);
                String t = String.format("%.1fs", maxT * i / 5 / 1000.0);
                g2.drawString(t, x - g2.getFontMetrics().stringWidth(t) / 2, top + ph + 20);
            }
            g2.setColor(new Color(160, 165, 172));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(left, top + ph, left + pw, top + ph);

            // Phan to mau nhat duoi duong cwnd
            if (!samples.isEmpty()) {
                Path2D.Double area = new Path2D.Double();
                area.moveTo(px(samples.get(0)[0], maxT, left, pw), top + ph);
                for (double[] s : samples) area.lineTo(px(s[0], maxT, left, pw), py(s[1], maxY, top, ph));
                area.lineTo(px(samples.get(samples.size() - 1)[0], maxT, left, pw), top + ph);
                area.closePath();
                g2.setColor(new Color(33, 102, 214, 28));
                g2.fill(area);
            }

            // ssthresh: duong bac thang net dut mau cam
            g2.setColor(ORANGE);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{7f, 5f}, 0f));
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
            g2.setColor(BLUE);
            g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < samples.size(); i++) {
                double[] a = samples.get(i - 1);
                double[] b = samples.get(i);
                g2.drawLine(px(a[0], maxT, left, pw), py(a[1], maxY, top, ph),
                        px(b[0], maxT, left, pw), py(b[1], maxY, top, ph));
            }

            // RTO/SRTT: ve tren cung truc thoi gian, chia 100 de dat cung he truc voi cwnd.
            drawRtoLine(g2, rtoSamples, 1, BLUE, maxT, maxY, left, pw, top, ph);
            drawRtoLine(g2, rtoSamples, 2, PURPLE, maxT, maxY, left, pw, top, ph);
            drawRwndLine(g2, rwndSamples, maxT, maxY, left, pw, top, ph, windowLimit);

            // Cham do tai moi lan timeout, cham cam tai moi lan fast retransmit
            for (double[] s : samples) {
                int type = (int) s[3];
                if (type == 2 || type == 3) {
                    int x = px(s[0], maxT, left, pw);
                    int y = py(s[1], maxY, top, ph);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(x - 7, y - 7, 14, 14);
                    g2.setColor(type == 2 ? RED : ORANGE);
                    g2.fillOval(x - 5, y - 5, 10, 10);
                }
            }

            drawLegend(g2, left, h - 14);
        }

        private void drawLegend(Graphics2D g2, int x, int y) {
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
            g2.setColor(BLUE);
            g2.setStroke(new BasicStroke(2.6f));
            g2.drawLine(x, y - 4, x + 20, y - 4);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("cwnd", x + 26, y);
            x += 90;
            g2.setColor(ORANGE);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{6f, 4f}, 0f));
            g2.drawLine(x, y - 4, x + 20, y - 4);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("ssthresh", x + 26, y);
            x += 110;
            g2.setColor(RED);
            g2.fillOval(x, y - 10, 10, 10);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("timeout", x + 16, y);
            x += 80;
            g2.setColor(ORANGE);
            g2.fillOval(x, y - 10, 10, 10);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("fast retransmit", x + 16, y);
            x += 105;
            g2.setColor(BLUE);
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawLine(x, y - 4, x + 20, y - 4);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("SRTT", x + 26, y);
            x += 70;
            g2.setColor(PURPLE);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{5f, 4f}, 0f));
            g2.drawLine(x, y - 4, x + 20, y - 4);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("RTO", x + 26, y);
            x += 70;
            g2.setColor(GREEN);
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawLine(x, y - 4, x + 20, y - 4);
            g2.setColor(new Color(70, 70, 70));
            g2.drawString("rwnd (segment)", x + 26, y);
        }

        private static void drawRwndLine(Graphics2D g2, List<double[]> values,
                                         double maxT, double maxY, int left, int pw, int top, int ph,
                                         int windowLimit) {
            g2.setColor(GREEN);
            g2.setStroke(new BasicStroke(2f));
            for (int i = 1; i < values.size(); i++) {
                double[] a = values.get(i - 1), b = values.get(i);
                double av = Math.min(windowLimit, a[1] / 8.0);
                double bv = Math.min(windowLimit, b[1] / 8.0);
                g2.drawLine(px(a[0], maxT, left, pw), py(av, maxY, top, ph),
                        px(b[0], maxT, left, pw), py(bv, maxY, top, ph));
            }
        }

        private static void drawRtoLine(Graphics2D g2, List<double[]> values, int index, Color color,
                                        double maxT, double maxY, int left, int pw, int top, int ph) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < values.size(); i++) {
                double[] a = values.get(i - 1), b = values.get(i);
                g2.drawLine(px(a[0], maxT, left, pw), py(a[index] / 100.0, maxY, top, ph),
                        px(b[0], maxT, left, pw), py(b[index] / 100.0, maxY, top, ph));
            }
        }

        private static int px(double t, double maxT, int left, int pw) {
            return left + (int) Math.round(pw * (t / maxT));
        }

        private static int py(double v, double maxY, int top, int ph) {
            return top + ph - (int) Math.round(ph * (v / maxY));
        }
    }
}
