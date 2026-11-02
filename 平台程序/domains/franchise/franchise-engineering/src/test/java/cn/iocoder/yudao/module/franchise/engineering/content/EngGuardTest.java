package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyReceiptDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守卫纯函数（17 §七单测清单：状态机/门禁方向/owner 校验）：
 * owner=负责人或指定工程师本人（D-14）；TOPPING 完成=topping 节点 DONE（收款侧前置的派生口径）；
 * 材料齐全=合格到货+无未闭环不合格（蓝本 :31-32）；白名单（F10）。
 */
class EngGuardTest {

    private static FrEngProjectDO project(long manager, Long assigned) {
        return new FrEngProjectDO().setCompanyId(1L).setManagerUserId(manager).setAssignedEngineerUserId(assigned);
    }

    @Test
    void ownerIsManagerOrAssignedEngineerOnly() {
        assertThatThrownBy(() -> EngGuard.requireOwner(project(10, null), 20))
                .hasMessageContaining("无权办理本工程");
        EngGuard.requireOwner(project(10, null), 10);
        EngGuard.requireOwner(project(10L, 30L), 30);
        EngGuard.requireOwner(project(10, null), 10);
    }

    @Test
    void toppingDoneRequiresToppingNodeDone() {
        FrEngPlanNodeDO topping = new FrEngPlanNodeDO().setMilestone("TOPPING");
        assertThat(EngGuard.toppingDone(List.of(topping.setStatus("IN_PROGRESS")))).isFalse();
        assertThat(EngGuard.toppingDone(List.of(topping.setStatus("DONE")))).isTrue();
        // 无 topping 里程碑的节点完成不算封顶
        assertThat(EngGuard.toppingDone(List.of(new FrEngPlanNodeDO()
                .setMilestone(null).setStatus("DONE")))).isFalse();
    }

    @Test
    void statusDerivesInLifecycleOrder() {
        FrEngProjectDO p = project(1, null);
        List<FrEngPlanNodeDO> oneOpen = List.of(new FrEngPlanNodeDO().setStatus("DONE"),
                new FrEngPlanNodeDO().setStatus("IN_PROGRESS"));
        FrEngClosureDO closure = new FrEngClosureDO();
        assertThat(EngGuard.deriveStatus(p, oneOpen, closure)).isEqualTo("PENDING_START");
        p.setActualStartAt(LocalDate.now());
        assertThat(EngGuard.deriveStatus(p, oneOpen, closure)).isEqualTo("IN_PROGRESS");
        List<FrEngPlanNodeDO> allDone = List.of(new FrEngPlanNodeDO().setStatus("DONE"),
                new FrEngPlanNodeDO().setStatus("DONE"));
        assertThat(EngGuard.deriveStatus(p, allDone, closure)).isEqualTo("READY_DELIVER");
        closure.setDeliveredAt(LocalDate.now());
        assertThat(EngGuard.deriveStatus(p, allDone, closure)).isEqualTo("DELIVERED");
        closure.setArchivedAt(java.time.OffsetDateTime.now());
        assertThat(EngGuard.deriveStatus(p, allDone, closure)).isEqualTo("ARCHIVED");
    }

    private static FrEngSupplyReceiptDO receipt(String quality, String resolution, String actual) {
        return new FrEngSupplyReceiptDO().setQuality(quality).setQualityResolution(resolution)
                .setActualQuantity(actual == null ? null : new BigDecimal(actual));
    }

    @Test
    void goodQuantityCountsClosedQualityOnly() {
        assertThat(EngGuard.goodQuantity(List.of(
                receipt("OK", null, "10"),
                receipt("REJECTED", null, "5"),
                receipt("REJECTED", "{\"result\":\"更换后合格\"}", "3"))))
                .isEqualByComparingTo("13");
        assertThat(EngGuard.qualityPassed(receipt("REJECTED", null, "5"))).isFalse();
        assertThat(EngGuard.qualityPassed(receipt("REJECTED", "{\"result\":\"更换后合格\"}", "5"))).isTrue();
    }

    @Test
    void materialReadyRequiresSufficientGoodReceiptsAndNoUnresolved() {
        // 足量合格 → 齐全
        assertThat(EngGuard.materialReady(List.of(fact(1, 10, false, List.of(receipt("OK", null, "10")))), null))
                .isTrue();
        // 未到齐 → 不齐
        assertThat(EngGuard.materialReady(List.of(fact(1, 10, false, List.of(receipt("OK", null, "8")))), null))
                .isFalse();
        // 三位小数容差（蓝本 good+0.0001>=quantity 即视为齐）
        assertThat(EngGuard.materialReady(List.of(fact(1, 10, false,
                List.of(receipt("OK", null, "9.999")))), null)).isFalse();
        assertThat(EngGuard.materialReady(List.of(fact(1, 10, false,
                List.of(receipt("OK", null, "9.9999")))), null)).isTrue();
        // 有未闭环不合格 → 不齐（即使数量已够）
        assertThat(EngGuard.materialReady(List.of(fact(1, 10, false, List.of(
                receipt("OK", null, "10"), receipt("REJECTED", null, "2")))), null)).isFalse();
        // 已取消材料不参与
        assertThat(EngGuard.materialReady(List.of(fact(1, 10, true, List.of())), null)).isTrue();
        // 节点维度过滤：只校验指定节点
        assertThat(EngGuard.materialReady(List.of(
                fact(1, 10, false, List.of(receipt("OK", null, "10"))),   // 节点 1 齐
                fact(2, 5, false, List.of(receipt("OK", null, "1")))), 1L)).isTrue();
    }

    private static EngGuard.MaterialFact fact(long nodeId, double quantity, boolean cancelled,
                                              List<FrEngSupplyReceiptDO> receipts) {
        return new EngGuard.MaterialFact(100 + nodeId, nodeId, BigDecimal.valueOf(quantity), cancelled, receipts);
    }

    @Test
    void actionWhitelistFollowsBlueprint() {
        FrEngClosureDO delivered = new FrEngClosureDO().setDeliveredAt(LocalDate.now());
        FrEngClosureDO archived = new FrEngClosureDO().setDeliveredAt(LocalDate.now())
                .setArchivedAt(java.time.OffsetDateTime.now());
        // 交付后可办
        EngGuard.requireActionAllowed(delivered, "settle");
        EngGuard.requireActionAllowed(delivered, "material-terms");
        EngGuard.requireActionAllowed(delivered, "collect");
        EngGuard.requireActionAllowed(delivered, "dunning");
        EngGuard.requireActionAllowed(delivered, "project");
        // 交付后不可施工
        assertThatThrownBy(() -> EngGuard.requireActionAllowed(delivered, "node"))
                .hasMessageContaining("无权办理本工程");
        assertThatThrownBy(() -> EngGuard.requireActionAllowed(delivered, "materials"))
                .hasMessageContaining("无权办理本工程");
        // 归档后仅 terms/collect/dunning
        EngGuard.requireActionAllowed(archived, "material-terms");
        assertThatThrownBy(() -> EngGuard.requireActionAllowed(archived, "settle"))
                .hasMessageContaining("无权办理本工程");
        // 未交付不设限
        EngGuard.requireActionAllowed(new FrEngClosureDO(), "node");
        EngGuard.requireActionAllowed(null, "anything");
    }

    @Test
    void moneyAndQuantityBoundaries() {
        assertThat(EngGuard.requirePositiveCents(new BigDecimal("1"))).isEqualTo(100);
        assertThat(EngGuard.requirePositiveCents(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(EngGuard.requirePositiveCents(new BigDecimal("100000000"))).isEqualTo(10_000_000_000L);
        assertThatThrownBy(() -> EngGuard.requirePositiveCents(new BigDecimal("0")))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> EngGuard.requirePositiveCents(new BigDecimal("100000001")))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(EngGuard.requireQuantity(new BigDecimal("0.001"))).isNotNull();
        assertThatThrownBy(() -> EngGuard.requireQuantity(new BigDecimal("0.0001")))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> EngGuard.requireQuantity(new BigDecimal("-1")))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
}
