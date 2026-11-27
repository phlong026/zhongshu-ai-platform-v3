package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司进度计划模板：正式开工里程碑 +《合家美宅进度计划表》一级计划（12 类施工节点）。
 * 主体层按项目层数展开（1-5 层钳制，缺省 2）后共 13-17 节点；模板只定节点结构与顺序，
 * 兼容模板保留原编号；无节点付款引用的新工程可去掉两条已由 prepare 办理的准备项。
 * 工期日期由工程人员按实际安排填写，远期可留空。
 * 规则来源：engineering-records.js:16-28（recordFlow 唯一基准）。
 */
public final class EngTemplate {

    /** 一级计划条目（perFloor=按层数展开；topping=主体封顶里程碑） */
    private record Stage(String name, boolean perFloor, boolean topping) {
    }

    private static final Stage[] TEMPLATE = {
            new Stage("开工准备", false, false),
            new Stage("施工准备", false, false),
            new Stage("主材确认", false, false),
            new Stage("基础工程", false, false),
            new Stage("主体层", true, false),
            new Stage("封顶验收", false, true),
            new Stage("屋面施工", false, false),
            new Stage("外墙包柱、内外粉", false, false),
            new Stage("门窗安装", false, false),
            new Stage("外墙涂料、文化石", false, false),
            new Stage("收尾工程", false, false),
            new Stage("竣工验收", false, false),
    };

    /** 展开后的节点草稿（日期留空待填；nodeIndex 从 1 起） */
    public static List<NodeDraft> buildNodes(Integer floors) {
        int n = Math.max(1, Math.min(5, floors == null ? 2 : floors));
        List<NodeDraft> rows = new ArrayList<>();
        rows.add(new NodeDraft(1, "正式开工", "START"));
        for (Stage stage : TEMPLATE) {
            if (stage.perFloor()) {
                for (int i = 1; i <= n; i++) {
                    rows.add(new NodeDraft(rows.size() + 1, i + "层主体", null));
                }
            } else {
                rows.add(new NodeDraft(rows.size() + 1, stage.name(), stage.topping() ? "TOPPING" : null));
            }
        }
        if (rows.stream().noneMatch(r -> "TOPPING".equals(r.milestone()))) {
            throw new IllegalStateException("模板缺少封顶验收节点");
        }
        return rows;
    }

    /** Only used after proving the signed payment plan has no legacy/node-index dependency. Never renumber. */
    public static List<NodeDraft> buildEssentialNodes(Integer floors) {
        return buildNodes(floors).stream().filter(node -> !"开工准备".equals(node.name())
                && !"施工准备".equals(node.name())).toList();
    }

    public record NodeDraft(int nodeIndex, String name, String milestone) {
    }

    /** 快照行（含日期与状态；版本 JSONB 的完整节点清单） */
    public record PlanSnapshotRow(int nodeIndex, String name, String milestone,
                                  LocalDate startDate, LocalDate endDate, String status) {

        public static PlanSnapshotRow from(NodeDraft draft) {
            return new PlanSnapshotRow(draft.nodeIndex(), draft.name(), draft.milestone(), null, null, "PENDING");
        }
    }

    /** 快照 JSON（写路径由调用方经 JdbcTemplate CAST(? AS jsonb) 落库） */
    public static String snapshotJson(List<PlanSnapshotRow> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            PlanSnapshotRow r = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"nodeIndex\":").append(r.nodeIndex())
                    .append(",\"name\":").append(JsonUtils.toJsonString(r.name()))
                    .append(",\"milestone\":").append(r.milestone() == null ? "null" : JsonUtils.toJsonString(r.milestone()))
                    .append(",\"startDate\":").append(r.startDate() == null ? "null" : "\"" + r.startDate() + "\"")
                    .append(",\"endDate\":").append(r.endDate() == null ? "null" : "\"" + r.endDate() + "\"")
                    .append(",\"status\":").append(r.status() == null ? "null" : "\"" + r.status() + "\"")
                    .append('}');
        }
        return json.append(']').toString();
    }
}
