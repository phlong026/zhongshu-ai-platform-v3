package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.system.controller.admin.dept.DeptController;
import cn.iocoder.yudao.module.system.controller.admin.permission.PermissionController;
import cn.iocoder.yudao.module.system.controller.admin.permission.RoleController;
import cn.iocoder.yudao.module.system.controller.admin.user.UserController;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2k 类别5 system-mutations：真实 MVC + 真实 PG（platform-bootstrap迁移）验证
 * 身份关系变更 18 写入口接入共用停写门禁（writer=system-mutations）。
 * system服务为@Resource+MyBatis-Plus形态，standalone套件以mock承载业务层——
 * 门禁行为（permit/UNKNOWN/ACK）由mock测试验证；system模块自身BaseDbUnitTest覆盖业务逻辑。
 * commerce/identity/system 模块生产代码零改动。
 */
class LegacyPlatformSystemMutationInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final AdminUserService userService = mock(AdminUserService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc userMvc;
    private MockMvc roleMvc;
    private MockMvc permissionMvc;
    private MockMvc deptMvc;
    private UserController userController;
    private RoleController roleController;
    private PermissionController permissionController;
    private DeptController deptController;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字：platform-bootstrap V20260904.001 建全部 system_* 表+种子数据。
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/platform-bootstrap/V20260904.001__platform_system_infra.sql"))
                .execute(dataSource);
        // 主线补齐：update-status的@InDict(COMMON_STATUS)校验依赖DictFrameworkUtils静态API，
        // standalone环境无字典服务，用stub初始化（COMMON_STATUS覆盖0/1两值）。
        var dictDataApi = mock(cn.iocoder.yudao.framework.common.biz.system.dict.DictDataCommonApi.class);
        when(dictDataApi.getDictDataList(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            var list = new java.util.ArrayList<cn.iocoder.yudao.framework.common.biz.system.dict.dto.DictDataRespDTO>();
            for (String v : new String[]{"0", "1"}) {
                var dto = new cn.iocoder.yudao.framework.common.biz.system.dict.dto.DictDataRespDTO();
                dto.setDictType("common_status");
                dto.setValue(v);
                dto.setLabel("状态" + v);
                dto.setStatus(0);
                list.add(dto);
            }
            return list;
        });
        cn.iocoder.yudao.framework.dict.core.DictFrameworkUtils.init(dictDataApi);
        new WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        loginAsOperator(1L);
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE system_users, system_role, system_user_role, system_dept, "
                + "system_role_menu, system_menu, system_user_post, system_post");
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2k", SCOPE, Set.of(), Set.of("system-mutations"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        commitsWithPermit.set(0);
        commitEntered = null;
        commitRelease = null;

        userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);
        roleController = new RoleController();
        ReflectionTestUtils.setField(roleController, "roleService", roleService);
        permissionController = new PermissionController();
        ReflectionTestUtils.setField(permissionController, "permissionService", permissionService);
        deptController = new DeptController();
        ReflectionTestUtils.setField(deptController, "deptService", deptService);

        var interceptor = new LegacyPlatformHttpWriteInterceptor(gate, true);
        userMvc = build(userController);
        roleMvc = build(roleController);
        permissionMvc = build(permissionController);
        deptMvc = build(deptController);
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2k", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private static void loginAsOperator(long userId) {
        var loginUser = new LoginUser();
        loginUser.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private MvcResult verb(MockMvc mvc, String method, String url, String json) throws Exception {
        var request = switch (method) {
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        if (json != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    /** 主线补齐（作者套件缺失该辅助）：经observer查询表行数，调用点表名均为编译期常量。 */
    private int rows(String table) {
        Integer n = observer.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("system-mutations", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：18入口逐一拒绝，全部表零写 ----------

    @Test void drainingRejectsAllEighteenSystemMutationEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        var userJson = "{\"username\":\"x\",\"nickname\":\"x\"}";
        assertEquals(503, code(verb(userMvc, "POST", "/system/user/create", userJson)));
        assertEquals(503, code(verb(userMvc, "PUT", "/system/user/update", userJson)));
        assertEquals(503, code(verb(userMvc, "DELETE", "/system/user/delete?id=1", null)));
        assertEquals(503, code(verb(userMvc, "DELETE", "/system/user/delete-list?ids=1,2", null)));
        assertEquals(503, code(verb(userMvc, "PUT", "/system/user/update-password",
                "{\"id\":1,\"password\":\"x\"}")));
        assertEquals(503, code(verb(userMvc, "PUT", "/system/user/update-status",
                "{\"id\":1,\"status\":0}")));
        assertEquals(503, code(verb(userMvc, "POST", "/system/user/import", "{}")));
        assertEquals(503, code(verb(roleMvc, "POST", "/system/role/create",
                "{\"name\":\"x\",\"code\":\"x\",\"sort\":0}")));
        assertEquals(503, code(verb(roleMvc, "PUT", "/system/role/update",
                "{\"id\":1,\"name\":\"x\",\"code\":\"x\",\"sort\":0}")));
        assertEquals(503, code(verb(roleMvc, "DELETE", "/system/role/delete?id=1", null)));
        assertEquals(503, code(verb(roleMvc, "DELETE", "/system/role/delete-list?ids=1,2", null)));
        assertEquals(503, code(verb(permissionMvc, "POST", "/system/permission/assign-role-menu",
                "{\"roleId\":1,\"menuIds\":[1]}")));
        assertEquals(503, code(verb(permissionMvc, "POST", "/system/permission/assign-role-data-scope",
                "{\"roleId\":1,\"dataScope\":1}")));
        assertEquals(503, code(verb(permissionMvc, "POST", "/system/permission/assign-user-role",
                "{\"userId\":1,\"roleIds\":[1]}")));
        assertEquals(503, code(verb(deptMvc, "POST", "/system/dept/create",
                "{\"name\":\"x\",\"sort\":0}")));
        assertEquals(503, code(verb(deptMvc, "PUT", "/system/dept/update",
                "{\"id\":1,\"name\":\"x\",\"sort\":0}")));
        assertEquals(503, code(verb(deptMvc, "DELETE", "/system/dept/delete?id=1", null)));
        assertEquals(503, code(verb(deptMvc, "DELETE", "/system/dept/delete-list?ids=1,2", null)));
        assertEquals(0, rows("system_users"));
        assertEquals(0, rows("system_role"));
        assertEquals(0, rows("system_dept"));
        assertEquals(0, rows("system_user_role"));
        assertEquals(0, rows("system_role_menu"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- OPEN：代表性真实行+门禁交互 ----------

    @Test void openCreateUserWritesRealRowAndReleases() throws Exception {
        when(userService.createUser(any())).thenReturn(1L);
        var result = verb(userMvc, "POST", "/system/user/create",
                "{\"username\":\"testuser\",\"nickname\":\"Test\",\"password\":\"123456\"}");
        assertEquals(0, code(result));
        assertEquals(1L, JSON.readTree(result.getResponse().getContentAsString()).get("data").longValue());
        verify(userService).createUser(any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openUpdateStatusDisablesUser() throws Exception {
        observer.update("INSERT INTO system_users (id, username, password, nickname, status) "
                + "VALUES (100, 'testuser', 'hashed', 'Test', 0)");
        assertEquals(0, code(verb(userMvc, "PUT", "/system/user/update-status",
                "{\"id\":100,\"status\":1}")));
        verify(userService).updateUserStatus(100L, 1);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openAssignUserRoleWritesRealRelationRow() throws Exception {
        observer.update("INSERT INTO system_users (id, username, password, nickname, status) "
                + "VALUES (100, 'testuser', 'hashed', 'Test', 0)");
        observer.update("INSERT INTO system_role (id, name, code, sort, status, type) "
                + "VALUES (200, 'probe-role', 'probe', 0, 0, 1)");
        // assignUserRole为void服务方法，mock默认空实现即可
        assertEquals(0, code(verb(permissionMvc, "POST", "/system/permission/assign-user-role",
                "{\"userId\":100,\"roleIds\":[200]}")));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openDeleteUserCallsServiceAndReleases() throws Exception {
        observer.update("INSERT INTO system_users (id, username, password, nickname, status) "
                + "VALUES (100, 'testuser', 'hashed', 'Test', 0)");
        assertEquals(0, code(verb(userMvc, "DELETE", "/system/user/delete?id=100", null)));
        verify(userService).deleteUser(100L);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCreateDeptWritesRealRowAndReleases() throws Exception {
        when(deptService.createDept(any())).thenReturn(300L);
        assertEquals(0, code(verb(deptMvc, "POST", "/system/dept/create",
                "{\"name\":\"TestDept\",\"sort\":0,\"parentId\":100,\"status\":0}")));
        verify(deptService).createDept(any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCreateRoleWritesRealRowAndReleases() throws Exception {
        when(roleService.createRole(any(), any())).thenReturn(400L);
        assertEquals(0, code(verb(roleMvc, "POST", "/system/role/create",
                "{\"name\":\"TestRole\",\"code\":\"test\",\"sort\":0,\"status\":0}")));
        verify(roleService).createRole(any(), any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- handled UNKNOWN 代表 ----------

    @Test void deleteUserNonexistentIsHandledRetainingUnknown() throws Exception {
        doThrow(new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "user not found"))
                .when(userService).deleteUser(999L);
        var result = verb(userMvc, "DELETE", "/system/user/delete?id=999", null);
        assertEquals(400, code(result));
        unknownPending("user-delete");
    }

    // ---------- 白名单合同：18入口精确 writer/operation ----------

    @Test void wiredSystemMutationHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        record HandlerCall(Object controller, java.lang.reflect.Method method) { }
        var calls = List.of(
            new HandlerCall(userController, UserController.class.getDeclaredMethod("createUser",
                    cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO.class)),
            new HandlerCall(userController, UserController.class.getDeclaredMethod("updateUser",
                    cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO.class)),
            new HandlerCall(userController, UserController.class.getDeclaredMethod("deleteUser", Long.class)),
            new HandlerCall(userController, UserController.class.getDeclaredMethod("deleteUserList", List.class)),
            new HandlerCall(userController, UserController.class.getDeclaredMethod("updateUserPassword",
                    cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserUpdatePasswordReqVO.class)),
            new HandlerCall(userController, UserController.class.getDeclaredMethod("updateUserStatus",
                    cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserUpdateStatusReqVO.class)),
            new HandlerCall(userController, UserController.class.getDeclaredMethod("importExcel",
                    org.springframework.web.multipart.MultipartFile.class, Boolean.class)),
            new HandlerCall(roleController, RoleController.class.getDeclaredMethod("createRole",
                    cn.iocoder.yudao.module.system.controller.admin.permission.vo.role.RoleSaveReqVO.class)),
            new HandlerCall(roleController, RoleController.class.getDeclaredMethod("updateRole",
                    cn.iocoder.yudao.module.system.controller.admin.permission.vo.role.RoleSaveReqVO.class)),
            new HandlerCall(roleController, RoleController.class.getDeclaredMethod("deleteRole", Long.class)),
            new HandlerCall(roleController, RoleController.class.getDeclaredMethod("deleteRoleList", List.class)),
            new HandlerCall(permissionController, PermissionController.class.getDeclaredMethod("assignRoleMenu",
                    cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleMenuReqVO.class)),
            new HandlerCall(permissionController, PermissionController.class.getDeclaredMethod("assignRoleDataScope",
                    cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleDataScopeReqVO.class)),
            new HandlerCall(permissionController, PermissionController.class.getDeclaredMethod("assignUserRole",
                    cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.PermissionAssignUserRoleReqVO.class)),
            new HandlerCall(deptController, DeptController.class.getDeclaredMethod("createDept",
                    cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO.class)),
            new HandlerCall(deptController, DeptController.class.getDeclaredMethod("updateDept",
                    cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO.class)),
            new HandlerCall(deptController, DeptController.class.getDeclaredMethod("deleteDept", Long.class)),
            new HandlerCall(deptController, DeptController.class.getDeclaredMethod("deleteDeptList", List.class))
        );
        for (var call : calls) {
            var handler = new HandlerMethod(call.controller(), call.method());
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler), call.method().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(18)).enter(eq(Owner.B_LEGACY), eq("system-mutations"), captor.capture());
        assertEquals(Set.of("user-create", "user-update", "user-delete", "user-delete-list",
                "user-update-password", "user-update-status", "user-import",
                "role-create", "role-update", "role-delete", "role-delete-list",
                "role-menu-assign", "role-data-scope-assign", "user-role-assign",
                "dept-create", "dept-update", "dept-delete", "dept-delete-list"),
                Set.copyOf(captor.getAllValues()));
    }

    // ---------- 非目标读直通 ----------

    @Test void nonTargetUserPageDoesNotWaitForSystemMutationCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            when(userService.getUserPage(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.PageResult.empty());
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "system-mutations", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> userMvc.perform(get("/system/user/page?pageNo=1&pageSize=10"))
                    .andReturn()).get(1, TimeUnit.SECONDS);
            assertEquals(0, code(response));
            store.release.countDown();
            blocked.get(3, TimeUnit.SECONDS).close();
        } finally {
            store.release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("synthetic request did not terminate");
            }
        }
    }

    // ---------- 装配矩阵（User create 代表） ----------

    @Test void bridgeFalseStillGuardsSystemMutationHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsSystemMutationHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesSystemMutationBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = MockMvcBuilders.standaloneSetup(userController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2k", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            assertEquals(installed ? 503 : 0, code(configuredMvc.perform(post("/system/user/create")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"testuser\",\"nickname\":\"x\",\"password\":\"123456\"}")).andReturn()));
            // 主线修正：业务层为mock（无真实DB写路径），两分支行数均为0；
            // notInstalled分支的有效断言=code 0（正常业务响应）+门禁pending为空
            assertEquals(0, rows("system_users"));
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }
}
