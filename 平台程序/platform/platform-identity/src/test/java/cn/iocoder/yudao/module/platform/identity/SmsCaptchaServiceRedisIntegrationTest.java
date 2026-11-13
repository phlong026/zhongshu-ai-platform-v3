package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.sms.SmsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.SMS_CAPTCHA_EXPIRED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.SMS_DAILY_LIMIT;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.SMS_SEND_TOO_FREQUENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** 仅在 -Dzs.test.redis-port 显式提供隔离 Redis 时运行，验证 Lua 在真实 Redis 上的并发语义。 */
class SmsCaptchaServiceRedisIntegrationTest {

    private RedissonConnectionFactory factory;
    private StringRedisTemplate redis;
    private SmsCaptchaService service;

    @BeforeEach
    void setUp() {
        String configuredPort = System.getProperty("zs.test.redis-port");
        assumeTrue(configuredPort != null, "requires isolated Redis via -Dzs.test.redis-port");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + Integer.parseInt(configuredPort));
        factory = new RedissonConnectionFactory(config);
        try {
            factory.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        service = new SmsCaptchaService();
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "smsClient", mock(SmsClient.class));
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            try {
                factory.destroy();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void concurrentSendAllowsExactlyOneAndRejectedRequestsDoNotIncrementDailyCount() throws Exception {
        String mobile = "13800007777";
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.sendLoginCaptcha(mobile);
                        return 0;
                    } catch (ServiceException e) {
                        return e.getCode();
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Integer> results = futures.stream().map(this::get).toList();
            assertThat(results).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(0),
                                    java.util.stream.Stream.generate(() -> SMS_SEND_TOO_FREQUENT.getCode()).limit(19))
                            .toList());
            assertThat(redis.opsForValue().get(SmsCaptchaService.KEY_DAILY + mobile + ":" + java.time.LocalDate.now()))
                    .isEqualTo("1");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentMobilesHaveIndependentLimitsAndDailyLimitDoesNotOvercount() {
        String first = "13800008880";
        String second = "13800008881";
        service.sendLoginCaptcha(first);
        service.sendLoginCaptcha(second);
        assertThat(service.peekRecentCaptcha(first)).isNotBlank();
        assertThat(service.peekRecentCaptcha(second)).isNotBlank();

        String limited = "13800008882";
        for (int i = 0; i < SmsCaptchaService.DAILY_SEND_LIMIT; i++) {
            service.sendLoginCaptcha(limited);
            redis.delete(SmsCaptchaService.KEY_INTERVAL + limited);
        }
        assertThatThrownBy(() -> service.sendLoginCaptcha(limited))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(SMS_DAILY_LIMIT.getCode()));
        assertThat(redis.opsForValue().get(SmsCaptchaService.KEY_DAILY + limited + ":" + java.time.LocalDate.now()))
                .isEqualTo(String.valueOf(SmsCaptchaService.DAILY_SEND_LIMIT));
    }

    @Test
    void successfulVerificationConsumesCaptchaExactlyOnce() {
        String mobile = "13800009990";
        service.sendLoginCaptcha(mobile);
        String captcha = service.peekRecentCaptcha(mobile);

        service.verifyLoginCaptcha(mobile, captcha);
        assertThatThrownBy(() -> service.verifyLoginCaptcha(mobile, captcha))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(SMS_CAPTCHA_EXPIRED.getCode()));
    }

    private int get(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
