package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import static cn.iocoder.yudao.module.franchise.api.EngineeringRectificationAssignments.ACTIVE_POSTS;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.ENGINEERING_INPUT_INVALID;

@Service
public class EngRectificationService {
    public record Change(String action, Long issueId, Long nodeId, String title, String note, Long ownerUserId, LocalDate dueDate) {}
    // Keys recorded by the first version hash these five fields in their original serialization order.
    private record LegacyChange(String action, Long issueId, Long nodeId, String title, String note) {}

    private static final String ISSUE = """
            SELECT r.id::text,r.title,r.state,n.name node,r.owner_user_id::text AS "ownerUserId",r.due_date::text AS "dueDate",
                   (SELECT e.note FROM fr_eng_rectification_event e WHERE e.issue_id=r.id ORDER BY e.create_time DESC,e.id DESC LIMIT 1) note
            FROM fr_eng_rectification r LEFT JOIN fr_eng_plan_node n ON n.id=r.node_id AND n.company_id=r.company_id AND n.project_id=r.project_id
            WHERE r.company_id=? AND r.project_id=?
            """;
    private final JdbcTemplate jdbc;
    private final EngProjectService projects;
    private final FranchiseCommands commands;
    private final SystemDirectory directory;

    public EngRectificationService(JdbcTemplate jdbc, EngProjectService projects, FranchiseCommands commands, SystemDirectory directory) {
        this.jdbc=jdbc;
        this.projects=projects;
        this.commands=commands;
        this.directory=directory;
    }

    private void requireRead(PostSnapshot post,long project) {
        var current=projects.requireProject(post,project);
        if (!Set.of("boss","owner").contains(post.roleCode())) EngGuard.requireOwner(current,post.userId());
    }

    public List<Map<String,Object>> list(PostSnapshot post,long project) {
        requireRead(post,project);
        var rows=jdbc.queryForList(ISSUE+" ORDER BY (r.state='CLOSED'),r.create_time DESC,r.id DESC",post.orgId(),project);
        addNames(rows);
        return rows;
    }

    public List<Map<String,String>> handlers(PostSnapshot post,long project) {
        var p=projects.requireProject(post,project);
        EngGuard.requireOwner(p,post.userId());
        var ids=jdbc.queryForList("SELECT DISTINCT s.user_id "+ACTIVE_POSTS+
                " AND s.org_id=? AND s.user_id IN (?,?) ORDER BY s.user_id",Long.class,post.orgId(),p.getManagerUserId(),p.getAssignedEngineerUserId());
        var names=new HashMap<Long,String>();
        if (!ids.isEmpty()) directory.usersByIds(ids).forEach(u->names.put(u.userId(),u.name()));
        return ids.stream().map(id->Map.of("id",id.toString(),"name",Objects.toString(names.get(id),"人员 "+id))).toList();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(PostSnapshot post,long project,long issue) {
        requireRead(post,project);
        var matches=jdbc.queryForList(ISSUE+" AND r.id=?",post.orgId(),project,issue);
        if (matches.isEmpty()) throw exception(ENGINEERING_INPUT_INVALID,"该项目下未找到原整改单");
        var history=jdbc.queryForList("""
            SELECT e.id::text,e.action,e.note,e.actor_id::text AS "actorUserId",e.owner_user_id::text AS "ownerUserId",
                   e.due_date::text AS "dueDate",to_char(e.create_time AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') AS "occurredAt"
            FROM fr_eng_rectification_event e
            WHERE e.issue_id=? ORDER BY e.create_time,e.id
            """,issue);
        var row=matches.get(0);
        var people=new ArrayList<>(history);
        people.add(row);
        addNames(people);
        row.put("history",history);
        return row;
    }

    private void addNames(List<Map<String,Object>> rows) {
        var ids=new LinkedHashSet<Long>();
        for (var row:rows) for (String field:List.of("ownerUserId","actorUserId")) {
            if (row.get(field)!=null) ids.add(Long.valueOf(row.get(field).toString()));
        }
        if (ids.isEmpty()) return;
        var names=new HashMap<String,String>();
        directory.usersByIds(new ArrayList<>(ids)).forEach(user->names.put(Long.toString(user.userId()),user.name()));
        for (var row:rows) {
            row.put("ownerName",names.get(row.get("ownerUserId")));
            if (row.containsKey("actorUserId")) row.put("actorName",names.get(row.get("actorUserId")));
        }
    }

    @Transactional
    public long change(PostSnapshot post,long project,Change cmd,String key) {
        key=commands.requireIdempotencyKey(key);
        jdbc.queryForList("SELECT id FROM fr_eng_project WHERE id=? AND company_id=? FOR UPDATE",project,post.orgId());
        var current=projects.requireProject(post,project);
        EngGuard.requireOwner(current,post.userId());
        var input=new LinkedHashMap<String,Object>();
        input.put("project",project);
        input.put("change",cmd.ownerUserId()==null && cmd.dueDate()==null
                ? new LegacyChange(cmd.action(),cmd.issueId(),cmd.nodeId(),cmd.title(),cmd.note()) : cmd);
        var replay=commands.replayed(post,key,input);
        if (replay.isPresent()) return replay.get().getAggregateId();

        String note=cmd.note()==null?"":cmd.note().strip();
        if (note.isEmpty()||note.length()>800) throw exception(ENGINEERING_INPUT_INVALID,"请填写800字以内的现场记录");
        long id;
        if ("create".equals(cmd.action())) {
            projects.requireOwned(post,project,"node");
            requireAssignment(current,cmd);
            if (cmd.title()==null||cmd.title().isBlank()||cmd.title().length()>160) throw exception(ENGINEERING_INPUT_INVALID,"请填写整改事项");
            if (cmd.nodeId()!=null && jdbc.queryForList("SELECT id FROM fr_eng_plan_node WHERE id=? AND project_id=? AND company_id=?",cmd.nodeId(),project,post.orgId()).isEmpty())
                throw exception(ENGINEERING_INPUT_INVALID,"请选择本项目节点");
            id=IdWorker.getId();
            jdbc.update("""
                INSERT INTO fr_eng_rectification(id,company_id,project_id,node_id,title,state,created_by,owner_user_id,due_date)
                VALUES(?,?,?,?,?,'OPEN',?,?,?)
                """,id,post.orgId(),project,cmd.nodeId(),cmd.title().strip(),post.userId(),cmd.ownerUserId(),cmd.dueDate());
        } else {
            if (cmd.issueId()==null) throw exception(ENGINEERING_INPUT_INVALID,"请选择原整改单");
            id=cmd.issueId();
            int changed;
            if ("assign".equals(cmd.action())) {
                requireAssignment(current,cmd);
                changed=jdbc.update("""
                    UPDATE fr_eng_rectification SET owner_user_id=?,due_date=?
                    WHERE company_id=? AND project_id=? AND id=? AND state IN ('OPEN','REVIEW')
                    """,cmd.ownerUserId(),cmd.dueDate(),post.orgId(),project,id);
            } else {
                if (cmd.ownerUserId()!=null || cmd.dueDate()!=null) throw exception(ENGINEERING_INPUT_INVALID,"请通过分派变更负责人或期限");
                String from,next;
                switch (String.valueOf(cmd.action())) {
                    case "submit" -> {from="OPEN";next="REVIEW";}
                    case "pass" -> {from="REVIEW";next="CLOSED";}
                    case "return" -> {from="REVIEW";next="OPEN";}
                    default -> throw exception(ENGINEERING_INPUT_INVALID,"无效整改操作");
                }
                changed=jdbc.update("UPDATE fr_eng_rectification SET state=? WHERE company_id=? AND project_id=? AND id=? AND state=?",
                        next,post.orgId(),project,id,from);
            }
            if (changed!=1) throw exception(ENGINEERING_INPUT_INVALID,"整改状态已变化，请刷新");
        }
        jdbc.update("""
            INSERT INTO fr_eng_rectification_event(id,issue_id,actor_id,action,note,owner_user_id,due_date,create_time)
            SELECT ?,r.id,?,?,?,r.owner_user_id,r.due_date,clock_timestamp() FROM fr_eng_rectification r
            WHERE r.id=? AND r.company_id=? AND r.project_id=?
            """,IdWorker.getId(),post.userId(),cmd.action(),note,id,post.orgId(),project);
        int version=jdbc.queryForObject("SELECT count(*) FROM fr_eng_rectification_event WHERE issue_id=?",Integer.class,id);
        commands.record(post,"site-rectification",id,version,"rectification-"+cmd.action(),input,key);
        return id;
    }

    private void requireAssignment(FrEngProjectDO project,Change cmd) {
        if (cmd.ownerUserId()==null || !(cmd.ownerUserId().equals(project.getManagerUserId()) || cmd.ownerUserId().equals(project.getAssignedEngineerUserId()))
                || jdbc.queryForList("SELECT s.id "+ACTIVE_POSTS+" AND s.org_id=? AND s.user_id=? ORDER BY s.id FOR SHARE OF s",
                    Long.class,project.getCompanyId(),cmd.ownerUserId()).isEmpty())
            throw exception(ENGINEERING_INPUT_INVALID,"请选择本项目仍有有效工程任职的负责人或指定工程师");
        if (cmd.dueDate()==null) throw exception(ENGINEERING_INPUT_INVALID,"请填写整改期限");
    }
}
