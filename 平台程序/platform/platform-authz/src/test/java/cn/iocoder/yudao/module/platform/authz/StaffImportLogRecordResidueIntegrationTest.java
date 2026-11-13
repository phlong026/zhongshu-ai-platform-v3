package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.operatelog.config.YudaoOperateLogConfiguration;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.infra.api.config.ConfigApi;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffImportService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.YudaoSystemDirectory;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.enums.LogRecordConstants;
import cn.iocoder.yudao.module.system.mq.producer.user.AdminUserProducer;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.dept.PostService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.permission.MenuService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * N-P2-1（窄审必修）：真实路径事实测试 —— 走 YudaoSystemDirectory → AdminUserServiceImpl.createUser(@LogRecord)
 * → LogRecordServiceImpl → OperateLogCommonApi（同步捕获入参），构造“建号后任职失败”行，
 * 锁定事实：创建用户操作日志在行回滚后仍有审计记录（残留）。
 *
 * <p>事实结论（登记 manifest N-P2-1）：捕获到 2 条“创建用户”记录，其中 1 条指向已回滚用户
 * （system_users 零残留）→ 审计残留成立；后续批建议“事务提交后记录/导入批次表”。
 */
@Import({cn.iocoder.yudao.module.platform.org.OrgServiceImpl.class,
        cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl.class,
        YudaoSystemDirectory.class,
        cn.iocoder.yudao.module.platform.org.StaffImportService.class,
        AdminUserServiceImpl.class,
        YudaoOperateLogConfiguration.class,
        StaffImportLogRecordResidueIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.legacy-design-bridge.enabled=false")
class StaffImportLogRecordResidueIntegrationTest extends PlatformPgIntegrationTest {

    private static final String ORG_TYPE = "imp-aud-store";
    private static final String ROLE = "imp-aud-sales";

    @TestConfiguration
    static class Config {

        @Bean
        public OperateLogCommonApi operateLogCommonApi() {
            return mock(OperateLogCommonApi.class);
        }

        @Bean
        public DeptService deptService() {
            DeptService deptService = mock(DeptService.class);
            org.mockito.Mockito.doAnswer(inv -> null)
                    .when(deptService).validateDeptList(org.mockito.ArgumentMatchers.any());
            return deptService;
        }

        @Bean
        public PostService postService() {
            PostService postService = mock(PostService.class);
            org.mockito.Mockito.doAnswer(inv -> null)
                    .when(postService).validatePostList(org.mockito.ArgumentMatchers.any());
            return postService;
        }

        @Bean
        public PermissionService permissionService() {
            return mock(PermissionService.class);
        }

        @Bean
        public RoleService roleService() {
            return mock(RoleService.class);
        }

        @Bean
        public MenuService menuService() {
            return mock(MenuService.class);
        }

        @Bean
        public TenantService tenantService() {
            return mock(TenantService.class);
        }

        @Bean
        public OAuth2TokenService oauth2TokenService() {
            return mock(OAuth2TokenService.class);
        }

        @Bean
        public ConfigApi configApi() {
            return mock(ConfigApi.class);
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new PasswordEncoder() {
                @Override
                public String encode(CharSequence rawPassword) {
                    return "encoded";
                }

                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    return true;
                }
            };
        }

        @Bean
        public AdminUserProducer adminUserProducer() {
            return mock(AdminUserProducer.class);
        }

        @Bean
        public ApplicationEventPublisher eventPublisher() {
            return mock(ApplicationEventPublisher.class);
        }
    }

    @Resource
    private StaffImportService importService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private OrgService orgService;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private OperateLogCommonApi operateLogApi;
    @Resource
    private RoleService roleService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private DeptService deptService;

    private final Map<Long, String> deptNamesById = new LinkedHashMap<>();

    private long orgId;
    private long teamId;

    @BeforeEach
    void setUp() {
        // system_users 最小真实表（列集合按 AdminUserDO 映射；非生产 schema，仅测试库）
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users ("
                + "id bigserial PRIMARY KEY, username varchar(64), nickname varchar(64),"
                + "mobile varchar(16), dept_id bigint, password varchar(100), status int2 DEFAULT 0,"
                + "post_ids varchar(255), email varchar(64), sex int2, avatar varchar(512),"
                + "login_ip varchar(64), login_date timestamp, remark varchar(255),"
                + "creator varchar(64), create_time timestamp DEFAULT now(),"
                + "updater varchar(64), update_time timestamp DEFAULT now(),"
                + "deleted int2 DEFAULT 0, tenant_id bigint DEFAULT 0)");
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS system_users_seq");
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES (?, 'test', '门店') ON CONFLICT DO NOTHING",
                ORG_TYPE);
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) "
                + "VALUES (?, 'test', ?, 'sales', FALSE, NULL) ON CONFLICT DO NOTHING", ROLE, ORG_TYPE);
        // deptService 桩：createDept 发号并记录名称；getDept 读回（deptName 解析团队名用）
        java.util.concurrent.atomic.AtomicLong deptSeq =
                new java.util.concurrent.atomic.AtomicLong(70_000_000_000L + System.nanoTime() % 100_000_000L);
        org.mockito.Mockito.when(deptService.createDept(org.mockito.ArgumentMatchers.any(
                        cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO.class)))
                .thenAnswer(inv -> {
                    cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO req =
                            inv.getArgument(0);
                    long deptId = deptSeq.incrementAndGet();
                    deptNamesById.put(deptId, req.getName());
                    return deptId;
                });
        org.mockito.Mockito.when(deptService.getDept(anyLong()))
                .thenAnswer(inv -> {
                    DeptDO dept = new DeptDO();
                    dept.setId(inv.getArgument(0));
                    dept.setName(deptNamesById.getOrDefault(inv.getArgument(0, Long.class), ""));
                    return dept;
                });
        RoleDO role = new RoleDO();
        role.setId(701L);
        role.setCode(ROLE);
        org.mockito.Mockito.when(roleService.getRoleList()).thenReturn(List.of(role));
        orgId = orgService.createOrg(ORG_TYPE, "导入审计门店" + System.nanoTime(), null);
        teamId = orgService.createTeam(orgId, null, "业务一组", "sales");
    }

    @Test
    void createUserOperateLogResidueIsRecordedEvenWhenRowRollsBack() {
        // 第 2 行在“建号+任职+角色同步”之后经 syncUserRoles 抛出（真实路径）→ 整行事务回滚
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("assign user role failed"))
                .when(permissionService).assignUserRole(
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        var result = importService.submit(orgId, List.of(
                new StaffImportService.Row(1, "张三", "13800138021", ROLE, "业务一组"),
                new StaffImportService.Row(2, "李四", "13800138022", ROLE, "业务一组")), 99L);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(0).status()).isEqualTo("CREATED");
        assertThat(result.rows().get(1).status()).isEqualTo("FAILED");
        assertThat(result.rows().get(1).code()).isEqualTo(500);
        assertThat(result.rows().get(1).message()).isEqualTo("该行处理失败，请稍后重试");
        // ① 失败行：建号在行事务内回滚（system_users 零残留）；② 成功行保留（逐行独立事务）
        assertThat(userCount("13800138021")).isEqualTo(1);
        assertThat(userCount("13800138022")).isZero();
        assertThat(staffPostService.listOrgPosts(orgId)).hasSize(1);

        // 事实锁定：创建用户操作日志已发出 2 条（含已回滚用户那条）→ 审计残留成立
        ArgumentCaptor<OperateLogCreateReqDTO> captor =
                ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(operateLogApi, atLeastOnce()).createOperateLogAsync(captor.capture());
        List<OperateLogCreateReqDTO> createLogs = captor.getAllValues().stream()
                .filter(dto -> LogRecordConstants.SYSTEM_USER_CREATE_SUB_TYPE.equals(dto.getSubType()))
                .toList();
        assertThat(createLogs).hasSize(2);
        assertThat(createLogs.stream().map(OperateLogCreateReqDTO::getBizId))
                .contains(staffPostService.listOrgPosts(orgId).get(0).getUserId());
    }

    private int userCount(String mobile) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM system_users WHERE mobile = ?",
                Integer.class, mobile);
        return count == null ? 0 : count;
    }
}
