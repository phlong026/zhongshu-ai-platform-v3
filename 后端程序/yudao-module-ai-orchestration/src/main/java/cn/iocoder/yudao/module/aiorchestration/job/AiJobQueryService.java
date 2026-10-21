package cn.iocoder.yudao.module.aiorchestration.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 任务只读查询（App/Admin 共用）
 *
 * 2026-10-09 运营需求④：后台生成记录补列——耗时（ai_job_attempt 终止时间 − 创建时间）、
 * 最近一次失败原因。客户昵称不进本查询（App 链路与跨模块测试共用 baseSelect），
 * 由管理端 {@link #nicknames} 批量解析后在 Controller 回填，仅作只读展示。
 */
@Service
public class AiJobQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AiJobQueryService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 批量解析账号昵称（identity account 表，跨模块只读；无账号/已删除不返回键） */
    public Map<Long, String> nicknames(java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, nickname FROM account WHERE id IN (" + placeholders + ") AND deleted = FALSE",
                userIds.toArray());
        Map<Long, String> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object nickname = row.get("nickname");
            if (nickname != null && !String.valueOf(nickname).isBlank()) {
                result.put(((Number) row.get("id")).longValue(), String.valueOf(nickname));
            }
        }
        return result;
    }

    /** 最近一次有记录的尝试所用的提示词与模型；仅管理端详情使用，列表与小程序视图都不带 */
    public record PromptRecord(String promptText, String providerModel) {
    }

    public Optional<PromptRecord> promptRecord(long jobId) {
        List<PromptRecord> rows = jdbcTemplate.query(
                "SELECT prompt_text, provider_model FROM ai_job_attempt "
                        + "WHERE job_id = ? AND prompt_text IS NOT NULL ORDER BY attempt_no DESC LIMIT 1",
                (rs, i) -> new PromptRecord(rs.getString("prompt_text"), rs.getString("provider_model")), jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AiJobOrchestrationService.JobSnapshot> getJob(long jobId) {
        List<AiJobOrchestrationService.JobSnapshot> rows = jdbcTemplate.query(baseSelect()
                + " WHERE ai_job.id = ? AND ai_job.deleted = FALSE", (rs, i) -> mapRow(rs), jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long countJobs(String status, String phase) {
        Filter filter = filter(status, phase);
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_job" + filter.sql(), Long.class, filter.args().toArray());
        return n == null ? 0 : n;
    }

    public List<AiJobOrchestrationService.JobSnapshot> pageJobs(String status, String phase,
                                                                int pageNo, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        Filter filter = filter(status, phase);
        List<Object> args = new java.util.ArrayList<>(filter.args());
        args.add(size);
        args.add((long) Math.max(pageNo - 1, 0) * size);
        return jdbcTemplate.query(baseSelect() + filter.sql()
                        + " ORDER BY ai_job.create_time DESC, ai_job.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapRow(rs), args.toArray());
    }

    static String baseSelect() {
        return "SELECT ai_job.id, ai_job.user_id, ai_job.phase, ai_job.status, "
                + "ai_job.requested_count, ai_job.accepted_count, ai_job.progress, "
                + "ai_job.cancel_seq, ai_job.project_ref, ai_job.unit_point_cost, ai_job.total_point_cost, "
                + "ai_job.create_time, "
                + "COALESCE(ai_job.input_snapshot -> 'imageOptions' ->> 'resolution', '2K') AS resolution, "
                + "COALESCE(ai_job.input_snapshot -> 'imageOptions' ->> 'orientation', "
                + "CASE WHEN ai_job.phase='FLAT' THEN 'PORTRAIT' ELSE 'LANDSCAPE' END) AS orientation, "
                + "(SELECT SUM(s.refunded_points) FROM ai_job_settlement s WHERE s.job_id = ai_job.id "
                + "AND s.deleted = FALSE) AS refunded_points, "
                + "(SELECT MAX(s.create_time) FROM ai_job_settlement s WHERE s.job_id = ai_job.id "
                + "AND s.deleted = FALSE) AS finished_at, "
                + "(SELECT EXTRACT(EPOCH FROM (MAX(at.finished_at) - ai_job.create_time))::int "
                + "FROM ai_job_attempt at WHERE at.job_id = ai_job.id AND at.finished_at IS NOT NULL) "
                + "AS duration_seconds, "
                + "(SELECT at.last_error FROM ai_job_attempt at WHERE at.job_id = ai_job.id "
                + "AND at.last_error IS NOT NULL ORDER BY at.attempt_no DESC LIMIT 1) AS last_error "
                + "FROM ai_job";
    }

    /** 筛选条件与其绑定参数；状态/阶段来自管理端查询参数，一律走参数绑定，不拼进 SQL 文本 */
    private record Filter(String sql, List<Object> args) {
    }

    private Filter filter(String status, String phase) {
        StringBuilder sql = new StringBuilder(" WHERE ai_job.deleted = FALSE");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND ai_job.status = ?");
            args.add(status);
        }
        if (phase != null && !phase.isBlank()) {
            sql.append(" AND ai_job.phase = ?");
            args.add(phase);
        }
        return new Filter(sql.toString(), args);
    }

    static AiJobOrchestrationService.JobSnapshot mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean terminal = List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED").contains(rs.getString("status"));
        return new AiJobOrchestrationService.JobSnapshot(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("phase"), rs.getString("status"),
                rs.getInt("requested_count"), rs.getInt("accepted_count"), terminal ? 100 : rs.getInt("progress"),
                rs.getObject("cancel_seq") == null ? "ACTIVE" : "CANCEL_REQUESTED", rs.getString("project_ref"),
                rs.getObject("unit_point_cost", Long.class), rs.getObject("total_point_cost", Long.class),
                rs.getObject("refunded_points") == null ? null : rs.getBigDecimal("refunded_points").longValueExact(),
                rs.getTimestamp("create_time").toInstant(),
                terminal && rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null,
                rs.getString("resolution"), rs.getString("orientation"),
                rs.getObject("duration_seconds") == null ? null : rs.getInt("duration_seconds"),
                rs.getString("last_error"));
    }

}
