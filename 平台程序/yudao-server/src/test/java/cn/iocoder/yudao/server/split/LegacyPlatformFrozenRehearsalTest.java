package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.method.HandlerMethod;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * FROZEN 推演演练（停写体系收官验证，绿后第一件——主线指令2026-11-12）：
 * 隔离环境证明 G1 门禁状态机终点行为。
 *
 * <p>阶段A（全量入口证明）：真实拦截器 targets=193 wired 方法（反射核数）——OPEN 全writer准入
 * → DRAINING 全193入口 ServiceException 503（preHandle 遍历，无需 HTTP/控制器实例）→ 排空判据
 * （pending 清空）→ FROZEN apply（真实 FreezeEvidence：全13 wiredWriters+实例ACK+watermarks 五项）
 * → FROZEN 全193入口仍503。
 * 阶段B（持久化+单调性+拒绝面）：真实 FileStore 控制目录（@TempDir）——初始显式 checkpoint→
 * OPEN→DRAINING→FROZEN 持久化文件核验；REOPEN_FORBIDDEN/FROZEN_IS_MONOTONIC/STALE_GENERATION/
 * 缺 FreezeEvidence 拒绝（G1 内核既有26测试覆盖引用+本演练终态实测）。
 * 豁免3面语义不变声明：Captcha×2/SendSubscribeMessage 不在 writers 机制内（登记性写源），
 * 门禁状态迁移不触及——writer-coverage fe6d3685 终态文件级断言（c32意见a/b落地引用）。
 */
class LegacyPlatformFrozenRehearsalTest {
    private static final String SCOPE = "0".repeat(64);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Set<String> ALL_WRITERS = Set.of(
            "platform-http", "franchise-http", "legacy-session", "identity-provision",
            "system-mutations", "system-events", "commerce-wallet", "commerce-payment",
            "design-driver", "ai-cancel-runtime", "content-jobs", "outbox-export-privacy",
            "remote-recovery");

    @TempDir
    static Path controlDir;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/platform-bootstrap/V20260904.001__platform_system_infra.sql"))
                .execute(dataSource);
        new WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    /** 初始显式 checkpoint（FileStore 契约：EXPLICIT_INITIAL_CHECKPOINT_REQUIRED）。 */
    private static void writeInitialCheckpoint(Path dir, String scope) throws Exception {
        var checkpointFile = dir.resolve("checkpoint.properties");
        Files.writeString(checkpointFile,
                "format=legacy-b-checkpoint-v1\ngeneration=0\nstate=CLOSED\nscope_sha256=" + scope
                        + "\ncontrol_fault=false\n");
        // FileStore.checkPrivate 要求 checkpoint 文件亦为 owner-only rw-------（644 会令 load 失败→controlFault）。
        Files.setPosixFilePermissions(checkpointFile,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    private static int wiredTargets(LegacyPlatformHttpWriteInterceptor interceptor) throws Exception {
        Field f = LegacyPlatformHttpWriteInterceptor.class.getDeclaredField("targets");
        f.setAccessible(true);
        Map<?, ?> targets = (Map<?, ?>) f.get(interceptor);
        return targets.size();
    }

    @SuppressWarnings("unchecked")
    private static List<Method> wiredMethods(LegacyPlatformHttpWriteInterceptor interceptor) throws Exception {
        Field f = LegacyPlatformHttpWriteInterceptor.class.getDeclaredField("targets");
        f.setAccessible(true);
        Map<Method, ?> targets = (Map<Method, ?>) f.get(interceptor);
        return new ArrayList<>(targets.keySet());
    }

    /** 全量入口遍历：DRAINING/FROZEN 下 preHandle 必抛 ServiceException(503)。 */
    private static void assertAllWiredEntriesRefused(LegacyPlatformHttpWriteInterceptor interceptor,
                                                     int expectedCount) throws Exception {
        var methods = wiredMethods(interceptor);
        assertEquals(expectedCount, methods.size());
        int refused = 0;
        for (Method method : methods) {
            var handler = new HandlerMethod(new Object(), method);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            var failure = assertThrows(ServiceException.class,
                    () -> interceptor.preHandle(request, response, handler), method.getName());
            assertEquals(503, failure.getCode(), method.getName());
            refused++;
        }
        assertEquals(expectedCount, refused);
    }

    @Test void stageA_fullLifecycleRehearsalWithAllWritersAndAllWiredEntries() throws Exception {
        // 装配：单实例演练 gate（13 REQUIRED_WRITERS 全注册；多实例 ACK 机制由 G1 内核既有26测试覆盖）。
        var store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        var gate = new LegacyPlatformWriteGate("rehearsal-1", SCOPE, Set.of("rehearsal-1"),
                ALL_WRITERS, store);
        // 阶段A1 检查单装配：OPEN——13 writer 全部准入（writer-coverage 13 wired 终态证明）。
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        for (String writer : ALL_WRITERS) {
            var permit = gate.enter(Owner.B_LEGACY, writer, "rehearsal-" + writer);
            permit.close();
        }
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());

        // 阶段A2 全量入口数核验：真实拦截器 targets=193 wired 方法。
        var interceptor = new LegacyPlatformHttpWriteInterceptor(gate, true);
        assertEquals(193, wiredTargets(interceptor), "wired targets must equal 109+84");
        assertEquals(193, wiredMethods(interceptor).size());

        // 阶段A3 DRAINING：全193入口 ServiceException 503（OPEN→DRAINING 迁移）。
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertAllWiredEntriesRefused(interceptor, 193);
        // DRAINING 下 enter 亦拒绝（新工作零准入）+ pending 可见性=空（入口级拒绝不入 pending）。
        for (String writer : ALL_WRITERS) {
            assertThrows(IllegalStateException.class,
                    () -> gate.enter(Owner.B_LEGACY, writer, "rehearsal-draining"));
        }
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());

        // 阶段A4 排空判据核验：pending 空（各存量面数字引用=G2m ai_job 178全终态/G2n fr_content_task
        // 111全终态+配额量/G2o outbox 357全DISPATCHED+data_subject 0/G2p 命令9行B合法——已证于各批）。
        // FreezeEvidence watermarks 五项非空（payment-inbox 必含）。
        var watermarks = Map.of("payment-inbox", "drained-zero-pending",
                "ai-job", "178-terminal-zero-nonterminal",
                "content-task", "111-terminal-11-uncertain-registered",
                "outbox-event", "357-dispatched-zero-pending",
                "data-subject", "zero-open-requests");

        // 阶段A5 FROZEN apply：真实 FreezeEvidence（全13 wiredWriters+实例ACK）。
        String boot = gate.snapshot().bootId();
        var ack = new Ack("rehearsal-1", boot, 2, SCOPE, State.DRAINING, 0, false);
        var evidence = new FreezeEvidence(Map.of("rehearsal-1", boot),
                Map.of("rehearsal-1", ack), ALL_WRITERS, true, true, true, watermarks);
        gate.apply(new Control(3, State.FROZEN, SCOPE), evidence);
        assertEquals(State.FROZEN, gate.snapshot().checkpoint().state());

        // 阶段A6 FROZEN 行为：全193入口仍503；豁免3面语义不变（不在 targets/writers 机制——登记性写源）。
        assertAllWiredEntriesRefused(interceptor, 193);
        for (String writer : ALL_WRITERS) {
            assertThrows(IllegalStateException.class,
                    () -> gate.enter(Owner.B_LEGACY, writer, "rehearsal-frozen"));
        }
    }

    @Test void stageA_frozenIsMonotonicAndRejectsMissingEvidence() throws Exception {
        var store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        var gate = new LegacyPlatformWriteGate("rehearsal-2", SCOPE, Set.of("rehearsal-2"),
                ALL_WRITERS, store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        // 缺 FreezeEvidence（missing()）→ WRITER_COVERAGE_INCOMPLETE 拒绝（G1 装配矩阵已证+终态实测）。
        assertThrows(IllegalStateException.class,
                () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        assertEquals(State.DRAINING, gate.snapshot().checkpoint().state());
        // FROZEN_IS_MONOTONIC：FROZEN 后不得回 DRAINING。
        String boot = gate.snapshot().bootId();
        var ack = new Ack("rehearsal-2", boot, 2, SCOPE, State.DRAINING, 0, false);
        var evidence = new FreezeEvidence(Map.of("rehearsal-2", boot), Map.of("rehearsal-2", ack),
                ALL_WRITERS, true, true, true, Map.of("payment-inbox", "drained"));
        gate.apply(new Control(3, State.FROZEN, SCOPE), evidence);
        assertThrows(IllegalStateException.class,
                () -> gate.apply(new Control(4, State.DRAINING, SCOPE), FreezeEvidence.missing()));
        // STALE_GENERATION：旧代数控制被拒。
        assertThrows(IllegalStateException.class,
                () -> gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing()));
        // REOPEN_FORZEN：非初始 checkpoint 不得回 OPEN。
        assertThrows(IllegalStateException.class,
                () -> gate.apply(new Control(4, State.OPEN, SCOPE), FreezeEvidence.missing()));
        assertEquals(State.FROZEN, gate.snapshot().checkpoint().state());
    }

    @Test void stageB_realFileStorePersistenceAndTerminalCheckpoint() throws Exception {
        Path dir = controlDir.resolve("rehearsal-filestore");
        Files.createDirectories(dir);
        // FileStore.checkPrivate 要求 owner-only rwx------（@TempDir 默认权限不足）。
        Files.setPosixFilePermissions(dir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        writeInitialCheckpoint(dir, SCOPE);
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var fileStore = new LegacyPlatformWriteConfiguration.FileStore(dir);
        var gate = new LegacyPlatformWriteGate("rehearsal-3", SCOPE, Set.of("rehearsal-3"),
                ALL_WRITERS, fileStore);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        String boot = gate.snapshot().bootId();
        var ack = new Ack("rehearsal-3", boot, 2, SCOPE, State.DRAINING, 0, false);
        var evidence = new FreezeEvidence(Map.of("rehearsal-3", boot), Map.of("rehearsal-3", ack),
                ALL_WRITERS, true, true, true,
                Map.of("payment-inbox", "drained-zero-pending", "ai-job", "terminal"));
        gate.apply(new Control(3, State.FROZEN, SCOPE), evidence);
        fileStore.close();
        // 持久化核验：checkpoint 文件 state=FROZEN generation=3（进程重启后 load 恢复终态）。
        var persisted = Files.readString(dir.resolve("checkpoint.properties"));
        assertTrue(persisted.contains("state=FROZEN"), persisted);
        assertTrue(persisted.contains("generation=3"), persisted);
        assertTrue(persisted.contains("scope_sha256=" + SCOPE), persisted);
        // 重启恢复：新 FileStore load 得 FROZEN 终态。
        var reopened = new LegacyPlatformWriteConfiguration.FileStore(dir);
        var checkpoint = reopened.load();
        assertEquals(State.FROZEN, checkpoint.state());
        assertEquals(3, checkpoint.generation());
        reopened.close();
        // 演练证据快照（主线指令第4条：控制目录快照+checkpoint）：仓库外证据目录。
        var evidenceDir = java.nio.file.Paths.get("/tmp", "zs-p3-g2s-final-contract");
        Files.createDirectories(evidenceDir);
        Files.copy(dir.resolve("checkpoint.properties"),
                evidenceDir.resolve("frozen-checkpoint-snapshot.properties"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var list = Files.list(dir)) {
            Files.write(evidenceDir.resolve("control-dir-listing.txt"),
                    list.map(Path::getFileName).map(Path::toString).sorted().toList());
        }
        assertNotNull(dataSource); // PG 容器生命周期引用
        var jdbc = new JdbcTemplate(dataSource);
        assertNotNull(jdbc);
    }

    @Test void stageC_writerCoverageTerminalFileVerification() throws Exception {
        // c32 意见(a)/(b) 文件级断言：13 writer 翻牌 + 豁免3 + 纯读1 + 前置项披露清单在册。
        var coverage = JSON.readTree(Files.readString(java.nio.file.Paths.get("..",
                "script", "platform-migration", "writer-coverage.json").normalize()));
        var writers = coverage.get("writers");
        assertEquals(13, writers.size());
        var names = new java.util.HashSet<String>();
        writers.forEach(w -> {
            names.add(w.get("id").asText());
            assertEquals("WIRED", w.get("status").asText(), w.get("id").asText());
        });
        assertEquals(ALL_WRITERS, names);
        assertEquals(3, coverage.get("exemptions").size());
        // c32必改2（face唯一性/集合等价，防假阳性）：3条=3面一一对应。
        var faces = new java.util.HashSet<String>();
        coverage.get("exemptions").forEach(x -> faces.add(x.get("face").asText()));
        assertEquals(3, faces.size());
        assertEquals(Set.of("CaptchaController.get（POST /system/captcha/get）",
                "CaptchaController.check（POST /system/captcha/check）",
                "SocialClientController.sendSubscribeMessage（POST /system/social-client/send-subscribe-message）"),
                Set.copyOf(faces));
        assertEquals(1, coverage.get("pure_reads_confirmed").size());
        assertNotNull(coverage.get("preexisting_deviations"));
        // c32随签(a)：RealServiceWiringPolicy.java 点名在册。
        var devFiles = new java.util.HashSet<String>();
        coverage.get("preexisting_deviations").forEach(x -> devFiles.add(x.get("file").asText()));
        assertTrue(devFiles.stream().anyMatch(f -> f.contains("RealServiceWiringPolicy.java")));
        assertTrue(coverage.get("note").asText().contains("13类全覆盖"));
    }
}
