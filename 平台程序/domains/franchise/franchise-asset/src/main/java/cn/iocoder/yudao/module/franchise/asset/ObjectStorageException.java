package cn.iocoder.yudao.module.franchise.asset;

/** 私有对象存储错误分类。只携带净化的操作上下文，不传播签名 URL 或 SDK 原始异常。 */
public class ObjectStorageException extends IllegalStateException {

    /** 失败根因分类（T13-11：区分 404/权限拒绝/超时，不将所有错误当不存在）。 */
    public enum Reason {
        /** 对象确不存在（S3 404 / NoSuchKey）——existsWithSize 对此返回 false，getObject 对此抛出。 */
        NOT_FOUND,
        /** 权限拒绝（S3 403 / AccessDenied）——服务端凭据无权访问，属配置/权限问题，绝非「不存在」。 */
        ACCESS_DENIED,
        /** 调用超时或网络/客户端异常（ApiCallTimeout / SdkClientException）——瞬态，可重试，绝非「不存在」。 */
        TIMEOUT,
        /** 其它 S3 服务异常（5xx 等）——服务端侧问题，绝不静默降级为「不存在」。 */
        SERVICE_ERROR
    }

    private final Reason reason;

    public ObjectStorageException(Reason reason, String objectKey, String detail) {
        super("对象存储失败[" + reason + "] key=" + objectKey + (detail == null || detail.isBlank() ? "" : "：" + detail));
        this.reason = reason;
    }

    /** 失败根因分类，供调用方按需区分处理（如瞬态超时可重试、权限拒绝需告警运维）。 */
    public Reason reason() {
        return reason;
    }
}
