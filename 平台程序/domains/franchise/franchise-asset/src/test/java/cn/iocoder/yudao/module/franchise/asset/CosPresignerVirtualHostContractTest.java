package cn.iocoder.yudao.module.franchise.asset;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class CosPresignerVirtualHostContractTest {

    private static final String BUCKET = "zhongshu-platform-b-assets-1397776231";
    private static final URI REGIONAL_ENDPOINT = URI.create("https://cos.ap-shanghai.myqcloud.com");
    private static final String OBJECT_HOST = BUCKET + ".cos.ap-shanghai.myqcloud.com";

    @Test
    void productionAdapterGeneratesBucketVirtualHostForGetAndPut() {
        var storage = new CosObjectStorageAdapter(REGIONAL_ENDPOINT.toString(), "ap-shanghai", BUCKET,
                "offline-test-id", "offline-test-key", false, 20_000);
        try {
            URI getUrl = URI.create(storage.presignDownloadUrl("franchise/1/example.png", 300));
            URI putUrl = URI.create(storage.presignUploadUrl("franchise/1/upload.png", 300));

            assertThat(getUrl.getScheme()).isEqualTo("https");
            assertThat(putUrl.getScheme()).isEqualTo("https");
            assertThat(getUrl.getHost()).isEqualTo(OBJECT_HOST);
            assertThat(putUrl.getHost()).isEqualTo(OBJECT_HOST);
        } finally {
            storage.close();
        }
    }
}
