package cn.iocoder.yudao.module.design.capability.security;

final class CapabilityAuthenticationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    CapabilityAuthenticationException(int status, String reason) {
        super(reason);
        this.status = status;
    }

    int status() {
        return status;
    }
}
