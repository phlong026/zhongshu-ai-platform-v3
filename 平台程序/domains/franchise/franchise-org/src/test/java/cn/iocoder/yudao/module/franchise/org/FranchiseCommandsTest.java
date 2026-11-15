package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class FranchiseCommandsTest {

    private final FranchiseCommands commands = new FranchiseCommands(mock(CommandLog.class));

    @Test
    void requiredFinancialKeyIsTrimmedButNeverTruncated() {
        assertThat(commands.requireIdempotencyKey(" stable-key ")).isEqualTo("stable-key");
        assertThat(commands.requireIdempotencyKey("x".repeat(80))).hasSize(80);
        assertBadRequest(null);
        assertBadRequest(" ");
        assertBadRequest("x".repeat(81));
    }

    private void assertBadRequest(String key) {
        assertThatThrownBy(() -> commands.requireIdempotencyKey(key))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    void samePayloadAndUserCannotReplayAnotherOrganizationsEvent() {
        var log = mock(CommandLog.class);
        var service = new FranchiseCommands(log);
        var post = new PostSnapshot(1, 2, 300, "store", "finance", null, false, Set.of());
        var payload = Map.of("amount", 100);
        var event = new CommandEventDO().setOrgId(200L).setAggregateId(11L)
                .setPayloadHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(payload)));
        when(log.findByIdempotencyKey(anyString())).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.replayed(post, "same-key", payload)).isInstanceOf(ServiceException.class);
        event.setOrgId(post.orgId());
        assertThat(service.replayed(post, "same-key", payload)).contains(event);
    }

    @Test
    void acceptsExplicitLegacyFingerprintButRejectsUnrelatedPayload() {
        var log = mock(CommandLog.class);
        var service = new FranchiseCommands(log);
        var post = new PostSnapshot(1, 2, 300, "store", "sales", null, false, Set.of());
        var legacyPayload = Map.of("operation", "delivery-create", "contractId", 9L);
        var currentPayload = Map.of("operation", "delivery-create", "contractId", 9L, "requirementId", 1L);
        var event = new CommandEventDO().setOrgId(post.orgId()).setAggregateId(11L)
                .setPayloadHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(legacyPayload)));
        when(log.findByIdempotencyKey(anyString())).thenReturn(Optional.of(event));

        assertThat(service.replayed(post, "same-key", currentPayload, legacyPayload)).contains(event);
        assertThatThrownBy(() -> service.replayed(post, "same-key",
                Map.of("operation", "delivery-create", "contractId", 10L), null))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void concurrentRecordCannotAcceptAnotherOrganizationsEvent() {
        var log = mock(CommandLog.class);
        var service = new FranchiseCommands(log);
        var post = new PostSnapshot(1, 2, 300, "store", "finance", null, false, Set.of());
        when(log.append(any())).thenReturn(new CommandEventDO().setOrgId(200L).setAggregateId(11L)
                .setAggregateType("receipt").setIdempotencyKey("same-key"));
        assertThatThrownBy(() -> service.record(post, "receipt", 11L, 1L, "create", Map.of(), "same-key"))
                .isInstanceOf(ServiceException.class);
    }
}
