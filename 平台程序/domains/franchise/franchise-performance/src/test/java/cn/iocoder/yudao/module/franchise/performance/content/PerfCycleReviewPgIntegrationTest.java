package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Import({PerfCycleService.class, PerfCycleAccess.class, PerfGoalService.class, FranchiseCommands.class, CommandLogImpl.class})
class PerfCycleReviewPgIntegrationTest extends FranchisePgIntegrationTest {
    private static final String MONTH = "2026-11";
    @Resource private PerfCycleService service;
    @Resource private JdbcTemplate jdbc;
    @Resource private FranchiseCommands commands;
    @Resource private PlatformTransactionManager transactionManager;
    @MockitoBean private PerfMetrics metrics;

    @Test
    void employeeReadsOwnCycleAndManagerReadsOnlyThisOrganization() {
        var store = fixture.store();
        var employee = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        var coworker = fixture.post(store.orgId(), "sales", store.salesTeamA());
        var boss = fixture.post(store.orgId(), "boss", null);
        var outside = fixture.post(fixture.store().orgId(), "owner", null);
        for (var person : java.util.List.of(employee, coworker, outside)) {
            historic(person, "已存在的周期记录");
        }
        currentPost.use(employee);
        assertThat(service.list(employee, MONTH)).hasSize(1);
        currentPost.use(boss);
        assertThat(service.list(boss, MONTH)).hasSize(2);
        currentPost.use(outside);
        assertThat(service.list(outside, MONTH)).hasSize(1);
    }

    private void historic(PostSnapshot person, String note) {
        jdbc.update("INSERT INTO fr_perf_cycle(id,company_id,period,user_id,status,source_signature,submission) "
                        + "VALUES (?,?,?,?,'PENDING_REVIEW',?,jsonb_build_object('note',?::text))",
                IdWorker.getId(), person.orgId(), MONTH, person.userId(),
                PerfMetrics.sourceSignature(java.util.List.of()), note);
    }

    @Test
    void exactVersionsRejectStaleReviewsWhileSubmissionsAndReceiptsRemainTraceable() {
        var store = fixture.store();
        var employee = fixture.post(store.orgId(), "sales", store.salesTeamA());
        var boss = fixture.post(store.orgId(), "boss", null);
        seedGoal(employee);
        var date = new AtomicReference<>(LocalDate.of(2026, 11, 2));
        String key = "perf-publication:90071992547409991";
        when(metrics.sources(any(), any(), any())).thenAnswer(inv -> List.of(new PerfMetrics.SourceRow(key,
                "auto:publications", 100L, employee.userId(), date.get(), key)));

        currentPost.use(employee);
        var first = service.submit(employee, MONTH, 0, "初次核对", 0L, "cycle-first");
        assertThat(first).containsEntry("revision", 1L).containsEntry("canReview", false);
        assertThat(service.submit(employee, MONTH, 0, "初次核对", 0L, "cycle-first")).isEqualTo(first);
        assertThatThrownBy(() -> service.submit(employee, MONTH, 0, "新内容", 0L, "cycle-first")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.submit(employee, MONTH, boss.userId(), "代交", 0L, "outside-own")).isInstanceOf(ServiceException.class);
        var second = service.submit(employee, MONTH, 0, "补充说明", 1L, "cycle-second");
        var snapshot = JsonUtil.parseMap((String) second.get("submission"));
        assertThat((List<?>) snapshot.get("history")).hasSize(1);
        assertThat((List<?>) snapshot.get("sources")).hasSize(1);
        assertThat(snapshot).containsEntry("note", "补充说明").containsEntry("goalVersion", 3);
        assertThat(JsonUtils.toJsonString(snapshot.get("sources"))).contains("90071992547409991", "2026-11-02");
        currentPost.use(boss);
        assertThatThrownBy(() -> service.review(boss, MONTH, employee.userId(), true, "", 1L, "review-before-resubmit"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> service.review(boss, MONTH, employee.userId(), true, "", null, "old-client"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(service.list(boss, MONTH).get(0)).containsEntry("canReview", true);
        date.set(LocalDate.of(2026, 11, 3));
        var rejected = service.review(boss, MONTH, employee.userId(), true, "核过", 2L, "review-changed");
        assertThat(rejected).containsEntry("status", "RETURNED").containsEntry("revision", 3L);
        assertThat(JsonUtil.parseMap((String) rejected.get("review")).get("reason").toString()).contains("来源");
        assertThat(service.review(boss, MONTH, employee.userId(), true, "核过", 2L, "review-changed")).isEqualTo(rejected);
        currentPost.use(employee);
        var third = service.submit(employee, MONTH, 0, "已按原单重新提交", 3L, "cycle-third");
        assertThat(third.get("review")).isNull();
        var history = (List<?>) JsonUtil.parseMap((String) third.get("submission")).get("history");
        assertThat(history).hasSize(2);
        assertThat(JsonUtils.toJsonString(history)).contains("初次核对", "补充说明", "2026-11-02", "来源");
        assertThat(((Map<?, ?>) ((Map<?, ?>) history.get(1)).get("submission")).containsKey("history")).isFalse();
        currentPost.use(boss);
        assertThat(service.review(boss, MONTH, employee.userId(), true, "月度确认", 4L, "fresh-review"))
                .containsEntry("status", "REVIEWED").containsEntry("revision", 5L);
    }

    @Test
    void legacyFactsStayUnchangedAndRevocationDeniesOldReadsAndWrites() {
        var org = fixture.store().orgId();
        var boss = fixture.post(org, "boss", null);
        var worker = fixture.post(org, "sales", null);
        historic(worker, "历史核对");
        historic(boss, "老板本人");
        currentPost.use(boss);
        assertThatThrownBy(() -> service.review(boss, MONTH, boss.userId(), true, "", 1L, "self-review"))
                .isInstanceOf(ServiceException.class);
        when(metrics.sources(any(), any(), any())).thenReturn(List.of());
        var reviewed = service.review(boss, MONTH, worker.userId(), true, "核对历史依据", null, "old-initial");
        assertThat(reviewed.get("status")).isEqualTo("REVIEWED");
        assertThat(JsonUtil.parseMap((String) reviewed.get("submission"))).isEqualTo(Map.of("note", "历史核对"));
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?", boss.postId());
        assertThatThrownBy(() -> service.list(boss, MONTH)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.review(boss, MONTH, worker.userId(), true, "核对历史依据", null, "old-initial"))
                .isInstanceOf(ServiceException.class);
        currentPost.use(worker);
        assertThat(service.list(worker, MONTH)).hasSize(1);
    }

    @Test
    void twoReviewersCannotSetDifferentResultsForTheSameDisplayedVersion() throws Exception {
        var org = fixture.store().orgId();
        var manager = fixture.post(org, "boss", null);
        var worker = fixture.post(org, "engineer", null);
        historic(worker, "并发复核");
        currentPost.use(manager);
        when(metrics.sources(any(), any(), any())).thenReturn(List.of());
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var attempts = List.of(true, false).stream().map(approve -> pool.submit(() -> {
                release.await();
                try {
                    service.review(manager, MONTH, worker.userId(), approve, "明确结论", 1L, "race-" + approve);
                    return 200;
                } catch (ServiceException conflict) {
                    return conflict.getCode();
                }
            })).toList();
            release.countDown();
            assertThat(List.of(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command='perf-cycle-review'",
                Long.class, org)).isEqualTo(1);
    }

    private void seedGoal(PostSnapshot actor) {
        jdbc.update("INSERT INTO fr_perf_goal(id,company_id,period,version,author_user_id,legacy_staff) "
                        + "VALUES(?,?,?,3,?,jsonb_build_object(?::text,400))",
                IdWorker.getId(), actor.orgId(), MONTH, actor.userId(), String.valueOf(actor.userId()));
    }

    @Test
    void originalUnversionedSubmitReceiptCanReplayAfterAnIndependentReview() {
        var org = fixture.store().orgId();
        var employee = fixture.post(org, "engineer", null);
        var boss = fixture.post(org, "boss", null);
        historic(employee, "原提交说明");
        currentPost.use(employee);
        long cycleId = jdbc.queryForObject("SELECT id FROM fr_perf_cycle WHERE company_id=? AND period=? AND user_id=?",
                Long.class, org, MONTH, employee.userId());
        commands.record(employee, PerfCycleService.AGGREGATE, cycleId, 1, "perf-cycle-submit",
                Map.of("period", MONTH, "userId", employee.userId(), "targetMissing", "0"), "legacy-receipt");
        currentPost.use(boss);
        when(metrics.sources(any(), any(), any())).thenReturn(List.of());
        service.review(boss, MONTH, employee.userId(), true, "核对", null, "legacy-reviewed");
        currentPost.use(employee);
        assertThat(service.submit(employee, MONTH, 0, " 原提交说明 ", null, "legacy-receipt"))
                .containsEntry("status", "REVIEWED").containsEntry("revision", 2L);
        assertThatThrownBy(() -> service.submit(employee, MONTH, 0, "不同说明", null, "legacy-receipt"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(409));
    }

    @Test
    void revocationWhileFreezingSourcesRollsBackTheCycleAndItsAuditReceipt() {
        var org = fixture.store().orgId();
        var employee = fixture.post(org, "sales", null);
        seedGoal(employee);
        currentPost.use(employee);
        var revoke = new TransactionTemplate(transactionManager);
        revoke.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(metrics.sources(any(), any(), any())).thenAnswer(inv -> {
            revoke.executeWithoutResult(status -> jdbc.update(
                    "UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?", employee.postId()));
            return List.of();
        });
        assertThatThrownBy(() -> service.submit(employee, MONTH, 0, "核对后切岗", 0L, "withdrawn-pending"))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_perf_cycle WHERE company_id=?", Long.class, org)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command='perf-cycle-submit'",
                Long.class, org)).isZero();
    }
}
