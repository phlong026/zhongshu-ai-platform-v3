package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图片衍生版本端点的 HTTP 契约（不依赖 Docker/数据库）：JPEG 内容类型、公开缓存头、失败统一 404。
 */
class AppAssetRenditionControllerTest {

    private AppAssetController controller;
    private AssetService assetService;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        controller = new AppAssetController();
        ReflectionTestUtils.setField(controller, "assetService", assetService);
        ReflectionTestUtils.setField(controller, "identitySessionPort",
                (IdentitySessionPort) token -> "token".equals(token)
                        ? Optional.of(new IdentitySessionPort.SessionContext(9L, "test", "openid", false))
                        : Optional.empty());
    }

    @Test
    void publicRenditionIsServedAsJpegWithOneHourPublicCache() {
        when(assetService.readRendition(0L, 7L, "thumbnail"))
                .thenReturn(new AssetService.Rendition(new byte[]{1, 2, 3}, true));

        ResponseEntity<byte[]> response = controller.getThumbnail("7", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(response.getHeaders().getCacheControl()).contains("public").contains("max-age=3600");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void ownerOnlyRenditionUsesPrivateCache() {
        when(assetService.readRendition(9L, 7L, "preview"))
                .thenReturn(new AssetService.Rendition(new byte[]{4}, false));

        ResponseEntity<byte[]> response = controller.getPreview("7", "Bearer token");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("private").doesNotContain("public");
    }

    @Test
    void missingOrForbiddenRenditionReturns404WithoutJsonBody() {
        when(assetService.readRendition(0L, 8L, "preview"))
                .thenThrow(new ServiceException(1_099_000_002, "forbidden"));

        ResponseEntity<byte[]> response = controller.getPreview("8", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void malformedAssetIdReturns404() {
        assertThat(controller.getThumbnail("not-a-number", null).getStatusCode().value()).isEqualTo(404);
    }
}
