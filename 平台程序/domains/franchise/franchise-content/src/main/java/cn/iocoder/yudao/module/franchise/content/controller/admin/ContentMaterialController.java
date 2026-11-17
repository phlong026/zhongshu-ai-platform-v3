package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentMaterialService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/content/material-library")
public class ContentMaterialController {
    private final CurrentPost posts;
    private final ContentMaterialService service;
    public ContentMaterialController(CurrentPost posts,ContentMaterialService service) { this.posts=posts;this.service=service; }
    @GetMapping @PreAuthorize("@post.has('"+ContentPermissions.QUERY+"')")
    public CommonResult<Map<String,Object>> page(@RequestParam(defaultValue="PERSONAL") String scope,
            @RequestParam(defaultValue="ALL") String type,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int page) {
        return success(service.page(posts.require(),scope,type,keyword,page));
    }
    @PostMapping("/upload-tickets") @PreAuthorize("@post.has('"+ContentPermissions.WRITE+"')")
    public CommonResult<Map<String,Object>> upload(@RequestBody Map<String,Object> body) { return success(service.upload(posts.require(),body)); }
    @PostMapping("/{id}/complete") @PreAuthorize("@post.has('"+ContentPermissions.WRITE+"')")
    public CommonResult<Map<String,Object>> complete(@PathVariable long id) { return success(service.complete(posts.require(),id)); }
    @PostMapping("/{id}/download-tickets") @PreAuthorize("@post.has('"+ContentPermissions.QUERY+"')")
    public CommonResult<Map<String,Object>> download(@PathVariable long id) { return success(service.download(posts.require(),id)); }
    @PutMapping("/{id}") @PreAuthorize("@post.has('"+ContentPermissions.WRITE+"')")
    public CommonResult<Boolean> update(@PathVariable long id,@RequestBody Map<String,Object> body) { service.update(posts.require(),id,body);return success(true); }
    @DeleteMapping("/{id}") @PreAuthorize("@post.has('"+ContentPermissions.WRITE+"')")
    public CommonResult<Boolean> remove(@PathVariable long id) { service.remove(posts.require(),id);return success(true); }
}

