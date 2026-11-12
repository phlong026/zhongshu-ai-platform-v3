package cn.iocoder.yudao.module.franchise.design.remote.transport;

/** Unknown writes must be reconciled through receipts; never transparently re-submit a create. */
public final class CapabilityTransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean outcomeUnknown;

    CapabilityTransportException(String message, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
