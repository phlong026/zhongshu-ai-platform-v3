package cn.iocoder.yudao.module.franchise.design.publiccase;

/** Sanitized failure from A's public catalog. */
public final class PublicCaseCatalogException extends RuntimeException {

    private final Integer upstreamCode;

    PublicCaseCatalogException(String message, Integer upstreamCode) {
        super(message);
        this.upstreamCode = upstreamCode;
    }

    public Integer upstreamCode() {
        return upstreamCode;
    }
}
