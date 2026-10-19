package tcpsim.gui;

/**
 * SessionRunner.java (Buoc 5)
 * ---------------------------
 * GUI khong biet gi ve TCP: khi bam "Chay lai" no chi goi runSession(...) cua
 * lop nao do (o day la Client). Ham nay chay tren mot thread rieng, tu bao ket qua
 * qua EventBus.publishReport(...) khi xong.
 */
public interface SessionRunner {
    void runSession(SessionSettings settings);
}
