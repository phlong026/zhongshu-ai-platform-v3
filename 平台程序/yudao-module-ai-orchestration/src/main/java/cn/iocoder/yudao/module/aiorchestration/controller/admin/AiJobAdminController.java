package cn.iocoder.yudao.module.aiorchestration.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.aiorchestration.controller.app.AppAiJobController;
import cn.iocoder.yudao.module.aiorchestration.controller.app.vo.AppAiJobRespVO;
import cn.iocoder.yudao.module.aiorchestration.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 任务运维")
@RestController
@RequestMapping("/design/v1/ai-jobs")
public class AiJobAdminController {

    private final cn.iocoder.yudao.module.aiorchestration.job.AiJobQueryService queryService;

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricingPort;

    public AiJobAdminController(cn.iocoder.yudao.module.aiorchestration.job.AiJobQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "任务分页查询：状态、阶段；AI 运营不可扣点/审批/公开案例")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AI_JOB_QUERY + "')")
    public CommonResult<PageResult<AppAiJobRespVO>> getJobPage(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AppAiJobRespVO> result = new PageResult<>();
        result.setTotal(queryService.countJobs(status, phase));
        List<AppAiJobRespVO> list = queryService.pageJobs(status, phase, pageNo, pageSize)
                .stream().map(AiJobAdminController::toAdminVo).toList();
        fillNicknames(list);
        result.setList(list);
        return success(result);
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "任务详情：attempt、租约、结果校验与结算信息；含提示词调用实扣点数")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AI_JOB_QUERY + "')")
    public CommonResult<AppAiJobRespVO> getJob(@PathVariable("jobId") String jobId) {
        var job = queryService.getJob(Long.parseLong(jobId))
                .orElse(null);
        if (job == null) {
            return success(null);
        }
        var vo = toAdminVo(job);
        // 提示词费与生图扣点分账展示：生图前文本模型按 AI_PROMPT 实扣， biz_id 与任务号同键
        vo.setPromptPointCost(usagePricingPort.chargedPointCost("AI_PROMPT", "ai_job", String.valueOf(job.jobId())));
        // 提示词含用户补充需求的衍生文本且可达 8000 字：只在详情里按需取，不进列表
        queryService.promptRecord(job.jobId()).ifPresent(record -> {
            vo.setPromptText(record.promptText());
            vo.setProviderModel(record.providerModel());
        });
        fillNicknames(List.of(vo));
        return success(vo);
    }

    /** 管理端视图：在客户端视图基础上补回仅运营可见的耗时与失败原因（内部报错原文不下发小程序） */
    public static AppAiJobRespVO toAdminVo(cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService.JobSnapshot job) {
        AppAiJobRespVO vo = AppAiJobController.toVo(job);
        vo.setDurationSeconds(job.durationSeconds());
        vo.setLastError(job.lastError());
        return vo;
    }

    /** 生成记录客户昵称回填：一页一次批量解析，查不到昵称的账号回退展示 userId */
    private void fillNicknames(List<AppAiJobRespVO> list) {
        var nicknameMap = queryService.nicknames(list.stream()
                .map(vo -> Long.parseLong(vo.getUserId())).toList());
        for (AppAiJobRespVO vo : list) {
            vo.setNickname(nicknameMap.get(Long.parseLong(vo.getUserId())));
        }
    }

}
