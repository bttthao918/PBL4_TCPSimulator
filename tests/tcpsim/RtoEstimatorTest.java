package tcpsim;

/** Small deterministic regression test for RFC 6298 arithmetic and backoff. */
public class RtoEstimatorTest {
    public static void main(String[] args) {
        RtoEstimator estimator = new RtoEstimator();
        estimator.reset(System.currentTimeMillis());
        check(estimator.getRtoMs() == 1000, "initial RTO");

        estimator.onRttSample(20);
        check(estimator.getSrttMs() == 20, "first SRTT");
        check(estimator.getRtoMs() == 200, "minimum RTO clamp");

        estimator.onRttSample(300);
        check(Math.abs(estimator.getSrttMs() - 55) < 0.001, "RFC SRTT update");
        check(estimator.getRtoMs() == 365, "RFC RTTVAR/RTO update");

        estimator.onTimeout();
        check(estimator.getRtoMs() == 730, "exponential backoff");
        System.out.println("RtoEstimatorTest passed");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
