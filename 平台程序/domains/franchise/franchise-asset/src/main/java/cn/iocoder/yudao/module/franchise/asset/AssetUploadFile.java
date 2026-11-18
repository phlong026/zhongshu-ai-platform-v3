package cn.iocoder.yudao.module.franchise.asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One private snapshot for checking and publishing the same bytes, even if a client overwrites its upload key. */
final class AssetUploadFile implements AutoCloseable {
    final Path path;

    AssetUploadFile(Path directory) throws IOException {
        path = Files.createTempFile(directory,"fr-asset-", ".tmp");
    }

    record Integrity(long size, String sha) {}

    Integrity copy(InputStream source, long limit) throws IOException {
        try (var out = Files.newOutputStream(path)) {
            return transfer(source, out, limit);
        }
    }

    Integrity integrity(long limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return transfer(input, OutputStream.nullOutputStream(), limit);
        }
    }

    private static Integrity transfer(InputStream source, OutputStream out, long limit) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = source.read(buffer, 0, (int) Math.min(buffer.length, limit - size + 1))) != -1) {
            size += count;
            if (size > limit) return new Integrity(size, null);
            out.write(buffer, 0, count);
            digest.update(buffer, 0, count);
        }
        return new Integrity(size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
