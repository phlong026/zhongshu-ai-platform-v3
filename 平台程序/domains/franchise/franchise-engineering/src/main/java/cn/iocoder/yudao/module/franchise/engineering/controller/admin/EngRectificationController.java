package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.content.EngRectificationService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/engineering/{project}/rectifications")
public class EngRectificationController {
    private final EngRectificationService service;
    private final CurrentPost current;
    public EngRectificationController(EngRectificationService service,CurrentPost current){this.service=service;this.current=current;}
    @GetMapping @PreAuthorize("@post.has('franchise:engineering:query')")
    public CommonResult<?> list(@PathVariable long project){return success(service.list(current.require(),project));}
    @PostMapping @PreAuthorize("@post.has('franchise:engineering:write')")
    public CommonResult<?> change(@PathVariable long project,@RequestBody EngRectificationService.Change cmd,@RequestHeader("Idempotency-Key") String key){
        return success(service.change(current.require(),project,cmd,key));
    }
}
