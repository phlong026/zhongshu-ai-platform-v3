package cn.iocoder.yudao.module.franchise.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reserve both input and maximum sanitized output before creating either file. */
final class AssetSpool {
    private final Path directory;
    private final long capacity;
    private long reserved;

    AssetSpool(Path directory, long capacity) {
        this.directory=directory;
        this.capacity=capacity;
    }

    static final class CapacityBusy extends IOException {}

    synchronized Reservation reserve(long bytes) throws IOException {
        if (bytes <= 0 || bytes > capacity - reserved) throw new CapacityBusy();
        Files.createDirectories(directory);
        reserved += bytes;
        return new Reservation(bytes);
    }

    final class Reservation implements AutoCloseable {
        private long bytes;
        private Reservation(long bytes) { this.bytes=bytes; }
        AssetUploadFile file() throws IOException { return new AssetUploadFile(directory); }
        public void close() {
            synchronized (AssetSpool.this) {
                reserved -= bytes;
                bytes = 0;
            }
        }
    }
}
