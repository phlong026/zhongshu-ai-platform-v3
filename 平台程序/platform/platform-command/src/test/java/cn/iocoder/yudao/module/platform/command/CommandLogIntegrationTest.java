package cn.iocoder.yudao.module.platform.command;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(CommandLogImpl.class)
class CommandLogIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong AGGREGATE_IDS = new AtomicLong(System.nanoTime());

    @Resource
    private CommandLog commandLog;
    @Resource
    private TransactionTemplate tx;
    @Resource
    private JdbcTemplate jdbc;

    @Test
    void appendsEventsAndListsThemInRevisionOrder() {
        long aggregateId = AGGREGATE_IDS.incrementAndGet();
        tx.executeWithoutResult(s -> commandLog.append(event(aggregateId, 1, "commission-submit", null, "{\"total\":100}")));
        tx.executeWithoutResult(s -> commandLog.append(event(aggregateId, 2, "design-complete", null, "{}")));

        var events = commandLog.listByAggregate("franchise", "commission", aggregateId);

        assertThat(events).extracting(CommandEventDO::getCommand).containsExactly("commission-submit", "design-complete");
        assertThat(events).extracting(CommandEventDO::getRevision).containsExactly(1L, 2L);
        assertThat(events.get(0).getId()).isNotNull();
        assertThat(events.get(0).getOccurredAt()).isNotNull();
        assertThat(events.get(0).getPayload()).isEqualTo("{\"total\":100}");
    }

    @Test
    void replayWithSameIdempotencyKeyAndPayloadReturnsOriginalEventWithoutInsert() {
        long aggregateId = AGGREGATE_IDS.incrementAndGet();
        String key = "idem-" + aggregateId;
        CommandEventDO first = tx.execute(s -> commandLog.append(event(aggregateId, 1, "commission-submit", key, "{\"a\":1}")));
        CommandEventDO replay = tx.execute(s -> commandLog.append(event(aggregateId, 1, "commission-submit", key, "{\"a\":1}")));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(commandLog.listByAggregate("franchise", "commission", aggregateId)).hasSize(1);
        assertThat(commandLog.findByIdempotencyKey(key)).get().extracting(CommandEventDO::getId).isEqualTo(first.getId());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        long aggregateId = AGGREGATE_IDS.incrementAndGet();
        String key = "idem-" + aggregateId;
        tx.executeWithoutResult(s -> commandLog.append(event(aggregateId, 1, "commission-submit", key, "{\"a\":1}")));

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                commandLog.append(event(aggregateId, 1, "commission-submit", key, "{\"a\":2}"))))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode());
    }

    @Test
    void eventRollsBackWithTheBusinessTransaction() {
        long aggregateId = AGGREGATE_IDS.incrementAndGet();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            commandLog.append(event(aggregateId, 1, "commission-submit", null, "{}"));
            throw new IllegalStateException("业务写入失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(commandLog.listByAggregate("franchise", "commission", aggregateId)).isEmpty();
    }

    @Test
    void sameAggregateRevisionCannotBeRecordedTwice() {
        long aggregateId = AGGREGATE_IDS.incrementAndGet();
        tx.executeWithoutResult(s -> commandLog.append(event(aggregateId, 1, "commission-submit", null, "{}")));

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                commandLog.append(event(aggregateId, 1, "design-complete", null, "{}"))))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT.getCode());
    }

    @Test
    void revisionGuardRejectsStaleUpdates() {
        long aggregateId = AGGREGATE_IDS.incrementAndGet();
        jdbc.execute("CREATE TABLE IF NOT EXISTS revision_probe(id BIGINT PRIMARY KEY, revision BIGINT NOT NULL)");
        jdbc.update("INSERT INTO revision_probe(id, revision) VALUES (?, 3)", aggregateId);

        int first = jdbc.update("UPDATE revision_probe SET revision = revision + 1 WHERE id = ? AND revision = 3", aggregateId);
        int stale = jdbc.update("UPDATE revision_probe SET revision = revision + 1 WHERE id = ? AND revision = 3", aggregateId);

        Revisions.requireUpdated(first);
        assertThatThrownBy(() -> Revisions.requireUpdated(stale))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT.getCode());
    }

    private static CommandEventDO event(long aggregateId, long revision, String command, String key, String payload) {
        return new CommandEventDO()
                .setDomain("franchise").setAggregateType("commission").setAggregateId(aggregateId)
                .setRevision(revision).setCommand(command)
                .setOrgId(10L).setActorUserId(20L).setActorPostId(30L).setActorRoleCode("sales")
                .setIdempotencyKey(key).setPayload(payload);
    }

}
