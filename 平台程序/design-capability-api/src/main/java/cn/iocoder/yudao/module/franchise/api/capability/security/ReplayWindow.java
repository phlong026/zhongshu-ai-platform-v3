package cn.iocoder.yudao.module.franchise.api.capability.security;

/** Inclusive timestamp window. clockSkewSeconds bounds each verifier's absolute error from UTC. */
public final class ReplayWindow {
    private final long toleranceSeconds;
    private final int nonceTtlSeconds;

    public ReplayWindow(long windowSeconds, long clockSkewSeconds) {
        if (windowSeconds < 1 || windowSeconds > 86400 || clockSkewSeconds < 0 || clockSkewSeconds > 86400
                || windowSeconds + clockSkewSeconds > 86400) {
            throw new IllegalArgumentException("Capability time window must be positive and at most one day including skew");
        }
        toleranceSeconds = Math.addExact(windowSeconds, clockSkewSeconds);
        // Each verifier may be +/- skew from UTC, so two verifiers can differ by 2*skew.
        nonceTtlSeconds = Math.toIntExact(Math.addExact(Math.multiplyExact(2, toleranceSeconds),
                Math.addExact(Math.multiplyExact(2, clockSkewSeconds), 1)));
    }

    public boolean accepts(String timestamp, long nowSeconds) {
        if (timestamp == null || timestamp.length() > 19 || nowSeconds < 0) {
            return false;
        }
        final long value;
        try {
            value = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (value < 0 || !Long.toString(value).equals(timestamp)) {
            return false;
        }
        long earliest = nowSeconds < toleranceSeconds ? 0 : nowSeconds - toleranceSeconds;
        long latest = nowSeconds > Long.MAX_VALUE - toleranceSeconds ? Long.MAX_VALUE : nowSeconds + toleranceSeconds;
        return value >= earliest && value <= latest;
    }

    /** Covers both acceptance windows, maximum verifier-to-verifier skew, and epoch-second rounding. */
    public int nonceTtlSeconds() {
        return nonceTtlSeconds;
    }
}
