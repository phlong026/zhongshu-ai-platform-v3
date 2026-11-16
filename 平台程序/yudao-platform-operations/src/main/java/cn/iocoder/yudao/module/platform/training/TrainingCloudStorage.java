package cn.iocoder.yudao.module.platform.training;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;

/** Dedicated private COS bucket. The API still authorizes every media request; no public COS URLs are issued. */
@Component
@ConditionalOnProperty(name="training.cloud.enabled",havingValue="true")
public class TrainingCloudStorage {
    private final S3Client client;
    private final String bucket;
    private final Object[] locks=new Object[64];

    @Autowired
    public TrainingCloudStorage(@Value("${training.cloud.endpoint:}") String endpoint,
      @Value("${training.cloud.region:}") String region,@Value("${training.cloud.bucket:}") String bucket,
      @Value("${training.cloud.secret-id:}") String secretId,@Value("${training.cloud.secret-key:}") String secretKey){
        this(build(endpoint,region,secretId,secretKey),bucket);
    }
    TrainingCloudStorage(S3Client client,String bucket){
        if(client==null||bucket==null||!bucket.matches("[a-z0-9][a-z0-9-]{1,57}-[0-9]{5,}"))
            throw new IllegalArgumentException("课程私有云存储配置无效");
        this.client=client;this.bucket=bucket;for(int i=0;i<locks.length;i++)locks[i]=new Object();
    }
    private static S3Client build(String endpoint,String region,String secretId,String secretKey){
        if(region==null||!region.matches("[a-z]{2}-[a-z]+")||!(("https://cos."+region+".myqcloud.com").equals(endpoint))
          ||secretId==null||secretId.isBlank()||secretKey==null||secretKey.isBlank())
            throw new IllegalArgumentException("课程私有云存储地域、HTTPS地址或凭据配置缺失");
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(secretId,secretKey)))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).chunkedEncodingEnabled(false).build())
          .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofMinutes(10))
            .apiCallAttemptTimeout(Duration.ofMinutes(5)).build()).build();
    }
    private String key(String relative){
        if(relative==null||!relative.matches("versions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:source\\.[a-z0-9]+|play\\.(?:mp4|mp3)|poster\\.jpg|image\\.png|preview/source\\.pdf)"))
            throw new ServiceException(400,"课程云存储路径无效");
        return "training/"+relative;
    }
    public void store(String relative,Path file){
        String object=key(relative);
        try{
            String hash=TrainingFiles.digest(file);long size=Files.size(file);
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(object)
              .metadata(Map.of("sha256",hash)).build(),RequestBody.fromFile(file));
            var saved=client.headObject(HeadObjectRequest.builder().bucket(bucket).key(object).build());
            if(saved.contentLength()==null||saved.contentLength()!=size||!hash.equals(saved.metadata().get("sha256")))
                throw new ServiceException(503,"课程云存储校验失败，请重试处理");
        }catch(ServiceException e){throw e;}catch(Exception e){throw new ServiceException(503,"课程云存储写入失败，请检查存储服务后重试");}
    }
    public void restore(String relative,Path destination){
        String object=key(relative);
        synchronized(locks[Math.floorMod(object.hashCode(),locks.length)]){
            if(Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS))return;
            Path temporary=null;
            try{
                Files.createDirectories(destination.getParent());temporary=Files.createTempFile(destination.getParent(),"cloud-",".tmp");
                GetObjectResponse result;
                try(var out=Files.newOutputStream(temporary)){
                    result=client.getObject(GetObjectRequest.builder().bucket(bucket).key(object).build(),ResponseTransformer.toOutputStream(out));
                }
                String expected=result.metadata().get("sha256");
                if(result.contentLength()==null||Files.size(temporary)!=result.contentLength()||expected==null
                  ||!expected.matches("[0-9a-f]{64}")||!expected.equals(TrainingFiles.digest(temporary)))
                    throw new ServiceException(503,"云端课程资料校验失败，请联系管理员");
                Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(ServiceException e){throw e;}catch(Exception e){throw new ServiceException(503,"云端课程资料暂时无法读取，请稍后重试");}
            finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
        }
    }
    @PreDestroy public void close(){client.close();}
}
