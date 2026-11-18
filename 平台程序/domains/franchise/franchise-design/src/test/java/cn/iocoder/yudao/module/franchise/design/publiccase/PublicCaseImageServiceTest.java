package cn.iocoder.yudao.module.franchise.design.publiccase;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicCaseImageServiceTest {
    private byte[] png() throws IOException {
        var image=new BufferedImage(2400,1600,BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void coverIs640PixelJpegAndPreviewKeepsMoreDetail() throws Exception {
        var cover=PublicCaseImageService.render(png(),"COVER");
        var small=ImageIO.read(new ByteArrayInputStream(cover.bytes()));
        assertEquals(640,small.getWidth());assertEquals(427,small.getHeight());assertEquals("image/jpeg",cover.contentType());
        var preview=ImageIO.read(new ByteArrayInputStream(PublicCaseImageService.render(png(),"PREVIEW").bytes()));
        assertEquals(1800,preview.getWidth());
    }
    @Test void originalKeepsExactBytes() throws Exception {
        byte[] input=png();assertArrayEquals(input,PublicCaseImageService.render(input,"ORIGINAL").bytes());
    }
    @Test void errorBodiesAndUnapprovedOriginsAreRejected() throws Exception {
        assertThrows(IOException.class,()->PublicCaseImageService.render("{\"code\":401}".getBytes(),"COVER"));
        var service=new PublicCaseImageService();
        assertThrows(IOException.class,()->service.read("https://127.0.0.1/private","COVER"));
        assertThrows(IOException.class,()->service.read("http://zhongshu-design-assets-1397776231.cos.ap-shanghai.myqcloud.com/a","COVER"));
        assertThrows(IllegalArgumentException.class,()->service.read("https://example.com","UNKNOWN"));
    }
}
