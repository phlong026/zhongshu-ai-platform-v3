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
        assertThat(nodes).hasSize(14); // 11 固定类 + 2 层主体（蓝本 nodes() :17）
        assertThat(nodes.get(0).name()).isEqualTo("正式开工");
        assertThat(nodes.get(5).name()).isEqualTo("1层主体");
        assertThat(nodes.get(6).name()).isEqualTo("2层主体");
        assertThat(nodes.get(7).name()).isEqualTo("封顶验收");
        assertThat(nodes.get(7).milestone()).isEqualTo("TOPPING");
        assertThat(nodes.get(13).name()).isEqualTo("竣工验收");
    }

    @Test
    void clampsFloorsToOneToFive() {
        assertThat(EngTemplate.buildNodes(null)).hasSize(14); // 缺省 2 层
        assertThat(EngTemplate.buildNodes(0)).hasSize(13);   // 钳制 1 层
        assertThat(EngTemplate.buildNodes(1)).hasSize(13);
        assertThat(EngTemplate.buildNodes(5)).hasSize(17);
        assertThat(EngTemplate.buildNodes(9)).hasSize(17);
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
    void essentialTemplateRemovesOnlyTheTwoPreparationRowsWithoutRenumbering() {
        for (int floors = 1; floors <= 5; floors++) {
            var legacy = EngTemplate.buildNodes(floors);
            var essential = EngTemplate.buildEssentialNodes(floors);
            assertThat(essential).hasSize(legacy.size() - 2).allMatch(legacy::contains);
            assertThat(essential).noneMatch(node -> "开工准备".equals(node.name()) || "施工准备".equals(node.name()));
            assertThat(essential.get(0)).isEqualTo(legacy.get(0));
            assertThat(essential.get(1).nodeIndex()).isEqualTo(4);
            assertThat(essential.stream().filter(node -> "TOPPING".equals(node.milestone())).toList())
                    .isEqualTo(legacy.stream().filter(node -> "TOPPING".equals(node.milestone())).toList());
        }
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
