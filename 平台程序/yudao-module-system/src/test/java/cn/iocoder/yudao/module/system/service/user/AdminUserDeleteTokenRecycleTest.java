package cn.iocoder.yudao.module.system.service.user;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.infra.api.config.ConfigApi;
import cn.iocoder.yudao.module.infra.api.file.FileApi;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.event.AdminUserDeletedEvent;
import cn.iocoder.yudao.module.system.event.AdminUserDeletedTokenCleaner;
import cn.iocoder.yudao.module.system.mq.producer.user.AdminUserProducer;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.dept.PostService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2ClientService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenServiceImpl;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * M01-T01「删除后重放失败」真实链验证（Codex R1 P2-5 / R2 P1-P2 收口）：
 * 真实 OAuth2TokenServiceImpl（非 mock）签发 access+配对 refresh → 真实删除用户 →
 * 库内令牌行清空、checkAccessToken 重放拒绝；并用有状态缓存副本验证「并发校验迟到回填缓存」
 * 的键级清理（提交后按 userId 查库空转，须按删除前捕获的精确键删除）。
 */
@Import({AdminUserServiceImpl.class, OAuth2TokenServiceImpl.class,
        AdminUserDeleteTokenRecycleTest.Config.class, AdminUserDeletedTokenCleaner.class})
public class AdminUserDeleteTokenRecycleTest extends BaseDbUnitTest {

    @Resource
    private AdminUserServiceImpl userService;
    @Resource
    private OAuth2TokenServiceImpl oauth2TokenService;
    @Resource
    private AdminUserDeletedTokenCleaner tokenCleaner;
    @Resource
    private AdminUserMapper userMapper;
    @Resource
    private OAuth2AccessTokenMapper oauth2AccessTokenMapper;
    @Resource
    private OAuth2RefreshTokenMapper oauth2RefreshTokenMapper;
    @Resource
    private FakeTokenRedis fakeTokenRedis;

    @MockitoBean
    private DeptService deptService;
    @MockitoBean
    private PostService postService;
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private PasswordEncoder passwordEncoder;
    @MockitoBean
    private TenantService tenantService;
    @MockitoBean
    private FileApi fileApi;
    @MockitoBean
    private ConfigApi configApi;
    @MockitoBean
    private AdminUserProducer adminUserProducer;
    @MockitoBean
    private OAuth2ClientService oauth2ClientService;
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate; // 父类 @Resource 字段注入占位，fake 覆写不触达

    /** 有状态缓存副本：模拟真实 Redis 的迟到回填与键级删除（mock 的 set/get 无记忆，测不出 R2 P1） */
    public static class FakeTokenRedis extends OAuth2AccessTokenRedisDAO {
        final Map<String, OAuth2AccessTokenDO> store = new ConcurrentHashMap<>();
        /** 非空时：该键被 delete 后立即回写（模拟并发校验在删库删缓存后、提交前的迟到回填——只有提交后键级清理能拔除） */
        volatile OAuth2AccessTokenDO latePoisonAfterDelete;

        @Override
        public OAuth2AccessTokenDO get(String accessToken) {
            return store.get(accessToken);
        }

        @Override
        public void set(OAuth2AccessTokenDO accessTokenDO) {
            store.put(accessTokenDO.getAccessToken(), accessTokenDO);
        }

        @Override
        public void delete(String accessToken) {
            store.remove(accessToken);
            OAuth2AccessTokenDO poison = latePoisonAfterDelete;
            if (poison != null && poison.getAccessToken().equals(accessToken)) {
                store.put(accessToken, poison); // 迟到回填（一次性：模拟单个竞态读-写者，仅提交后键级清理能拔除）
                latePoisonAfterDelete = null;
            }
        }

        @Override
        public void deleteList(Collection<String> accessTokens) {
            accessTokens.forEach(store::remove);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeTokenRedis fakeTokenRedis() {
            return new FakeTokenRedis();
        }
    }

    @Test
    public void testDisableUserInvalidatesTokensViaAfterCommitCleaner() {
        // M01-T02（Codex R1 P1 收口）：updateUserStatus 须有事务使 AFTER_COMMIT 键级清理真实执行——
        // 真实令牌服务签发→全局停用→缓存键被提交后监听拔除→重放拒绝（mock redis 测不出缺事务）
        TenantContextHolder.setTenantId(0L);
        AdminUserDO user = randomPojo(AdminUserDO.class).setSex(1).setStatus(0);
        userMapper.insert(user);
        String clientId = "m01t02-client";
        OAuth2ClientDO client = randomPojo(OAuth2ClientDO.class).setClientId(clientId)
                .setAccessTokenValiditySeconds(1800).setRefreshTokenValiditySeconds(3600);
        when(oauth2ClientService.validOAuthClientFromCache(eq(clientId))).thenReturn(client);
        OAuth2AccessTokenDO accessTokenDO = oauth2TokenService.createAccessToken(
                user.getId(), UserTypeEnum.ADMIN.getValue(), clientId, List.of("read"));
        fakeTokenRedis.set(accessTokenDO);
        // 提交前迟到回填（Codex R2 P2）：删除缓存后立即毒化——仅 AFTER_COMMIT 键级清理能拔除；
        // 缺 @Transactional 时监听不触发、毒化残留、本断言红，测试具备判别力
        fakeTokenRedis.latePoisonAfterDelete = accessTokenDO;

        userService.updateUserStatus(user.getId(), CommonStatusEnum.DISABLE.getStatus());

        assertNull(oauth2AccessTokenMapper.selectByAccessToken(accessTokenDO.getAccessToken()));
        assertNull(fakeTokenRedis.get(accessTokenDO.getAccessToken()), "停用提交后键级清理须已拔除迟到回填的缓存");
        assertThrows(ServiceException.class, () -> oauth2TokenService.checkAccessToken(accessTokenDO.getAccessToken()));
    }

    @Test
    public void testDeleteUserInvalidatesIssuedTokensAndReplayFails() {
        TenantContextHolder.setTenantId(0L);
        // 1. 建真实用户
        AdminUserDO user = randomPojo(AdminUserDO.class).setSex(1).setStatus(0); // sex/status 为 tinyint，随机值越界
        userMapper.insert(user);

        // 2. 真实签发 access + 配对 refresh（真实令牌服务，客户端配置 mock）
        String clientId = "m01t01-client";
        OAuth2ClientDO client = randomPojo(OAuth2ClientDO.class).setClientId(clientId)
                .setAccessTokenValiditySeconds(1800).setRefreshTokenValiditySeconds(3600);
        when(oauth2ClientService.validOAuthClientFromCache(eq(clientId))).thenReturn(client);
        OAuth2AccessTokenDO accessTokenDO = oauth2TokenService.createAccessToken(
                user.getId(), UserTypeEnum.ADMIN.getValue(), clientId, List.of("read"));
        assertNotNull(oauth2AccessTokenMapper.selectByAccessToken(accessTokenDO.getAccessToken()));
        assertEquals(1, oauth2RefreshTokenMapper.selectList().size());
        assertNotNull(fakeTokenRedis.get(accessTokenDO.getAccessToken()));

        // 3. 真实删除用户（真实令牌服务走库删除；提交后 AdminUserDeletedTokenCleaner 按捕获键清理缓存）
        userService.deleteUser(user.getId());

        // 4. 重放失败：access 行与配对 refresh 行均被删除，checkAccessToken 拒绝
        assertNull(oauth2AccessTokenMapper.selectByAccessToken(accessTokenDO.getAccessToken()));
        assertEquals(0, oauth2RefreshTokenMapper.selectList().size());
        assertThrows(ServiceException.class, () -> oauth2TokenService.checkAccessToken(accessTokenDO.getAccessToken()));
        assertNull(fakeTokenRedis.get(accessTokenDO.getAccessToken()), "删除事务内的清理须已拔除缓存");

        // 5. 并发交错模拟：删除事务期间某次校验读到未提交旧行，在提交后迟到回填缓存（Codex R2 P1 场景）
        fakeTokenRedis.set(accessTokenDO);
        // 按精确键清理须能拔掉这枚毒化缓存——按 userId 查库（已空集）会静默跳过
        tokenCleaner.onUserDeleted(new AdminUserDeletedEvent(List.of(user.getId()),
                List.of(accessTokenDO.getAccessToken(), accessTokenDO.getRefreshToken())));
        assertNull(fakeTokenRedis.get(accessTokenDO.getAccessToken()), "迟到回填的缓存必须被键级清理拔除");
        assertThrows(ServiceException.class, () -> oauth2TokenService.checkAccessToken(accessTokenDO.getAccessToken()));
    }
}
