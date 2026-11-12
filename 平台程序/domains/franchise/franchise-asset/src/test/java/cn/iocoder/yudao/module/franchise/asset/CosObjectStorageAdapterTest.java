package cn.iocoder.yudao.module.franchise.asset;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CosObjectStorageAdapterTest {
    @Test
    void boundedReadThenCloseAbortsInsteadOfDrainingAttackerControlledRemainder() throws Exception {
        var s3 = mock(S3Client.class);
        var aborted = new AtomicBoolean();
        var drained = new AtomicBoolean();
        InputStream endless = new InputStream() {
            @Override public int read() { return 1; }
            @Override public void close() { if (!aborted.get()) drained.set(true); }
        };
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(endless, () -> aborted.set(true))));
        var storage = new CosObjectStorageAdapter(s3, mock(S3Presigner.class), "private-test", 1000);
        try (InputStream input = storage.getObject("franchise/1/33/uploads/5.pdf")) {
            assertThat(input.readNBytes(32)).hasSize(32);
        }
        assertThat(aborted).isTrue();
        assertThat(drained).isFalse();
    }

    @Test
    void noReadOrWriteMayEscapeBusinessObjectPrefix() {
        var storage = new CosObjectStorageAdapter(mock(S3Client.class), mock(S3Presigner.class), "private-test", 1000);
        assertThatThrownBy(() -> storage.getObject("uploads/design/5.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.putObject("franchise/../design/5.png", new byte[1])).isInstanceOf(IllegalArgumentException.class);
    }
}
