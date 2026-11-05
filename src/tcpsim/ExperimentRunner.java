package tcpsim;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import tcpsim.gui.EventBus;
import tcpsim.gui.SessionReport;
import tcpsim.gui.SessionSettings;

/** CLI batch runner: 5 loss rates x 3 windows x 2 CC modes x 3 trials. */
public final class ExperimentRunner {
    private static final int[] LOSS_RATES = {0, 5, 10, 20, 30};
    private static final int[] WINDOWS = {1, 4, 32};
    private static final int TRIALS = 3;
    private static final int DEFAULT_MESSAGE_REPEAT = 8;
    private static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 90;

    private ExperimentRunner() { }

    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> config = Client.loadConfig("client-config.txt");
        String serverIp = args.length > 0 ? args[0] : config.getOrDefault("server_ip", Client.AUTO_SERVER);
        int messageRepeat = args.length > 1 ? Integer.parseInt(args[1])
                : Integer.parseInt(config.getOrDefault("message_repeat", String.valueOf(DEFAULT_MESSAGE_REPEAT)));
        int delayMinMs = Integer.parseInt(config.getOrDefault("delay_min_ms", "0"));
        int delayMaxMs = Integer.parseInt(config.getOrDefault("delay_max_ms", "0"));
        int corruptPercent = Integer.parseInt(config.getOrDefault("corrupt_percent", "0"));
        int ssthresh = Integer.parseInt(config.getOrDefault("ssthresh", "16"));
        Path csv = Paths.get(args.length > 2 ? args[2] : "test-results/ket_qua.csv");
        int sessionTimeoutSeconds = args.length > 3
                ? Integer.parseInt(args[3]) : DEFAULT_SESSION_TIMEOUT_SECONDS;
        // Optional filters keep the original 90-run CLI intact while allowing
        // a quick smoke run: <loss> <window> <trials> <cc:on|off>.
        int[] lossRates = args.length > 4 ? new int[]{Integer.parseInt(args[4])} : LOSS_RATES;
        int[] windows = args.length > 5 ? new int[]{Integer.parseInt(args[5])} : WINDOWS;
        int trials = args.length > 6 ? Integer.parseInt(args[6]) : TRIALS;
        boolean[] ccModes = args.length > 7
                ? new boolean[]{!args[7].equalsIgnoreCase("off")}
                : new boolean[]{true, false};
        Path parent = csv.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            out.write("loss_percent,window_size,congestion_control,trials,transfer_ms,segments,retransmissions,fast_retransmits,timeouts,max_cwnd,throughput_bytes_per_sec,data_check,success_rate");
            out.newLine();
            out.flush();
            int completed = 0;
            int totalRuns = lossRates.length * windows.length * ccModes.length * trials;
            for (int loss : lossRates) {
                for (int window : windows) {
                    for (boolean cc : ccModes) {
                        List<SessionReport> reports = new ArrayList<>();
                        for (int trial = 1; trial <= trials; trial++) {
                            System.out.printf("[%d/%d] Bat dau loss=%d window=%d cc=%s trial=%d%n",
                                    completed + 1, totalRuns, loss, window, cc, trial);
                            System.out.flush();
                            SessionSettings settings = settings(serverIp, loss, window, cc, messageRepeat,
                                    delayMinMs, delayMaxMs, corruptPercent, ssthresh);
                            EventBus.clearAll();
                            SessionReport report = runSessionWithTimeout(settings, sessionTimeoutSeconds);
                            if (report == null) throw new IllegalStateException("Khong nhan duoc SessionReport");
                            reports.add(report);
                            completed++;
                            System.out.printf("loss=%d window=%d cc=%s trial=%d transfer=%dms retrans=%d timeout=%d%n",
                                    loss, window, cc, trial, report.transferMs,
                                    report.retransmissions, report.timeouts);
                        }
                        writeAverage(out, loss, window, cc, reports);
                        out.flush();
                    }
                }
            }
        }
        System.out.println("Da ghi ket qua vao " + csv.toAbsolutePath());
    }

    private static SessionReport runSessionWithTimeout(SessionSettings settings, int timeoutSeconds)
            throws InterruptedException {
        Thread worker = new Thread(() -> Client.runSession(settings), "ExperimentSession");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        if (worker.isAlive()) {
            System.err.println("TIMEOUT: phien vuot qua " + timeoutSeconds
                    + " giay; huy phien va ghi ket qua that bai.");
            Client.transferFailed = true;
            Client.sessionGen++;
            try {
                if (Client.clientSocket != null) Client.clientSocket.close();
            } catch (Exception ignored) {
                // The worker will also close the socket in runSession's finally block.
            }
            worker.interrupt();
            worker.join(3000);
        }
        SessionReport report = EventBus.pollReport();
        if (report == null) {
            report = new SessionReport();
            report.success = false;
            report.failReason = "Experiment session timeout/no report";
        }
        return report;
    }

    private static SessionSettings settings(String serverIp, int loss, int window,
                                            boolean cc, int messageRepeat, int delayMinMs,
                                            int delayMaxMs, int corruptPercent, int ssthresh) {
        SessionSettings s = new SessionSettings();
        s.serverIp = serverIp;
        s.lossPercent = loss;
        s.corruptPercent = corruptPercent;
        s.delayMinMs = delayMinMs;
        s.delayMaxMs = delayMaxMs;
        s.windowSize = window;
        s.congestionControl = cc;
        s.ssthresh = ssthresh;
        s.messageRepeat = messageRepeat;
        return s;
    }

    private static void writeAverage(BufferedWriter out, int loss, int window, boolean cc,
                                     List<SessionReport> reports) throws IOException {
        double transfer = average(reports, r -> r.transferMs);
        double segments = average(reports, r -> r.segments);
        double retrans = average(reports, r -> r.retransmissions);
        double fast = average(reports, r -> r.fastRetransmits);
        double timeouts = average(reports, r -> r.timeouts);
        double maxCwnd = average(reports, r -> r.maxCwnd);
        double throughput = average(reports, r -> r.transferMs > 0
                ? r.payloadBytes * 1000.0 / r.transferMs : 0);
        double successRate = 100.0 * reports.stream().filter(r -> r.success).count() / reports.size();
        String dataCheck = reports.stream().allMatch(r -> "DUNG".equals(r.dataCheck)) ? "DUNG" : "KHONG_DUNG";
        out.write(String.format(java.util.Locale.US,
                "%d,%d,%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.2f",
                loss, window, cc ? "on" : "off", reports.size(), transfer, segments,
                retrans, fast, timeouts, maxCwnd, throughput, dataCheck, successRate));
        out.newLine();
    }

    private interface Metric { double value(SessionReport report); }

    private static double average(List<SessionReport> reports, Metric metric) {
        double sum = 0;
        for (SessionReport report : reports) sum += metric.value(report);
        return sum / reports.size();
    }

}
