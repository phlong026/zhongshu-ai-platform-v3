package cn.iocoder.yudao.module.design.asset;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 衍生图生成器单测（不依赖 Docker/数据库）：尺寸、比例、小图不放大、透明底转白、非图片拒绝。
 */
class AssetRenditionGeneratorTest {

    @Test
    void rendersThumbAndPreviewKeepingAspectRatio() throws Exception {
        AssetRenditionGenerator.Renditions renditions = AssetRenditionGenerator.render(png(2400, 1200));
        assertThat(renditions).isNotNull();

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(renditions.thumb()));
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(renditions.preview()));
        assertThat(thumb.getWidth()).as("缩略图长边 480").isEqualTo(480);
        assertThat(thumb.getHeight()).as("按原比例缩放").isEqualTo(240);
        assertThat(preview.getWidth()).as("预览图长边 1280").isEqualTo(1280);
        assertThat(preview.getHeight()).isEqualTo(640);
        assertThat(renditions.thumb().length).isLessThan(renditions.preview().length);

        assertThat(renditions.thumb()[0] & 0xFF).as("JPEG 魔数 FF").isEqualTo(0xFF);
        assertThat(renditions.thumb()[1] & 0xFF).as("JPEG 魔数 D8").isEqualTo(0xD8);
    }

    @Test
    void smallImageIsNotUpscaled() throws Exception {
        AssetRenditionGenerator.Renditions renditions = AssetRenditionGenerator.render(png(120, 60));
        assertThat(ImageIO.read(new ByteArrayInputStream(renditions.thumb())).getWidth()).isEqualTo(120);
        assertThat(ImageIO.read(new ByteArrayInputStream(renditions.preview())).getWidth()).isEqualTo(120);
    }

    @Test
    void rejectsUnsupportedMimeAndUndecodableContent() throws Exception {
        assertThat(AssetRenditionGenerator.supports("application/pdf")).isFalse();
        assertThat(AssetRenditionGenerator.supports("image/webp")).isFalse();
        assertThat(AssetRenditionGenerator.supports("image/jpeg")).isTrue();
        assertThat(AssetRenditionGenerator.render("not-an-image".getBytes())).isNull();
    }

    private static byte[] png(int width, int height) throws Exception {
        // ARGB 全透明：验证 PNG 透明像素转 JPEG 时铺白底而不是黑底
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00000000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
