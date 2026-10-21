package cn.iocoder.yudao.module.platform.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 员工登录接入的小程序。只有这里登记的 AppID 能登录；secret 只存于服务端配置（部署时由环境变量注入）。
 */
@ConfigurationProperties(prefix = "platform.identity.wechat")
@Data
public class PlatformWechatProperties {

    /** real：直连微信；stub：本地联调替身（禁止在 prod 配置下启用） */
    private String provider = "real";
    private String baseUrl = "https://api.weixin.qq.com";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 5000;
    private List<App> apps = List.of();

    public boolean allows(String appid) {
        return appid != null && !appid.isBlank() && apps.stream().anyMatch(a -> appid.equals(a.getAppid()));
    }

    public Optional<String> secretOf(String appid) {
        return apps.stream().filter(a -> Objects.equals(appid, a.getAppid()))
                .map(App::getSecret).filter(s -> s != null && !s.isBlank()).findFirst();
    }

    @Data
    public static class App {
        private String appid;
        private String secret;

        @Override
        public String toString() { // 防止配置打印时带出 secret
            return "App(appid=" + appid + ")";
        }
    }

}
