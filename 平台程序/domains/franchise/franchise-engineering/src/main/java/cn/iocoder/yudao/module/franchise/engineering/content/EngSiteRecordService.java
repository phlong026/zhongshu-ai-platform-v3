package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSiteRecordDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSiteRecordMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 现场记录：普通记录（note+多图资产）与更正（原值入 corrections；completion 记录不可在此更正，
 * 走节点更正——蓝本 site-correct :81）。前置=已实际开工。
 */
@Service
public class EngSiteRecordService {

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngSiteRecordMapper siteRecords;
    private final FranchiseCommands commands;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public EngSiteRecordService(EngProjectService projectService, FrEngPlanNodeMapper planNodes,
                                FrEngSiteRecordMapper siteRecords, FranchiseCommands commands,
                                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.projectService = projectService;
        this.planNodes = planNodes;
        this.siteRecords = siteRecords;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SiteRecordCommand(long nodeId, LocalDate occurredAt, String note, List<Long> fileAssetIds) {
    }

    public record CorrectCommand(String note, List<Long> fileAssetIds, String reason) {
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(PostSnapshot post, long projectId, SiteRecordCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "site");
        // 重放输入=落账输入（评审十三 P1-3）：nodeId+note 两处同形
        Map<String, Object> payload = Map.of("nodeId", cmd.nodeId(), "note", StrUtil.nullToEmpty(cmd.note()));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return Map.of("records", list(post, projectId));
        }
        if (project.getActualStartAt() == null || StrUtil.isBlank(cmd.note())
                || cmd.fileAssetIds() == null || cmd.fileAssetIds().isEmpty()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请在原施工节点填写现场记录并留存照片");
        }
        LocalDate date = cmd.occurredAt() == null ? LocalDate.now() : cmd.occurredAt();
        if (date.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "现场记录日期不能晚于今天");
        }
        boolean nodeExists = planNodes.selectByProject(post.orgId(), projectId).stream()
                .anyMatch(n -> n.getId() == cmd.nodeId());
        if (!nodeExists) {
            throw exception(ENGINEERING_INPUT_INVALID, "请选择本项目的施工节点");
        }
        jdbcTemplate.update("INSERT INTO fr_eng_site_record (id, company_id, project_id, node_id, "
                        + "occurred_at, note, completion, file_asset_ids, author_user_id, create_time) "
                        + "VALUES (?,?,?,?,?,FALSE,CAST(? AS jsonb),?,now())",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(), post.orgId(), projectId, cmd.nodeId(),
                date, StrUtil.trim(cmd.note()),
                EngPlanService.JsonList.of(cmd.fileAssetIds().toArray()), post.userId());
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, projectService.requireProject(post, projectId)),
                "eng-site-record", payload, idempotencyKey);
        return Map.of("records", list(post, projectId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> correct(PostSnapshot post, long recordId, CorrectCommand cmd, String idempotencyKey) {
        FrEngSiteRecordDO record = siteRecords.selectById(recordId);
        if (record == null || record.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "现场记录不存在");
        }
        projectService.requireOwned(post, record.getProjectId(), "site");
        var replayed = commands.replayed(post, idempotencyKey, Map.of("recordId", recordId));
        if (replayed.isPresent()) {
            return Map.of("records", list(post, record.getProjectId()));
        }
        if (Boolean.TRUE.equals(record.getCompletion())) {
            throw exception(ENGINEERING_INPUT_INVALID, "这是节点完成记录，请在本节点完成记录中更正");
        }
        if (StrUtil.isBlank(cmd.reason()) || StrUtil.isBlank(cmd.note())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写更正原因和现场情况");
        }
        String corrections = EngPlanService.JsonList.append(record.getCorrections(), Map.of(
                "note", StrUtil.nullToEmpty(record.getNote()), "reason", cmd.reason(),
                "date", LocalDate.now().toString(), "actorUserId", post.userId()));
        if (cmd.fileAssetIds() != null && !cmd.fileAssetIds().isEmpty()) {
            jdbcTemplate.update("UPDATE fr_eng_site_record SET note = ?, corrections = CAST(? AS jsonb), "
                            + "file_asset_ids = CAST(? AS jsonb) WHERE id = ? AND company_id = ?",
                    StrUtil.trim(cmd.note()), corrections,
                    EngPlanService.JsonList.of(cmd.fileAssetIds().toArray()), recordId, post.orgId());
        } else {
            jdbcTemplate.update("UPDATE fr_eng_site_record SET note = ?, corrections = CAST(? AS jsonb) "
                    + "WHERE id = ? AND company_id = ?", StrUtil.trim(cmd.note()), corrections, recordId, post.orgId());
        }
        commands.record(post, AGGREGATE, record.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, record.getProjectId())),
                "eng-site-correct",
                Map.of("recordId", recordId), idempotencyKey);
        return Map.of("records", list(post, record.getProjectId()));
    }

    public List<Map<String, Object>> list(PostSnapshot post, long projectId) {
        projectService.requireProject(post, projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrEngSiteRecordDO r : siteRecords.selectByProject(post.orgId(), projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("nodeId", r.getNodeId());
            row.put("occurredAt", r.getOccurredAt());
            row.put("note", r.getNote());
            row.put("completion", r.getCompletion());
            row.put("result", r.getResult());
            row.put("reviewMode", r.getReviewMode());
            row.put("fileAssetIds", r.getFileAssetIds());
            row.put("corrections", r.getCorrections());
            rows.add(row);
        }
        return rows;
    }
}
