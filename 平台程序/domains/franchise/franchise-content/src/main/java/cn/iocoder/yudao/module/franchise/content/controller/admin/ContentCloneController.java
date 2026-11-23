package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentCloneService;
import cn.iocoder.yudao.module.franchise.content.content.ContentCloneUploadService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/content")
public class ContentCloneController {
    private final ContentCloneService clones;
    private final CurrentPost posts;
    private final ContentCloneUploadService uploads;
    public ContentCloneController(ContentCloneService clones,CurrentPost posts,ContentCloneUploadService uploads) {this.clones=clones;this.posts=posts;this.uploads=uploads;}
    public record Create(String kind,String title,long sourceUploadId,String consentVersion,long confirmedCredits,Long sourceAssetId) { }
    public record Upload(String kind,String mimeType,long sizeBytes,String sha256) { }
    @PostMapping("/personas/{personaId}/clone-upload-tickets")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> upload(@PathVariable("personaId") long id,@RequestBody Upload body) {
        var post=posts.require();clones.assertAccess(post,body.kind());
        return success(uploads.issue(post,id,body.kind(),body.mimeType(),body.sizeBytes(),body.sha256()));
    }
    public record FileFormat(String kind,String mimeType) {}
    public record FileReceipt(long sizeBytes,String sha256) {}
    public record AssetUpload(String kind, long assetId) {}

    @PostMapping("/personas/{personaId}/clone-asset-uploads")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> assetUpload(@PathVariable("personaId") long id, @RequestBody AssetUpload body) {
        var post = posts.require(); clones.assertAccess(post, body.kind());
        return success(uploads.importAsset(post, id, body.kind(), body.assetId()));
    }

    @PostMapping("/personas/{personaId}/clone-file-tickets")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> fileTicket(@PathVariable("personaId") long id,@RequestBody FileFormat body) {
        var post=posts.require();
        clones.assertAccess(post,body.kind());
        return success(uploads.prepareFileUpload(post,id,body.kind(),body.mimeType()));
    }

    @PostMapping("/clone-uploads/{id}/file-receipt")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> fileReceipt(@PathVariable("id") long id,@RequestBody FileReceipt body) {
        return success(uploads.completeFileUpload(posts.require(),id,body.sizeBytes(),body.sha256()));
    }

    @PostMapping("/clone-uploads/{id}/complete")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> complete(@PathVariable("id") long id) {return success(uploads.complete(posts.require(),id));}
    @GetMapping("/clones/quote")
    @PreAuthorize("@post.has('"+ContentPermissions.QUERY+"')")
    public CommonResult<Map<String,Object>> quote(@RequestParam("kind") String kind) {return success(clones.quote(posts.require(),kind));}
    @GetMapping("/personas/{personaId}/clones")
    @PreAuthorize("@post.has('"+ContentPermissions.QUERY+"')")
    public CommonResult<List<Map<String,Object>>> list(@PathVariable("personaId") long id) {return success(clones.list(posts.require(),id));}
    @PostMapping("/personas/{personaId}/clones")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> create(@PathVariable("personaId") long id,@RequestBody Create body,@RequestHeader("Idempotency-Key") String key) {
        var post=posts.require();
        return success(body.sourceUploadId()==0&&body.sourceAssetId()!=null
                ?clones.replayLegacy(post,id,body.kind(),body.title(),body.sourceAssetId(),body.consentVersion(),key,body.confirmedCredits())
                :clones.create(post,id,body.kind(),body.title(),body.sourceUploadId(),body.consentVersion(),key,body.confirmedCredits()));
    }
    @PostMapping("/clones/{id}/refresh")
    @PreAuthorize("@post.has('"+ContentPermissions.QUERY+"')")
    public CommonResult<Map<String,Object>> refresh(@PathVariable("id") long id) {return success(clones.refresh(posts.require(),id));}
    @PutMapping("/clones/{id}/default")
    @PreAuthorize("@post.has('"+ContentPermissions.PERSONA+"','"+ContentPermissions.PERSONA_SELF+"')")
    public CommonResult<Map<String,Object>> setDefault(@PathVariable("id") long id) {return success(clones.setDefault(posts.require(),id));}
}
