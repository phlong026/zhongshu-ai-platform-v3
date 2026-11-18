package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.*;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngProjectController.*;

@RestController
@RequestMapping("/franchise/engineering/{id}")
public class EngSiteControlController {
    public record CameraBody(Long cameraId,String name,String location,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.fasterxml.jackson.databind.deser.std.StringDeserializer.class) String url,
        Boolean enabled) { }
    private final CurrentPost current;
    private final EngProgressService progress;
    private final EngCameraService cameras;
    public EngSiteControlController(CurrentPost current,EngProgressService progress,EngCameraService cameras){this.current=current;this.progress=progress;this.cameras=cameras;}
    @GetMapping("/progress/handlers")
    @PreAuthorize("@post.has('"+EngineeringPermissions.QUERY+"')")
    public CommonResult<List<Map<String,Object>>> handlers(@PathVariable long id){return success(progress.handlers(current.require(),id));}
    @PostMapping("/nodes/{nodeId}/delay")
    @PreAuthorize("@post.has('"+EngineeringPermissions.WRITE+"')")
    public CommonResult<Map<String,Object>> delay(@PathVariable long id,@PathVariable long nodeId,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key){
        return success(progress.save(current.require(),id,nodeId,new EngProgressService.DelayCommand(str(body.get("reason")),longOrNull(body.get("ownerUserId")),localDate(body.get("recoveryDate")),str(body.get("action"))),requireIdem(key)));
    }
    @GetMapping("/cameras")
    @PreAuthorize("@post.has('"+EngineeringPermissions.QUERY+"')")
    public CommonResult<List<Map<String,Object>>> cameras(@PathVariable long id){return success(cameras.list(current.require(),id));}
    @PostMapping("/cameras")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable=false,responseEnable=false)
    @PreAuthorize("@post.has('"+EngineeringPermissions.WRITE+"')")
    public CommonResult<List<Map<String,Object>>> cameraSave(@PathVariable long id,@RequestBody CameraBody body,@RequestHeader(value="Idempotency-Key",required=false)String key){
        return success(cameras.save(current.require(),id,new EngCameraService.CameraCommand(body.cameraId(),body.name(),body.location(),body.url(),!Boolean.FALSE.equals(body.enabled())),requireIdem(key)));
    }
    @GetMapping("/cameras/{cameraId}/playback")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(responseEnable=false)
    @PreAuthorize("@post.has('"+EngineeringPermissions.QUERY+"')")
    public CommonResult<Map<String,Object>> playback(@PathVariable long id,@PathVariable long cameraId,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return success(cameras.playback(current.require(),id,cameraId));}
}
