package cn.iocoder.yudao.module.platform.operations.controller.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.platform.operations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Tag(name="平台组织运营")
@RequestMapping("/platform/operations")
@PreAuthorize("@ss.hasRole('super_admin')")
public class PlatformOperationsController {
    private final OrganizationProfiles profiles;
    private final OrganizationCapabilities capabilities;
    private final TaskOperationsQuery tasks;
    private final TaskReconciliation reconciliation;
    private final OperationsGuard guard;
    private final JdbcTemplate jdbc;
    public PlatformOperationsController(OrganizationProfiles profiles,OrganizationCapabilities capabilities,TaskOperationsQuery tasks,
          TaskReconciliation reconciliation,OperationsGuard guard,JdbcTemplate jdbc){this.profiles=profiles;this.capabilities=capabilities;this.tasks=tasks;this.reconciliation=reconciliation;this.guard=guard;this.jdbc=jdbc;}
    @GetMapping("/organizations/{orgId}/profile")
    public CommonResult<OrganizationProfiles.Profile> profile(@PathVariable long orgId){return success(profiles.get(orgId));}
    @PostMapping("/organizations/{orgId}/profile")
    public CommonResult<OrganizationProfiles.Profile> profile(@PathVariable long orgId,@RequestBody OrganizationProfiles.Update input){return success(profiles.update(orgId,body(input)));}
    @PostMapping("/organizations/{orgId}/owner")
    public CommonResult<OrganizationProfiles.Profile> owner(@PathVariable long orgId,@RequestBody OrganizationProfiles.Transfer input){return success(profiles.transfer(orgId,body(input)));}
    @GetMapping("/organizations/{orgId}/owner-candidates")
    public CommonResult<List<Map<String,Object>>> ownerCandidates(@PathVariable long orgId,@RequestParam(defaultValue="")String keyword) {
        var actor=guard.require();guard.organization(actor,orgId,true);
        if(keyword.length()>100)throw new ServiceException(400,"搜索内容最多100字");
        String search="%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return success(jdbc.queryForList("""
          SELECT u.id::text AS id,u.nickname AS name,u.mobile FROM system_users u
          WHERE u.tenant_id=? AND u.status=0 AND u.deleted=0
          AND EXISTS(SELECT 1 FROM pf_staff_post p WHERE p.user_id=u.id AND p.org_id=? AND p.status='ACTIVE')
          AND NOT EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND ur.deleted=0 AND r.deleted=0 AND r.code='super_admin')
          AND CONCAT_WS(' ',u.nickname,u.mobile) LIKE ? ESCAPE '!' ORDER BY u.nickname,u.id LIMIT 50
          """,actor.tenantId(),orgId,search));
    }
    @GetMapping("/organizations/{orgId}/capabilities")
    public CommonResult<OrganizationCapabilities.Policy> capabilities(@PathVariable long orgId){return success(capabilities.get(orgId));}
    @PostMapping("/organizations/{orgId}/capabilities")
    public CommonResult<OrganizationCapabilities.Policy> capabilities(@PathVariable long orgId,@RequestBody OrganizationCapabilities.Update input){return success(capabilities.update(orgId,body(input)));}
    @GetMapping("/organizations/{orgId}/audits")
    public CommonResult<PageResult<Map<String,Object>>> audits(@PathVariable long orgId,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize) {
        var actor=guard.require();guard.organization(actor,orgId,false);
        if(pageNo<1||pageNo>100000||pageSize<1||pageSize>100)throw new ServiceException(400,"分页参数无效");
        String from="""
          FROM (SELECT id,tenant_id,org_id,actor_id,action,reason,created_at AS create_time,before_data::text,after_data::text FROM pf_ops_action_audit
          UNION ALL SELECT id,tenant_id,org_id,actor_id,action,reason,create_time,NULL::text,NULL::text FROM pf_org_admin_audit) a
          LEFT JOIN system_users u ON u.id=a.actor_id AND u.tenant_id=a.tenant_id
          WHERE a.tenant_id=? AND a.org_id=?
          """;
        Long total=jdbc.queryForObject("SELECT COUNT(*) "+from,Long.class,actor.tenantId(),orgId);
        var rows=jdbc.queryForList("SELECT a.*,COALESCE(u.nickname,a.actor_id::text) AS actor_name "+from+" ORDER BY a.create_time DESC,a.id DESC LIMIT ? OFFSET ?",actor.tenantId(),orgId,pageSize,(pageNo-1)*pageSize);
        return success(new PageResult<>(rows,total==null?0L:total));
    }
    @GetMapping("/tasks")
    public CommonResult<PageResult<Map<String,Object>>> tasks(@RequestParam(required=false)Long orgId,@RequestParam(required=false)String subject,
        @RequestParam(required=false)String keyword,@RequestParam(defaultValue="true")boolean onlyAnomalies,
        @RequestParam(required=false)String caseStatus,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize) {
        return success(tasks.page(orgId,subject,keyword,onlyAnomalies,caseStatus,pageNo,pageSize));
    }
    @GetMapping("/tasks/{taskKey}")
    public CommonResult<Map<String,Object>> task(@PathVariable String taskKey){return success(tasks.detail(taskKey));}
    @PostMapping("/tasks/{taskKey}/review")
    public CommonResult<Map<String,Object>> review(@PathVariable String taskKey,@RequestBody TaskReconciliation.Review input){return success(reconciliation.review(taskKey,body(input)));}
    private static <T>T body(T input){if(input==null)throw new ServiceException(400,"请填写操作内容");return input;}
}
