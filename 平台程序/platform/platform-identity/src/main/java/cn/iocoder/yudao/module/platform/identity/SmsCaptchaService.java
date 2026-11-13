package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.module.platform.identity.sms.SmsClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
    static final long SEND_ALLOWED = 1L;
    static final long SEND_TOO_FREQUENT = -1L;
    static final long SEND_DAILY_LIMIT = -2L;
    static final long VERIFY_MATCHED = 1L;
    static final long VERIFY_WRONG = 0L;
    static final long VERIFY_EXPIRED = -1L;

    private static final DefaultRedisScript<Long> SEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[1]) == 1 then return -1 end
            local sent = tonumber(redis.call('get', KEYS[2]) or '0')
            if sent >= tonumber(ARGV[5]) then return -2 end
            redis.call('set', KEYS[1], '1', 'PX', ARGV[2])
            sent = redis.call('incr', KEYS[2])
            if sent == 1 then redis.call('pexpire', KEYS[2], ARGV[3]) end
            redis.call('set', KEYS[3], ARGV[1], 'PX', ARGV[4])
            redis.call('del', KEYS[4])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('get', KEYS[1])
            if not stored then return -1 end
            if stored == ARGV[1] then
              redis.call('del', KEYS[1], KEYS[2])
              return 1
            end
            local fails = redis.call('incr', KEYS[2])
            if fails == 1 then redis.call('pexpire', KEYS[2], ARGV[2]) end
            if fails >= tonumber(ARGV[3]) then redis.call('del', KEYS[1]) end
            return 0
            """, Long.class);

    private final SecureRandom random = new SecureRandom();

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private SmsClient smsClient;

    /** 发送登录验证码（含全部限流） */
    public void sendLoginCaptcha(String mobile) {
        String captcha = String.format("%06d", random.nextInt(1_000_000));
        String dailyKey = KEY_DAILY + mobile + ":" + LocalDate.now();
        long dailyTtlMillis = Duration.between(
                java.time.ZonedDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())).toMillis();
        Long result = redis.execute(SEND_SCRIPT,
                List.of(KEY_INTERVAL + mobile, dailyKey, KEY_CAPTCHA + mobile, KEY_FAILS + mobile),
                captcha, String.valueOf(SEND_INTERVAL.toMillis()), String.valueOf(dailyTtlMillis),
                String.valueOf(CAPTCHA_TTL.toMillis()), String.valueOf(DAILY_SEND_LIMIT));
        if (result != null && result == SEND_TOO_FREQUENT) {
            throw exception(SMS_SEND_TOO_FREQUENT);
        }
        if (result != null && result == SEND_DAILY_LIMIT) {
            throw exception(SMS_DAILY_LIMIT);
        }
        if (result == null || result != SEND_ALLOWED) {
            throw exception(SMS_SEND_FAILED, "发送状态存储失败");
        }
        // 先落 Redis 再走通道：通道失败时验证码仍在但用户没收到，重发受 60s 限流保护（防通道风暴）
        smsClient.sendLoginCaptcha(mobile, captcha);
        log.info("[sendLoginCaptcha][mobile={}] 登录验证码已发送", mask(mobile));
    }

    /** 校验并消费验证码；通过返回 true 并立即失效，失败抛业务异常并计数 */
    public void verifyLoginCaptcha(String mobile, String captcha) {
        Long result = redis.execute(VERIFY_SCRIPT, List.of(KEY_CAPTCHA + mobile, KEY_FAILS + mobile),
                captcha, String.valueOf(CAPTCHA_TTL.toMillis()), String.valueOf(MAX_VERIFY_FAILS));
        if (result == null || result == VERIFY_EXPIRED) {
            throw exception(SMS_CAPTCHA_EXPIRED);
        }
        if (result == VERIFY_WRONG) {
            throw exception(SMS_CAPTCHA_WRONG);
        }
    }

    /** 读取最近一次验证码。仅供 local profile 的开发出码接口与端到端脚本使用，生产不存在调用方 */
    public String peekRecentCaptcha(String mobile) {
        return redis.opsForValue().get(KEY_CAPTCHA + mobile);
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

}
