package cn.iocoder.yudao.module.franchise.design.publiccase;

/** Sanitized failure from A's public catalog. */
public final class PublicCaseCatalogException extends RuntimeException {

    private final Integer upstreamCode;
    private final boolean deadlineExceeded;
    private final boolean invalidResponse;

    PublicCaseCatalogException(String message, Integer upstreamCode, boolean deadlineExceeded,
                               boolean invalidResponse) {
        super(message);
        this.upstreamCode = upstreamCode;
        this.deadlineExceeded = deadlineExceeded;
        this.invalidResponse = invalidResponse;
    }

    public Integer upstreamCode() {
        return upstreamCode;
    }

    public boolean deadlineExceeded() {
        return deadlineExceeded;
    }

    public boolean invalidResponse() {
        return invalidResponse;
    }
}
