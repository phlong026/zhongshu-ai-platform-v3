package cn.iocoder.yudao.module.franchise.asset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** B 独立开发存储目录；所有字节入口仍须经过组织鉴权及持久票据。 */
@Slf4j
@Component("franchiseLocalObjectStorageAdapter")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!${platform.legacy-design-bridge.enabled:true}")
@ConditionalOnProperty(name = "zhongshu.franchise.asset.storage.provider", havingValue = "local")
public class LocalObjectStorageAdapter implements ObjectStoragePort {
    private final Path root;

    public LocalObjectStorageAdapter(@Value("${zhongshu.franchise.asset.storage-root:${java.io.tmpdir}/franchise-assets}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!key.startsWith("franchise/") || key.contains("..") || !path.startsWith(root)) {
            throw new IllegalArgumentException("非法业务资产对象路径");
        }
        return path;
    }

    @Override public String presignUploadUrl(String key, long ttl) { resolve(key); return "local://" + key; }
    @Override public String presignDownloadUrl(String key, long ttl) { resolve(key); return "local://" + key; }

    @Override
    public boolean existsWithSize(String key, long expected) {
        try { Path path = resolve(key); return Files.exists(path) && Files.size(path) == expected; }
        catch (IOException e) { throw failure("head", key, e); }
    }

    @Override
    public InputStream getObject(String key) {
        try { return Files.newInputStream(resolve(key)); }
        catch (IOException e) { throw failure("read", key, e); }
    }

    @Override
    public void putObject(String key, Path content) {
        try {
            Path path = resolve(key);
            Files.createDirectories(path.getParent());
            Files.copy(content, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw failure("write", key, e); }
    }

    private ObjectStorageException failure(String operation, String key, IOException e) {
        log.warn("B asset storage failed operation={} key={} error={}", operation, key, e.getClass().getSimpleName());
        return new ObjectStorageException(ObjectStorageException.Reason.SERVICE_ERROR, key, operation);
    }
}
