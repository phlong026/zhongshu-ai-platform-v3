package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/** Generic HLS binding. We do not proxy URLs or infer device availability from a saved address. */
@Service
public class EngCameraService {
    private final EngProjectService projects;
    private final JdbcTemplate jdbc;
    private final FranchiseCommands commands;
    public EngCameraService(EngProjectService projects,JdbcTemplate jdbc,FranchiseCommands commands){this.projects=projects;this.jdbc=jdbc;this.commands=commands;}
    private void readable(PostSnapshot post,long projectId){
        var p=projects.requireProject(post,projectId);
        if(!EngProjectService.isFinanceOrAbove(post))EngGuard.requireOwner(p,post.userId());
    }
    public List<Map<String,Object>> list(PostSnapshot post,long projectId){
        readable(post,projectId);
        return jdbc.queryForList("SELECT id::text,name,location,enabled,(stream_url<>'') AS configured FROM fr_eng_camera WHERE company_id=? AND project_id=? ORDER BY create_time,id",post.orgId(),projectId);
    }
    public Map<String,Object> playback(PostSnapshot post,long projectId,long id){
        readable(post,projectId);
        var rows=jdbc.queryForList("SELECT stream_url FROM fr_eng_camera WHERE company_id=? AND project_id=? AND id=? AND enabled=TRUE",post.orgId(),projectId,id);
        if(rows.isEmpty()||String.valueOf(rows.get(0).get("stream_url")).isBlank())throw exception(ENGINEERING_INPUT_INVALID,"摄像头尚未接入或已停用");
        return Map.of("url",rows.get(0).get("stream_url"),"protocol","HLS");
    }
    public record CameraCommand(Long id,String name,String location,String url,Boolean enabled) { }
    @Transactional(rollbackFor=Exception.class)
    public List<Map<String,Object>> save(PostSnapshot post,long projectId,CameraCommand cmd,String key){
        var project=projects.requireOwned(post,projectId,"camera");
        String url=cmd.url()==null?"":cmd.url().trim();validateUrl(url);
        var payload=new LinkedHashMap<String,Object>();payload.put("cameraId",cmd.id());payload.put("name",cmd.name());payload.put("location",cmd.location());payload.put("enabled",cmd.enabled());
        // Camera addresses may contain provider tokens: store only their digest in the audit log.
        payload.put("urlDigest",cn.hutool.crypto.digest.DigestUtil.sha256Hex(url));
        if(commands.replayed(post,key,payload).isPresent())return list(post,projectId);
        if(cmd.name()==null||cmd.name().isBlank()||cmd.name().length()>80||cmd.location()==null||cmd.location().isBlank()||cmd.location().length()>120)
            throw exception(ENGINEERING_INPUT_INVALID,"请填写摄像头名称和安装位置");
        boolean enabled=!Boolean.FALSE.equals(cmd.enabled());
        if(cmd.id()==null){
            if(jdbc.queryForObject("SELECT count(*) FROM fr_eng_camera WHERE company_id=? AND project_id=?",Integer.class,post.orgId(),projectId)>=20)
                throw exception(ENGINEERING_INPUT_INVALID,"每个项目最多绑定20个摄像头");
            jdbc.update("INSERT INTO fr_eng_camera(id,company_id,project_id,name,location,stream_url,enabled,author_user_id) VALUES(?,?,?,?,?,?,?,?)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),post.orgId(),projectId,cmd.name().trim(),cmd.location().trim(),url,enabled,post.userId());
        }else{
            int count=jdbc.update("UPDATE fr_eng_camera SET name=?,location=?,stream_url=CASE WHEN ?='' THEN stream_url ELSE ? END,enabled=?,update_time=now() WHERE id=? AND company_id=? AND project_id=?",cmd.name().trim(),cmd.location().trim(),url,url,enabled,cmd.id(),post.orgId(),projectId);
            if(count!=1)throw exception(ENGINEERING_INPUT_INVALID,"摄像头不存在或无权修改");
        }
        commands.record(post,EngPlanService.AGGREGATE,projectId,projects.bumpRevision(post,project),"eng-camera-save",payload,key);
        return list(post,projectId);
    }
    static void validateUrl(String raw){
        if(raw.isBlank())return; // Binding may be prepared before a camera supplier is selected.
        try{
            URI uri=URI.create(raw);
            if(raw.length()>2048||!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||!uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"))throw new IllegalArgumentException();
        }catch(RuntimeException e){throw exception(ENGINEERING_INPUT_INVALID,"请填写 HTTPS 的 HLS（.m3u8）播放地址");}
    }
}
