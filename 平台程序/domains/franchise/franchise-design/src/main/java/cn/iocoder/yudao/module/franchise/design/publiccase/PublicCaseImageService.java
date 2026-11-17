package cn.iocoder.yudao.module.franchise.design.publiccase;

import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded image delivery on the mini-program's existing API domain. No signed upstream URLs in the client. */
@Service
public class PublicCaseImageService {
    static final int MAX_BYTES = 24 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore permits = new Semaphore(3);
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private long cacheBytes;
    private record Cached(Image value, long expires) { }
    public record Image(byte[] bytes, String contentType) { }

    public Image read(String downloadUrl, String rendition) throws IOException, InterruptedException {
        if (!Set.of("COVER", "PREVIEW", "ORIGINAL").contains(rendition)) throw new IllegalArgumentException("Unknown image rendition");
        URI uri = URI.create(downloadUrl);
        // This is A's configured private image bucket, never a caller-supplied origin.
        if (!"https".equals(uri.getScheme()) || !"zhongshu-design-assets-1397776231.cos.ap-shanghai.myqcloud.com".equals(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IOException("Image origin is not allowed");
        String key = uri.getHost() + uri.getRawPath() + ":" + rendition;
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && hit.expires > System.currentTimeMillis()) return hit.value;
        }
        if (!permits.tryAcquire()) throw new IOException("Image delivery is busy; retry shortly");
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Image upstream HTTP " + response.statusCode());
                if (response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BYTES) throw new IOException("Image exceeds size limit");
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IOException("Image exceeds size limit");
            Image image = render(bytes, rendition);
            if (!"ORIGINAL".equals(rendition)) synchronized (cache) {
                Cached previous = cache.put(key, new Cached(image, System.currentTimeMillis() + 300_000));
                cacheBytes += image.bytes.length - (previous == null ? 0 : previous.value.bytes.length);
                var it = cache.entrySet().iterator();
                while (cacheBytes > 32L * 1024 * 1024 && it.hasNext()) { cacheBytes -= it.next().getValue().value.bytes.length; it.remove(); }
            }
            return image;
        } finally { permits.release(); }
    }

    static Image render(byte[] bytes, String rendition) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image response");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long)width * height > 80_000_000) throw new IOException("Invalid image dimensions");
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("jpeg", "jpg", "png", "webp").contains(format)) throw new IOException("Unsupported image type");
                if ("ORIGINAL".equals(rendition)) return new Image(bytes, "image/" + (format.equals("jpg") ? "jpeg" : format));
                int limit = "COVER".equals(rendition) ? 640 : 1800;
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / limit);
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage source = reader.read(0, params);
                double scale = Math.min(1, (double)limit / Math.max(width, height));
                BufferedImage target = new BufferedImage(Math.max(1,(int)Math.round(width * scale)), Math.max(1,(int)Math.round(height * scale)), BufferedImage.TYPE_INT_RGB);
                var graphics = target.createGraphics();
                try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, target.getWidth(), target.getHeight(), null);
                } finally { graphics.dispose(); source.flush(); }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(target, "jpeg", output); target.flush();
                return new Image(output.toByteArray(), "image/jpeg");
            } finally { reader.dispose(); }
        }
    }
}
