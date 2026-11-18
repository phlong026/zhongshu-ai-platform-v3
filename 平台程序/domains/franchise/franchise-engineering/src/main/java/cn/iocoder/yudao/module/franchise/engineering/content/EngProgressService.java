package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.engineering.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

@Service
public class EngProgressService {
    private final EngProjectService projects;
    private final FrEngPlanNodeMapper nodes;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbc;
    private final SystemDirectory directory;
    public EngProgressService(EngProjectService projects, FrEngPlanNodeMapper nodes, FranchiseCommands commands, JdbcTemplate jdbc, SystemDirectory directory) {
        this.projects=projects; this.nodes=nodes; this.commands=commands; this.jdbc=jdbc; this.directory=directory;
    }
    public List<Map<String,Object>> handlers(PostSnapshot post, long id) {
        var project=projects.requireProject(post,id);
        if(!EngProjectService.isFinanceOrAbove(post)) EngGuard.requireOwner(project,post.userId());
        var ids=new LinkedHashSet<Long>();ids.add(project.getManagerUserId());
        if(project.getAssignedEngineerUserId()!=null)ids.add(project.getAssignedEngineerUserId());
        return directory.usersByIds(new ArrayList<>(ids)).stream().map(u->Map.<String,Object>of("id",String.valueOf(u.userId()),"name",Objects.toString(u.name(),"未填写姓名"))).toList();
    }
    public record DelayCommand(String reason, Long ownerUserId, LocalDate recoveryDate, String action) { }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(PostSnapshot post,long projectId,long nodeId,DelayCommand cmd,String key) {
        var project=projects.requireOwned(post,projectId,"delay");
        var payload=new LinkedHashMap<String,Object>();payload.put("nodeId",nodeId);payload.put("reason",cmd.reason());
        payload.put("ownerUserId",cmd.ownerUserId());payload.put("recoveryDate",cmd.recoveryDate());payload.put("action",cmd.action());
        if(commands.replayed(post,key,payload).isPresent())return projects.detail(post,projectId);
        var node=nodes.selectByProject(post.orgId(),projectId).stream().filter(n->n.getId()==nodeId).findFirst().orElseThrow(()->exception(ENGINEERING_INPUT_INVALID));
        if(!EngProgress.overdue(node,LocalDate.now()))throw exception(ENGINEERING_INPUT_INVALID,"仅未完成的延期节点需要登记处理");
        if(cmd.reason()==null||cmd.reason().isBlank()||cmd.reason().length()>1000||cmd.action()==null||cmd.action().isBlank()||cmd.action().length()>1000)
            throw exception(ENGINEERING_INPUT_INVALID,"请填写延期原因和处理措施（各不超过1000字）");
        if(cmd.ownerUserId()==null||(!Objects.equals(cmd.ownerUserId(),project.getManagerUserId())&&!Objects.equals(cmd.ownerUserId(),project.getAssignedEngineerUserId())))
            throw exception(ENGINEERING_INPUT_INVALID,"请选择本项目负责人或指定工程师");
        if(cmd.recoveryDate()==null||cmd.recoveryDate().isBefore(LocalDate.now()))throw exception(ENGINEERING_INPUT_INVALID,"预计完成日期不能早于今天");
        payload.put("recordedAt",java.time.OffsetDateTime.now().toString());payload.put("actorUserId",post.userId());
        payload.put("deviationDays",EngProgress.deviationDays(node,LocalDate.now()));
        payload.put("ownerName",directory.usersByIds(List.of(cmd.ownerUserId())).stream().map(SystemDirectory.UserBrief::name).filter(Objects::nonNull).findFirst().orElse(""));
        String history=EngPlanService.JsonList.append(node.getDelayHistory(),payload);
        jdbc.update("UPDATE fr_eng_plan_node SET delay_reason=?,delay_owner_user_id=?,recovery_date=?,recovery_action=?,delay_history=CAST(? AS jsonb),update_time=now() WHERE id=? AND company_id=? AND project_id=?",
            cmd.reason().trim(),cmd.ownerUserId(),cmd.recoveryDate(),cmd.action().trim(),history,nodeId,post.orgId(),projectId);
        payload.remove("recordedAt");payload.remove("actorUserId");payload.remove("deviationDays");payload.remove("ownerName");
        commands.record(post,EngPlanService.AGGREGATE,projectId,projects.bumpRevision(post,project),"eng-delay-handle",payload,key);
        return projects.detail(post,projectId);
    }
}
