package cn.iocoder.yudao.module.franchise.asset;

/** B 私有对象存储。上传和验收对象分前缀，下载使用短期票据或签名 URL。 */
public interface ObjectStoragePort {

    /** 客户端直传地址（真实 COS 为预签名 PUT URL；本地适配器返回内部标记地址） */
    String presignUploadUrl(String objectKey, long ttlSeconds);

    /** 对象是否存在且大小一致 */
    boolean existsWithSize(String objectKey, long expectedSize);

    /** 读取对象内容（校验/消毒用；调用方负责关闭流） */
    java.io.InputStream getObject(String objectKey);

    /** 服务端写对象；已审核内容必须写入客户端无写权限的独立不可变 key。 */
    void putObject(String objectKey, byte[] content);

    /** 短期下载 URL */
    String presignDownloadUrl(String objectKey, long ttlSeconds);

}
