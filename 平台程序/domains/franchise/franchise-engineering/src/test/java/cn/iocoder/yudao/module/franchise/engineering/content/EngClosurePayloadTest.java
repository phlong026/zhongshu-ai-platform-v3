package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyMapper;
import cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等重放回归锁（评审十三 P1-3/AC13）：写命令的「重放输入」与「落账输入」必须同形——
 * FranchiseCommands.replayed 按 payload SHA256 比对，形状错位即 IDEMPOTENCY_KEY_REUSED。
 * 以 settle 为代表锁定「同一 payload 变量贯穿 replay/record」的修复模式。
 */
class EngClosurePayloadTest {

    @Test
    void settleReplayPayloadMatchesRecordPayload() {
        EngProjectService projectService = mock(EngProjectService.class);
        EngPlanService planService = mock(EngPlanService.class);
        FrEngClosureMapper closures = mock(FrEngClosureMapper.class);
        FranchiseCommands commands = mock(FranchiseCommands.class);
        EngineeringLedgerQueryPort ledger = mock(EngineeringLedgerQueryPort.class);
        FrEngSupplyMapper supplies = mock(FrEngSupplyMapper.class);
        FrEngCollectionTermMapper collectionTerms = mock(FrEngCollectionTermMapper.class);
        FrEngCollectionMapper collections = mock(FrEngCollectionMapper.class);
        EngClosureService service = new EngClosureService(projectService, planService, closures,
                mock(cn.iocoder.yudao.module.franchise.engineering.dal.FrEngExpenseMapper.class),
                mock(FrEngPlanNodeMapper.class), collectionTerms,
                collections, supplies,
                mock(FrEngMaterialMapper.class), commands, ledger,
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.class));

        PostSnapshot post = new PostSnapshot(11L, 7L, 100L, "shop", "project-manager",
                null, false, Set.of());
        FrEngProjectDO project = new FrEngProjectDO().setId(9L).setCompanyId(100L)
                .setManagerUserId(7L).setRevision(5L);
        when(projectService.requireOwned(post, 9L, "settle")).thenReturn(project);
        when(projectService.requireProject(post, 9L)).thenReturn(project);
        when(commands.replayed(eq(post), anyString(), any())).thenReturn(Optional.empty());
        when(closures.selectByProject(100L, 9L)).thenReturn(new FrEngClosureDO().setId(1L)
                .setDeliveredAt(LocalDate.now()));
        when(projectService.bumpRevision(post, project)).thenReturn(6L);
        // settleSummary 聚合查询（空集即可，不影响 payload 断言）
        when(supplies.selectByProject(100L, 9L)).thenReturn(java.util.List.of());
        when(collectionTerms.selectByProject(100L, 9L)).thenReturn(java.util.List.of());
        when(collections.selectByProject(100L, 9L)).thenReturn(java.util.List.of());
        when(ledger.paidCentsByPayableIds(eq(post), any())).thenReturn(Map.of());

        service.settle(post, 9L, new EngClosureService.SettleCommand(
                LocalDate.of(2026, 11, 1), "结算说明", null), "key-1");

        ArgumentCaptor<Object> replayPayload = ArgumentCaptor.forClass(Object.class);
        verify(commands).replayed(eq(post), eq("key-1"), replayPayload.capture());
        ArgumentCaptor<Object> recordPayload = ArgumentCaptor.forClass(Object.class);
        verify(commands).record(eq(post), anyString(), eq(9L), eq(6L), eq("eng-settle"),
                recordPayload.capture(), eq("key-1"));
        assertThat(recordPayload.getValue()).isEqualTo(replayPayload.getValue())
                .isEqualTo(Map.of("date", "2026-11-01", "note", "结算说明"));
    }

}
