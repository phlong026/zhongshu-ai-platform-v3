package cn.iocoder.yudao.module.design.capability;

public final class CapabilityException extends RuntimeException {
    private final int httpStatus;
    private final int code;
    public CapabilityException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
    public int getHttpStatus() { return httpStatus; }
    public int getCode() { return code; }
    public static CapabilityException forbidden() { return new CapabilityException(403, 403, "设计服务资源不可访问"); }
    public static CapabilityException conflict() { return new CapabilityException(409, 409, "同一业务键不能用于不同内容或操作者"); }
}
