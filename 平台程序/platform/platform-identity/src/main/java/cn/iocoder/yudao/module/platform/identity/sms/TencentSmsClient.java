package cn.iocoder.yudao.module.platform.identity.sms;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.SMS_SEND_FAILED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.SMS_SERVICE_UNAVAILABLE;

/**
 * M02-T04：腾讯云短信 real 通道（批次#39 渠道商拍板=腾讯）。
 * G0 门禁模式接入（StubSmsClient 反向互补）：provider=real 时激活；
 * 构造期校验五配置齐备（SdkAppId/签名/模板/SecretId/SecretKey——缺一启动失败，
 * 与 RealServiceWiringPolicy REAL_MODE_REQUIREMENTS 登记一致）。
 * 手机号发送前加 +86 国别码（国内短信）；验证码经模板参数传递（模板须含 {1}）。
 * 发送结果非 Ok 映射 1_104_000_009（带腾讯 Message 摘要——不含凭据）；
 * SDK 异常映射 1_104_000_002 服务不可用。日志只带脱敏手机号，绝不打印验证码。
 */
@Component
@ConditionalOnProperty(prefix = "platform.identity.sms", name = "provider", havingValue = "real")
@Slf4j
public class TencentSmsClient implements SmsClient {

    private final com.tencentcloudapi.sms.v20210111.SmsClient sdk;
    private final String sdkAppId;
    private final String signName;
    private final String templateId;

    public TencentSmsClient(com.tencentcloudapi.sms.v20210111.SmsClient sdk, String sdkAppId, String signName, String templateId,
                            String secretId, String secretKey, String region) {
        if (isBlank(sdkAppId) || isBlank(signName) || isBlank(templateId)
                || isBlank(secretId) || isBlank(secretKey) || isBlank(region)) {
            throw new IllegalStateException(
                    "短信 real 通道（platform.identity.sms.provider=real）要求 platform.identity.sms.* "
                            + "sdk-app-id/sign-name/template-id/secret-id/secret-key/region 全部非空");
        }
        this.sdk = Objects.requireNonNull(sdk, "SmsClient");
        this.sdkAppId = sdkAppId;
        this.signName = signName;
        this.templateId = templateId;
    }

    @Override
    public void sendLoginCaptcha(String mobile, String captcha) {
        SendSmsRequest req = new SendSmsRequest();
        req.setSmsSdkAppId(sdkAppId);
        req.setSignName(signName);
        req.setTemplateId(templateId);
        req.setPhoneNumberSet(new String[]{"+86" + mobile});
        req.setTemplateParamSet(new String[]{captcha});
        req.setSessionContext("cv-e4-" + System.currentTimeMillis());
        try {
            SendSmsResponse resp = sdk.SendSms(req);
            SendStatus[] statuses = resp.getSendStatusSet();
            SendStatus status = statuses == null || statuses.length == 0 ? null : statuses[0];
            if (status == null || !"Ok".equals(status.getCode())) {
                String reason = status == null ? "空响应" : status.getMessage();
                log.warn("[sendLoginCaptcha][real 短信发送失败] mobile={} code={} reason={}",
                        mask(mobile), status == null ? "?" : status.getCode(), reason);
                throw exception(PlatformIdentityErrorCodes.SMS_SEND_FAILED, reason);
            }
            log.info("[sendLoginCaptcha][real 短信已提交] mobile={}", mask(mobile));
        } catch (TencentCloudSDKException e) {
            log.warn("[sendLoginCaptcha][腾讯云短信 SDK 异常] mobile={} message={}", mask(mobile), e.getMessage());
            throw exception(PlatformIdentityErrorCodes.SMS_SERVICE_UNAVAILABLE);
        }
    }

    /** 腾讯云 SDK 装配（独立于构造器——测试可注入 mock；生产由 Spring 按 profile 配置构造） */
    public static com.tencentcloudapi.sms.v20210111.SmsClient buildSdk(String secretId, String secretKey, String region) {
        HttpProfile http = new HttpProfile();
        http.setEndpoint("sms.tencentcloudapi.com");
        http.setReadTimeout(20_000); // 无 setReqTimeout（3.1.1280 签名=read/write timeout）
        ClientProfile profile = new ClientProfile();
        profile.setHttpProfile(http);
        return new com.tencentcloudapi.sms.v20210111.SmsClient(new Credential(secretId, secretKey), region, profile);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 7 ? "***"
                : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}
