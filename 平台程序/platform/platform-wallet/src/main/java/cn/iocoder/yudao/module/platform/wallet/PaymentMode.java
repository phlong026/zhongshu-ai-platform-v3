package cn.iocoder.yudao.module.platform.wallet;

/** Global payment execution mode. The selected mode is snapshotted on every new payment order. */
public enum PaymentMode {
    MOCK,
    REAL;

    public static PaymentMode parse(String value) {
        if (value == null) throw new IllegalArgumentException("Payment mode is required");
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "MOCK" -> MOCK;
            case "REAL" -> REAL;
            default -> throw new IllegalArgumentException("Unsupported payment mode");
        };
    }
}
