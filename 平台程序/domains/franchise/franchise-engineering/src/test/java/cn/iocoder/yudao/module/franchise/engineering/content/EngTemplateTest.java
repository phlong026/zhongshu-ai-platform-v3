package cn.iocoder.yudao.module.franchise.engineering.content;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 标准一级计划模板（12 类按层数展开，蓝本 :17/:24-28）：层数钳制、封顶节点恒在。 */
class EngTemplateTest {

    @Test
    void buildsTwelveCategoryPlanWithFloorExpansion() {
        List<EngTemplate.NodeDraft> nodes = EngTemplate.buildNodes(2);
        assertThat(nodes).hasSize(13); // 11 固定类 + 2 层主体（蓝本 nodes() :17）
        assertThat(nodes.get(0).name()).isEqualTo("开工准备");
        assertThat(nodes.get(4).name()).isEqualTo("1层主体");
        assertThat(nodes.get(5).name()).isEqualTo("2层主体");
        assertThat(nodes.get(6).name()).isEqualTo("封顶验收");
        assertThat(nodes.get(6).milestone()).isEqualTo("TOPPING");
        assertThat(nodes.get(12).name()).isEqualTo("竣工验收");
    }

    @Test
    void clampsFloorsToOneToFive() {
        assertThat(EngTemplate.buildNodes(null)).hasSize(13); // 缺省 2 层
        assertThat(EngTemplate.buildNodes(0)).hasSize(12);   // 钳制 1 层
        assertThat(EngTemplate.buildNodes(1)).hasSize(12);
        assertThat(EngTemplate.buildNodes(5)).hasSize(16);
        assertThat(EngTemplate.buildNodes(9)).hasSize(16);
    }

    @Test
    void nodeIndexesAreSequentialFromOne() {
        List<EngTemplate.NodeDraft> nodes = EngTemplate.buildNodes(3);
        for (int i = 0; i < nodes.size(); i++) {
            assertThat(nodes.get(i).nodeIndex()).isEqualTo(i + 1);
        }
        assertThat(nodes.stream().filter(n -> "TOPPING".equals(n.milestone())).count()).isEqualTo(1);
    }

    @Test
    void snapshotJsonKeepsDatesAndStatus() {
        List<EngTemplate.PlanSnapshotRow> rows = List.of(
                new EngTemplate.PlanSnapshotRow(1, "开工准备", null,
                        java.time.LocalDate.of(2026, 11, 3), java.time.LocalDate.of(2026, 11, 10), "DONE"),
                new EngTemplate.PlanSnapshotRow(2, "1层主体", null, null, null, "PENDING"));
        String json = EngTemplate.snapshotJson(rows);
        assertThat(json).contains("\"nodeIndex\":1").contains("\"name\":\"开工准备\"")
                .contains("\"startDate\":\"2026-11-03\"").contains("\"status\":\"DONE\"")
                .contains("\"startDate\":null").contains("\"milestone\":null");
    }

    @Test
    void toppingNodeAlwaysPresentAfterClamping() {
        // buildNodes 内置防御断言：模板缺封顶节点直接抛 IllegalStateException（此处固化正常路径恒有 topping）
        for (Integer floors : java.util.Arrays.asList(null, 0, 1, 3, 5, 9)) {
            assertThat(EngTemplate.buildNodes(floors))
                    .anyMatch(n -> "TOPPING".equals(n.milestone()));
        }
    }
}
