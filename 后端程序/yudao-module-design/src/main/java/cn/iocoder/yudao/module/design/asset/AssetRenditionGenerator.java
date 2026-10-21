package cn.iocoder.yudao.module.design.asset;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 列表/详情图片衍生版本：缩略图长边 480、预览图长边 1280，统一 JPEG（质量 0.82）。
 *
 * <p>背景（2026-10-20 性能治理）：案例封面原图实测为 1632×1312 / 4.7MB 的 PNG，列表一屏 10 张
 * 合计 30.5MB，且每张还要两次票据请求；小程序端解码 10 张原图约 86MB 内存。列表/详情只需要
 * 几百像素的展示位，因此在服务端生成固定长边的 JPEG 衍生图，由稳定 URL 下发，交给客户端缓存。
 *
 * <p>实现约束：
 * <ul>
 *   <li>只处理 JDK ImageIO 可解码的图片 MIME（与 {@link AssetTypePolicy} 放开的格式一致），PDF 不生成；</li>
 *   <li>统一铺白底再编码 JPEG——PNG 透明背景直接转 JPEG 会得到黑底；</li>
 *   <li>小图不放大：长边已小于目标值时按原尺寸重编码，避免模糊；</li>
 *   <li>解码失败返回 {@code null}，由调用方降级（不阻断上传/读取主链路）。</li>
 * </ul>
 */
public final class AssetRenditionGenerator {

    /** 列表卡片：2 列网格在 3x 屏下约 330×220px，480 长边足够且单张约 40–80KB。 */
    public static final int THUMB_MAX_EDGE = 480;

    /** 详情页大图：1280 长边，单张约 150–300KB，替代 4.7MB 原图。 */
    public static final int PREVIEW_MAX_EDGE = 1280;

    private static final float JPEG_QUALITY = 0.82f;

    private AssetRenditionGenerator() {
    }

    public record Renditions(byte[] thumb, byte[] preview) {
    }

    /** 与 {@link AssetTypePolicy} 允许上传的图片格式保持一致；PDF 等非图片返回 false。 */
    public static boolean supports(String mime) {
        if (mime == null) {
            return false;
        }
        return switch (mime) {
            case "image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff" -> true;
            default -> false;
        };
    }

    /**
     * 一次解码生成缩略图与预览图两个版本。
     *
     * @return 解码失败返回 {@code null}
     * @throws IOException 编码失败（调用方按失败降级处理）
     */
    public static Renditions render(byte[] source) throws IOException {
        if (source == null || source.length == 0) {
            return null;
        }
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
        if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
            return null;
        }
        try {
            return new Renditions(encodeJpeg(original, THUMB_MAX_EDGE),
                    encodeJpeg(original, PREVIEW_MAX_EDGE));
        } finally {
            original.flush();
        }
    }

    private static byte[] encodeJpeg(BufferedImage source, int maxEdge) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1d, (double) maxEdge / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        // TYPE_INT_RGB：JPEG 无 alpha 通道，先铺白底避免透明区域变黑
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        try {
            return writeJpeg(target);
        } finally {
            target.flush();
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer unavailable");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
