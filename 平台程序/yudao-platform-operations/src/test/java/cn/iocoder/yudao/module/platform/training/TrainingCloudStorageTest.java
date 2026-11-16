package cn.iocoder.yudao.module.platform.training;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrainingCloudStorageTest {
    @TempDir Path directory;
    S3Client client;TrainingCloudStorage storage;
    final String key="versions/12345678-1234-1234-1234-123456789abc/source.pdf";
    final byte[] bytes="a private course document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup(){client=mock(S3Client.class);storage=new TrainingCloudStorage(client,"zhongshu-training-1397776231");}
    @AfterEach void close(){storage.close();}
    @Test void invalidObjectPathsNeverReachCloud(){for(String invalid:new String[]{"../source.pdf","versions/../source.pdf","uploads/123/0.part",key+"/../../other"})assertThatThrownBy(()->storage.store(invalid,directory.resolve("file"))).isInstanceOf(ServiceException.class);verifyNoInteractions(client);}
    @Test void uploadPinsPrivateBucketKeySizeAndHash()throws Exception{
        Path file=directory.resolve("original");Files.write(file,bytes);String hash=TrainingFiles.digest(bytes);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength((long)bytes.length).metadata(Map.of("sha256",hash)).build());
        storage.store(key,file);
        var request=org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);var body=org.mockito.ArgumentCaptor.forClass(RequestBody.class);verify(client).putObject(request.capture(),body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("zhongshu-training-1397776231");assertThat(request.getValue().key()).isEqualTo("training/"+key);assertThat(request.getValue().metadata()).containsEntry("sha256",hash);assertThat(request.getValue().aclAsString()).isNull();assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(bytes);
    }
    @Test void cloudAcknowledgementWithoutMatchingHashIsRejected()throws Exception{Path file=Files.write(directory.resolve("source"),bytes);when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength((long)bytes.length).metadata(Map.of("sha256","0".repeat(64))).build());assertThatThrownBy(()->storage.store(key,file)).isInstanceOf(ServiceException.class).hasMessageContaining("校验失败");}
    @SuppressWarnings({"unchecked","rawtypes"}) void download(byte[] content,String hash){
        when(client.getObject(any(GetObjectRequest.class),any(ResponseTransformer.class))).thenAnswer(a->{GetObjectRequest r=a.getArgument(0);assertThat(r.bucket()).isEqualTo("zhongshu-training-1397776231");assertThat(r.key()).isEqualTo("training/"+key);ResponseTransformer transformer=a.getArgument(1);return transformer.transform(GetObjectResponse.builder().contentLength((long)content.length).metadata(Map.of("sha256",hash)).build(),AbortableInputStream.create(new ByteArrayInputStream(content)));});
    }
    @Test void cloudRestoresMissingCacheAndSubsequentReadsUseCache()throws Exception{download(bytes,TrainingFiles.digest(bytes));Path file=directory.resolve("cache/source.pdf");storage.restore(key,file);assertThat(Files.readAllBytes(file)).isEqualTo(bytes);clearInvocations(client);storage.restore(key,file);verifyNoInteractions(client);}
    @Test void corruptCloudDownloadNeverBecomesReadableCache()throws Exception{download(bytes,"0".repeat(64));Path file=directory.resolve("cache/source.pdf");assertThatThrownBy(()->storage.restore(key,file)).isInstanceOf(ServiceException.class);assertThat(file).doesNotExist();try(var files=Files.list(file.getParent())){assertThat(files.count()).isZero();}}
    @Test void storageErrorsAreSanitized()throws Exception{Path file=Files.write(directory.resolve("source"),bytes);when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenThrow(S3Exception.builder().statusCode(403).message("secret-account-and-provider-details").build());assertThatThrownBy(()->storage.store(key,file)).isInstanceOf(ServiceException.class).hasMessageContaining("写入失败").hasMessageNotContaining("secret-account");}
    @Test void configurationRejectsNonCosEndpointsWithoutEchoingCredentials(){assertThatThrownBy(()->new TrainingCloudStorage("http://127.0.0.1","ap-shanghai","bucket-1397776231","private-id","private-key")).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-id").hasMessageNotContaining("private-key");}
}
