package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Step;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowRules;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.franchise.commission.ExternalCommissionModel.*;

/** A saved measurement is the immutable SAVE event; completing or reopening it adds no new measurements. */
final class ExternalCommissionMeasurements {
    private static final TypeReference<Map<String,Object>> FIELDS = new TypeReference<>() {};
    private static final String SOURCE = """
            SELECT e.id,e.revision,w.customer_id,e.occurred_on,e.fields,e.attachments,e.note
            FROM fr_customer_workflow w JOIN fr_customer_workflow_event e
              ON e.workflow_id=w.id AND e.tenant_id=w.tenant_id AND e.company_id=w.company_id
            WHERE w.tenant_id=? AND w.company_id=? AND w.customer_id=?
              AND e.step_code='MEASUREMENT' AND e.action='SAVE'
              AND e.occurred_on IS NOT NULL AND e.fields @> '{"checked":true}'::jsonb
              AND jsonb_array_length(e.attachments)>0
            """;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<AssetEvidenceQueryPort> evidence;

    ExternalCommissionMeasurements(JdbcTemplate jdbc, ObjectProvider<AssetEvidenceQueryPort> evidence) {
        this.jdbc = jdbc;
        this.evidence = evidence;
    }

    MeasurementPage list(long tenant, long company, long customer, Long before, int pageSize) {
        if ((before != null && before <= 0) || pageSize < 1 || pageSize > 50) throw invalid("测量记录分页参数无效");
        var rows = jdbc.query(SOURCE + " AND (CAST(? AS bigint) IS NULL OR e.id<?) ORDER BY e.id DESC LIMIT ?",
                ExternalCommissionMeasurements::row, tenant, company, customer, before, before, pageSize + 1);
        var page = rows.subList(0, Math.min(pageSize, rows.size()));
        var qualified = page.stream().filter(ExternalCommissionMeasurements::checked).toList();
        Set<Long> ids = qualified.stream().flatMap(item -> item.attachments().stream()).map(file -> Long.valueOf(file.assetId())).collect(Collectors.toSet());
        Map<Long,Long> owners = Map.of();
        if (!ids.isEmpty()) {
            var query = evidence.getIfAvailable();
            if (query == null) throw new ServiceException(503, "测量原件校验暂不可用");
            owners = query.acceptedOwners(tenant, ids, Set.of("BUSINESS_DOCUMENT"));
        }
        var accepted = owners;
        var items = qualified.stream().filter(item -> item.attachments().stream()
                .allMatch(file -> Objects.equals(accepted.get(Long.valueOf(file.assetId())), company))).toList();
        String next = rows.size() > pageSize ? page.get(page.size() - 1).eventId() : null;
        return new MeasurementPage(Long.toString(customer), items, next);
    }

    MeasurementRef require(long tenant, long company, long customer, MeasurementRef selection) {
        var rows = jdbc.query(SOURCE + " AND e.id=? AND e.revision=?", ExternalCommissionMeasurements::row,
                tenant, company, customer, Long.parseLong(selection.eventId()), selection.revision());
        if (rows.size() != 1 || !checked(rows.get(0))) throw invalid("所选测量版本不属于该客户或尚未核对");
        return rows.get(0);
    }

    static MeasurementRef selection(MeasurementRef input) {
        if (input == null) return null;
        if (input.eventId() == null || !input.eventId().matches("[1-9][0-9]{0,18}") || input.revision() == null || input.revision() < 1)
            throw invalid("测量记录编号或版本无效");
        try {
            return MeasurementRef.identity(Long.toString(Long.parseLong(input.eventId())), input.revision(), input.includeSourceNote());
        } catch (NumberFormatException invalidId) {
            throw invalid("测量记录编号无效");
        }
    }

    private static boolean checked(MeasurementRef value) {
        return CustomerWorkflowRules.missing(Step.MEASUREMENT, value.occurredOn(), value.fields(), value.attachments()).isEmpty();
    }

    private static MeasurementRef row(ResultSet rs, int ignored) throws SQLException {
        String note = rs.getString("note");
        return new MeasurementRef(rs.getString("id"), rs.getLong("revision"), rs.getString("customer_id"),
                rs.getObject("occurred_on", LocalDate.class), JsonUtils.parseObject(rs.getString("fields"), FIELDS),
                JsonUtils.parseArray(rs.getString("attachments"), Attachment.class), note == null || note.isBlank() ? null : note, null);
    }

    private static ServiceException invalid(String message) { return new ServiceException(400, message); }
}
