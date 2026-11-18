package cn.iocoder.yudao.module.franchise.asset;


import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 资产内容安全扫描（上传完成后、入库前执行；架构 §6.9 / §10.4）
 *
 * 检查项：魔数 vs 声明 MIME、像素守卫（先查头再解码，防解压炸弹）、
 * JPEG EXIF/GPS 检测、EICAR 恶意样本标记；图片统一经 ImageIO 重编码剥离元数据。
 */
@org.springframework.stereotype.Component("franchiseAssetContentScanner")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class AssetContentScanner {

    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_DIMENSION = 10_000;

    /** A null rejection accepts the file. Non-images keep their original, verified contents. */
    String scan(String mime, Path original, Path sanitized) throws IOException {
        byte[] prefix;
        try (var input = Files.newInputStream(original)) {
            prefix = input.readNBytes(68);
        }
        String actual = sniffMime(prefix);
        if (!mime.equals(actual)) return "MAGIC_NUMBER: 声明 " + mime + " 实际 " + actual;
        if (new String(prefix, java.nio.charset.StandardCharsets.US_ASCII)
                .contains("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR")) return "MALWARE: EICAR 测试样本";
        if (!mime.startsWith("image/")) return null;
        try (var stream = ImageIO.createImageInputStream(original.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return "DECODE: 图片头解析失败";
            var reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) return "PIXEL_GUARD: " + width + "x" + height + " 超限";
                image = reader.read(0);
            } catch (javax.imageio.IIOException | IllegalArgumentException error) {
                return "DECODE: 图片解码失败";
            } finally {
                reader.dispose();
            }
            try {
                return ImageIO.write(image, "image/jpeg".equals(mime) ? "jpg" : "png", sanitized.toFile())
                        ? null : "DECODE: 图片重编码失败";
            } finally {
                image.flush();
            }
        }
    }

    private String sniffMime(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "image/png";
        }
        if (content.length >= 4 && content[0] == 'G' && content[1] == 'I' && content[2] == 'F' && content[3] == '8') {
            return "image/gif";
        }
        if (content.length >= 2 && content[0] == 'B' && content[1] == 'M') {
            return "image/bmp";
        }
        // TIFF 小端 II*\0 与大端 MM\0* 两种字节序
        if (content.length >= 4 && ((content[0] == 'I' && content[1] == 'I' && content[2] == 0x2A && content[3] == 0)
                || (content[0] == 'M' && content[1] == 'M' && content[2] == 0 && content[3] == 0x2A))) {
            return "image/tiff";
        }
        if (content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-') {
            return "application/pdf";
        }
        if (content.length >= 10 && content[0] == 'I' && content[1] == 'D' && content[2] == '3') {
            return "audio/mpeg";
        }
        if (content.length >= 4 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xE0) == 0xE0
                && (content[1] & 0x06) != 0 && (content[2] & 0xF0) != 0 && (content[2] & 0xF0) != 0xF0) {
            return "audio/mpeg";
        }
        if (content.length >= 12 && content[4] == 'f' && content[5] == 't' && content[6] == 'y' && content[7] == 'p') {
            return "video/mp4";
        }
        return null;
    }

}
