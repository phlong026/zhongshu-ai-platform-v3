package cn.iocoder.yudao.module.franchise.design.controller.admin;
import cn.iocoder.yudao.module.franchise.design.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@RestController
@RequestMapping("/franchise/design/flow-budget-archive")
@PreAuthorize("@post.has('"+DesignPermissions.USE+"')")
public class DesignItemizedArchiveController {
    private final DesignAccessService access;private final DesignItemizedArchiveService archive;
    public DesignItemizedArchiveController(DesignAccessService access,DesignItemizedArchiveService archive){this.access=access;this.archive=archive;}
    @PostMapping public Object save(@RequestBody DesignItemizedArchiveService.Input body,@RequestHeader("Idempotency-Key") String key){return success(archive.save(access.post(),body,key));}
}
