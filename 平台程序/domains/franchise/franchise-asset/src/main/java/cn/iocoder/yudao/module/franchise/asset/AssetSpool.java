package cn.iocoder.yudao.module.franchise.asset;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/** The OS lock covers recovery and every live reservation, including rolling restarts. */
final class AssetSpool {
    private static final String LOCK = ".asset-spool.lock";
    private static final Pattern SNAPSHOT = Pattern.compile("fr-asset-v2-[0-9]+[.]tmp");
    private final Path directory;
    private final long capacity;
    private FileChannel owner;
    private long reserved;
    private long retained;

    AssetSpool(Path directory, long capacity) {
        this.directory = directory;
        this.capacity = capacity;
    }

    static final class CapacityBusy extends IOException {}

    synchronized void recover() throws IOException {
        if (owner == null) {
            try { acquire(); } catch (CapacityBusy busy) { return; }
            release();
        }
    }

    private void acquire() throws IOException {
        Files.createDirectories(directory);
        var channel = FileChannel.open(directory.resolve(LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            try {
                if (channel.tryLock() == null) throw new CapacityBusy();
            } catch (OverlappingFileLockException busy) { throw new CapacityBusy(); }
            retained = 0;
            try (var paths = Files.walk(directory)) {
                for (var it = paths.iterator(); it.hasNext();) {
                    Path path = it.next();
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile() || path.equals(directory.resolve(LOCK))) continue;
                    if (path.getParent().equals(directory) && SNAPSHOT.matcher(path.getFileName().toString()).matches()) {
                        try { Files.delete(path); continue; } catch (IOException stillOccupied) { /* Charge unreclaimed bytes. */ }
                    }
                    retained = Math.addExact(retained, attrs.size());
                }
            }
            owner = channel;
        } finally {
            if (owner != channel) channel.close();
        }
    }

    private void release() throws IOException {
        var channel = owner;
        owner = null;
        channel.close();
    }

    synchronized Reservation reserve(long bytes) throws IOException {
        if (bytes <= 0 || bytes > capacity) throw new CapacityBusy();
        if (owner == null) acquire();
        if (bytes > capacity - retained - reserved) {
            if (reserved == 0) release();
            throw new CapacityBusy();
        }
        reserved += bytes;
        return new Reservation(bytes);
    }

    final class Reservation implements AutoCloseable {
        private long bytes;
        private Reservation(long bytes) { this.bytes = bytes; }
        AssetUploadFile file() throws IOException { return new AssetUploadFile(directory); }
        public void close() throws IOException {
            synchronized (AssetSpool.this) {
                if (bytes == 0) return;
                reserved -= bytes;
                bytes = 0;
                if (reserved == 0) release();
            }
        }
    }
}
