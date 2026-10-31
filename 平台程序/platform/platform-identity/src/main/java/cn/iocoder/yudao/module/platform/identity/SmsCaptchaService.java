package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.module.platform.identity.sms.SmsClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

/**
 * 登录短信验证码：生成、发送限流、校验，全部走 Redis（一次性、短时效，不落库）。
 * 规则对标同行通行做法：5 分钟有效；同一手机号 60 秒一条、每日至多 10 条；
 * 校验错误 5 次即作废重发；校验成功立即失效。
 */
@Service
@Slf4j
public class SmsCaptchaService {

    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    static final int DAILY_SEND_LIMIT = 10;
    static final int MAX_VERIFY_FAILS = 5;

    static final String KEY_CAPTCHA = "platform:sms:captcha:";
    static final String KEY_FAILS = "platform:sms:captcha:fails:";
    static final String KEY_INTERVAL = "platform:sms:send:interval:";
    static final String KEY_DAILY = "platform:sms:send:daily:";

    private final SecureRandom random = new SecureRandom();

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private SmsClient smsClient;

    /** 发送登录验证码（含全部限流） */
    public void sendLoginCaptcha(String mobile) {
        if (Boolean.TRUE.equals(redis.hasKey(KEY_INTERVAL + mobile))) {
            throw exception(SMS_SEND_TOO_FREQUENT);
        }
        String dailyKey = KEY_DAILY + mobile + ":" + java.time.LocalDate.now();
        Long sent = redis.opsForValue().increment(dailyKey);
        redis.expire(dailyKey, Duration.ofDays(1));
        if (sent != null && sent > DAILY_SEND_LIMIT) {
            throw exception(SMS_DAILY_LIMIT);
        }

        String captcha = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(KEY_CAPTCHA + mobile, captcha, CAPTCHA_TTL);
        redis.delete(KEY_FAILS + mobile);
        redis.opsForValue().set(KEY_INTERVAL + mobile, "1", SEND_INTERVAL);
        // 先落 Redis 再走通道：通道失败时验证码仍在但用户没收到，重发受 60s 限流保护（防通道风暴）
        smsClient.sendLoginCaptcha(mobile, captcha);
        log.info("[sendLoginCaptcha][mobile={}] 登录验证码已发送", mask(mobile));
    }

    /** 校验并消费验证码；通过返回 true 并立即失效，失败抛业务异常并计数 */
    public void verifyLoginCaptcha(String mobile, String captcha) {
        String stored = redis.opsForValue().get(KEY_CAPTCHA + mobile);
        if (stored == null) {
            throw exception(SMS_CAPTCHA_EXPIRED);
        }
        if (!stored.equals(captcha)) {
            Long fails = redis.opsForValue().increment(KEY_FAILS + mobile);
            redis.expire(KEY_FAILS + mobile, CAPTCHA_TTL);
            if (fails != null && fails >= MAX_VERIFY_FAILS) {
                redis.delete(KEY_CAPTCHA + mobile);
                log.warn("[verifyLoginCaptcha][mobile={}] 验证码连续错误 {} 次，已作废", mask(mobile), fails);
            }
            throw exception(SMS_CAPTCHA_WRONG);
        }
        redis.delete(KEY_CAPTCHA + mobile);
        redis.delete(KEY_FAILS + mobile);
    }

    /** 读取最近一次验证码。仅供 local profile 的开发出码接口与端到端脚本使用，生产不存在调用方 */
    public String peekRecentCaptcha(String mobile) {
        return redis.opsForValue().get(KEY_CAPTCHA + mobile);
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

}
