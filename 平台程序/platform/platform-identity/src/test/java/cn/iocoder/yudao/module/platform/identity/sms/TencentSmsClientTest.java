package cn.iocoder.yudao.module.platform.identity.sms;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M02-T04（批次#39）：腾讯云短信 real 通道纯单测——
 * 请求组装（SdkAppId/签名/模板/手机号 +86 前缀/验证码参数集）、
 * Ok 解析、非 Ok 映射 1_104_000_009（SDK 异常→服务不可用 1_104_000_010）、
 * 构造期配置缺失守卫（G0 门禁——real 激活必须六配置齐备）。
 * 腾讯 SmsClient 用手写假体继承（构造 protected 依赖可空传——行为可控可断言）。
 */
class TencentSmsClientTest {

    private static TencentSmsClient client(TencentSmsSdkStub sdk) {
        return new TencentSmsClient(sdk, "1400000001", "众墅之家", "2601234",
                "AKIDtest", "secret-test", "ap-guangzhou");
    }

    /** 腾讯 SmsClient 构造声明受检异常——测试统一经此工厂（失败即断言错误） */
    private static TencentSmsSdkStub newStub() {
        try {
            return new TencentSmsSdkStub();
        } catch (com.tencentcloudapi.common.exception.TencentCloudSDKException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void buildsRequestWithE164MobileAndTemplateParams() {
        TencentSmsSdkStub sdk = newStub();
        sdk.ok = true;
        client(sdk).sendLoginCaptcha("13912345678", "882913");

        SendSmsRequest captured = sdk.captured;
        assertThat(captured.getSmsSdkAppId()).isEqualTo("1400000001");
        assertThat(captured.getSignName()).isEqualTo("众墅之家");
        assertThat(captured.getTemplateId()).isEqualTo("2601234");
        assertThat(captured.getPhoneNumberSet()).containsExactly("+8613912345678");
        assertThat(captured.getTemplateParamSet()).containsExactly("882913");
        assertThat(captured.getSessionContext()).isNotBlank();
    }

    @Test
    void okStatusPassesSilently() {
        TencentSmsSdkStub sdk = newStub();
        sdk.ok = true;
        client(sdk).sendLoginCaptcha("13912345678", "882913"); // 不抛即通过
    }

    @Test
    void nonOkStatusMapsToSendFailed() {
        TencentSmsSdkStub sdk = newStub();
        sdk.ok = false;
        sdk.reason = "签名未审批";

        assertThatThrownBy(() -> client(sdk).sendLoginCaptcha("13912345678", "882913"))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformIdentityErrorCodes.SMS_SEND_FAILED.getCode()));
    }

    @Test
    void sdkExceptionMapsToServiceUnavailable() {
        TencentSmsSdkStub sdk = newStub();
        sdk.throwSdk = true;

        assertThatThrownBy(() -> client(sdk).sendLoginCaptcha("13912345678", "882913"))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformIdentityErrorCodes.SMS_SERVICE_UNAVAILABLE.getCode()));
    }

    @Test
    void constructorRejectsBlankConfig() {
        // G0 门禁：real 激活必须六配置齐备（缺一启动失败——与 stub 仅 local 反向互补）
        assertThatThrownBy(() -> new TencentSmsClient(newStub(), "", "签名", "模板", "id", "key", "ap-guangzhou"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 腾讯 SmsClient 手写假体（行为可控可断言；super 构造凭据为占位——不发真实请求） */
    static class TencentSmsSdkStub extends com.tencentcloudapi.sms.v20210111.SmsClient {
        boolean ok = true;
        boolean throwSdk = false;
        String reason = "Ok";
        SendSmsRequest captured;

        TencentSmsSdkStub() throws com.tencentcloudapi.common.exception.TencentCloudSDKException {
            super(new com.tencentcloudapi.common.Credential("id", "key"), "ap-guangzhou");
        }

        @Override
        public SendSmsResponse SendSms(SendSmsRequest req) throws com.tencentcloudapi.common.exception.TencentCloudSDKException {
            captured = req;
            if (throwSdk) {
                throw new com.tencentcloudapi.common.exception.TencentCloudSDKException("err", "request failed");
            }
            SendSmsResponse resp = new SendSmsResponse();
            SendStatus status = new SendStatus();
            status.setCode(ok ? "Ok" : "FailedSignature.IncorrectSignName");
            status.setMessage(reason);
            resp.setSendStatusSet(new SendStatus[]{status});
            return resp;
        }
    }
}
