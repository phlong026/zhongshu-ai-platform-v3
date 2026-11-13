package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamCommandServiceTest {

    private static final PostSnapshot POST = new PostSnapshot(11L, 9L, 151L, "store", "boss", null, false, Set.of());

    @Mock
    private OrgService orgService;
    @Mock
    private CommandLog commandLog;

    private TeamCommandService service;

    @BeforeEach
    void setUp() {
        service = new TeamCommandService(orgService, commandLog);
    }

    @Test
    void rejectsBlankAndOverlongKeysBeforeBusinessWrite() {
        assertThatThrownBy(() -> service.create(POST, " ", "业务部", "sales"))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode()).isEqualTo(400);
        assertThatThrownBy(() -> service.create(POST, "x".repeat(129), "业务部", "sales"))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode()).isEqualTo(400);
        verify(orgService, never()).createTeam(anyLong(), any(), any(), any());
    }

    @Test
    void replaysSameKeyAndPayloadWithoutCreatingAgain() {
        String payload = "{\"orgId\":151,\"name\":\"业务部\",\"line\":\"sales\"}";
        when(commandLog.findByIdempotencyKey(any())).thenReturn(Optional.of(new CommandEventDO()
                .setAggregateId(152L).setPayloadHash(DigestUtil.sha256Hex(payload))));

        assertThat(service.create(POST, " team-1 ", "业务部", "sales")).isEqualTo(152L);
        verify(orgService, never()).createTeam(anyLong(), any(), any(), any());
        InOrder order = inOrder(orgService, commandLog);
        order.verify(orgService).lockOrgForCommand(151L);
        order.verify(commandLog).findByIdempotencyKey(any());
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() {
        when(commandLog.findByIdempotencyKey(any())).thenReturn(Optional.of(new CommandEventDO()
                .setAggregateId(152L).setPayloadHash(DigestUtil.sha256Hex("different"))));

        assertThatThrownBy(() -> service.create(POST, "team-1", "业务部", "sales"))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(IDEMPOTENCY_KEY_REUSED.getCode());
        verify(orgService, never()).createTeam(anyLong(), any(), any(), any());
    }

    @Test
    void createsAndRecordsAfterLockWhenKeyIsNew() {
        when(commandLog.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(orgService.createTeam(151L, null, "业务部", "sales")).thenReturn(152L);
        when(commandLog.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(POST, "team-1", "业务部", "sales")).isEqualTo(152L);
        InOrder order = inOrder(orgService, commandLog);
        order.verify(orgService).lockOrgForCommand(151L);
        order.verify(commandLog).findByIdempotencyKey(any());
        order.verify(orgService).createTeam(151L, null, "业务部", "sales");
        order.verify(commandLog).append(any());
    }
}
