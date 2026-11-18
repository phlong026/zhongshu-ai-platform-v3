package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

@Service
public class EngRectificationService {
    public record Change(String action, Long issueId, Long nodeId, String title, String note) {}
    private final JdbcTemplate jdbc;
    private final EngProjectService projects;
    private final FranchiseCommands commands;
    public EngRectificationService(JdbcTemplate jdbc,EngProjectService projects,FranchiseCommands commands){
        this.jdbc=jdbc;this.projects=projects;this.commands=commands;
    }
    public List<Map<String,Object>> list(PostSnapshot post,long project){
        var p=projects.requireProject(post,project);
        if(!Set.of("boss","owner").contains(post.roleCode()))EngGuard.requireOwner(p,post.userId());
        return jdbc.queryForList("""
            SELECT r.id::text,r.title,r.state,n.name node,
                   (SELECT e.note FROM fr_eng_rectification_event e WHERE e.issue_id=r.id ORDER BY e.create_time DESC,e.id DESC LIMIT 1) note
            FROM fr_eng_rectification r LEFT JOIN fr_eng_plan_node n ON n.id=r.node_id AND n.company_id=r.company_id
            WHERE r.company_id=? AND r.project_id=? ORDER BY (r.state='CLOSED'),r.create_time DESC,r.id DESC
            """,post.orgId(),project);
    }
    @Transactional
    public long change(PostSnapshot post,long project,Change cmd,String key){
        key=commands.requireIdempotencyKey(key);
        jdbc.queryForList("SELECT id FROM fr_eng_project WHERE id=? AND company_id=? FOR UPDATE",project,post.orgId());
        EngGuard.requireOwner(projects.requireProject(post,project),post.userId());
        var input=new LinkedHashMap<String,Object>();
        input.put("project",project);
        input.put("change",cmd);
        var replay=commands.replayed(post,key,input);
        if(replay.isPresent())return replay.get().getAggregateId();
        if ("create".equals(cmd.action())) projects.requireOwned(post,project,"node");
        String note=cmd.note()==null?"":cmd.note().strip();
        if(note.isEmpty()||note.length()>800)throw exception(ENGINEERING_INPUT_INVALID,"请填写800字以内的现场记录");
        long id;
        String next;
        if("create".equals(cmd.action())){
            if(cmd.title()==null||cmd.title().isBlank()||cmd.title().length()>160)throw exception(ENGINEERING_INPUT_INVALID,"请填写整改事项");
            if(cmd.nodeId()!=null && jdbc.queryForList("SELECT id FROM fr_eng_plan_node WHERE id=? AND project_id=? AND company_id=?",cmd.nodeId(),project,post.orgId()).isEmpty())
                throw exception(ENGINEERING_INPUT_INVALID,"请选择本项目节点");
            id=IdWorker.getId(); next="OPEN";
            jdbc.update("INSERT INTO fr_eng_rectification(id,company_id,project_id,node_id,title,state,created_by) VALUES(?,?,?,?,?,?,?)",
                id,post.orgId(),project,cmd.nodeId(),cmd.title().strip(),next,post.userId());
        }else{
            if(cmd.issueId()==null)throw exception(ENGINEERING_INPUT_INVALID,"请选择原整改单");
            id=cmd.issueId();
            String from;
            switch(String.valueOf(cmd.action())){
                case "submit" -> {from="OPEN";next="REVIEW";}
                case "pass" -> {from="REVIEW";next="CLOSED";}
                case "return" -> {from="REVIEW";next="OPEN";}
                default -> throw exception(ENGINEERING_INPUT_INVALID,"无效整改操作");
            }
            if(jdbc.update("UPDATE fr_eng_rectification SET state=? WHERE company_id=? AND project_id=? AND id=? AND state=?",next,post.orgId(),project,id,from)!=1)
                throw exception(ENGINEERING_INPUT_INVALID,"整改状态已变化，请刷新");
        }
        jdbc.update("INSERT INTO fr_eng_rectification_event(id,issue_id,actor_id,action,note) VALUES(?,?,?,?,?)",IdWorker.getId(),id,post.userId(),cmd.action(),note);
        int version=jdbc.queryForObject("SELECT count(*) FROM fr_eng_rectification_event WHERE issue_id=?",Integer.class,id);
        commands.record(post,"site-rectification",id,version,"rectification-"+cmd.action(),input,key);
        return id;
    }
}
