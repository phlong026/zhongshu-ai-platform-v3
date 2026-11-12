package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/** 在共享body缓存和访问日志之前限长；不改变安全链的鉴权或租户规则。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@ConditionalOnProperty(name = "platform.wallet.payment.enabled", havingValue = "true")
@Slf4j
public class WalletXpayNotifyConfiguration {
    private static final int MAX_BYTES = 65536;
    private static final String ATTRIBUTE = WalletXpayNotifyConfiguration.class.getName() + ".notification";

    record Notification(Map<String, String> parameters, byte[] body) { }

    static Notification notification(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (!(value instanceof Notification notification)) throw new IllegalStateException("Notification ingress is required");
        return notification;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> walletXpayNotifyIngress() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setOrder(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER - 1);
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return !WalletXpayNotifyController.PATH.equals(path)
                        || !("GET".equals(request.getMethod()) || "POST".equals(request.getMethod()));
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Notification notification;
                try {
                    Map<String, String> parameters = queryParameters(request.getQueryString());
                    // 已有包装器同时限制Content-Length和分块请求，最多读取上限+1字节。
                    var bounded = new CacheRequestBodyWrapper(request, MAX_BYTES);
                    notification = new Notification(parameters, bounded.getInputStream().readNBytes(MAX_BYTES + 1));
                } catch (CacheRequestBodyWrapper.BodyTooLargeException failure) {
                    reject(response, 413, "size");
                    return;
                } catch (IllegalArgumentException | IOException failure) {
                    reject(response, 400, failure.getClass().getSimpleName());
                    return;
                }
                request.setAttribute(ATTRIBUTE, notification);
                // 全局非prod拦截器仍会打印请求体，即使关闭@ApiAccessLog；只向业务入口保留原文。
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override public String getQueryString() { return null; }
                    @Override public String getParameter(String name) { return null; }
                    @Override public Map<String, String[]> getParameterMap() { return Map.of(); }
                    @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
                    @Override public String[] getParameterValues(String name) { return null; }
                    @Override public int getContentLength() { return 0; }
                    @Override public long getContentLengthLong() { return 0; }
                    @Override public BufferedReader getReader() { return new BufferedReader(new StringReader("")); }
                    @Override public ServletInputStream getInputStream() {
                        return new ServletInputStream() {
                            @Override public int read() { return -1; }
                            @Override public boolean isFinished() { return true; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous notification only"); }
                        };
                    }
                }, response);
            }
        });
        return registration;
    }

    private static Map<String, String> queryParameters(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.length() > 4096) throw new IllegalArgumentException("Notification query too large");
        Map<String, String> parameters = new HashMap<>();
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1), StandardCharsets.UTF_8);
            if (parameters.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate notification parameter");
        }
        return Map.copyOf(parameters);
    }

    private static void reject(HttpServletResponse response, int status, String failureType) throws IOException {
        log.warn("[walletXpayNotify][operation=ingress failureType={}] 通知请求拒绝", failureType);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(WalletXpayNotifyController.ack(false, false));
    }
}
