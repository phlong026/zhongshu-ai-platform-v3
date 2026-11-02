package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 内容素材透传（基-1 通道，D-1.5-9：CONTENT_ASSET 类型 jpeg/png/mp3/mp4 ≤50MB，B_END 免审）。
 * 与 franchise-asset 同款：以组织服务账号身份签发票据（鉴权=登录+任职，对象级校验在端口实现侧）。
 */
@Tag(name = "加盟 - 内容创作（素材）")
@RestController
@RequestMapping("/franchise/content/assets")
@Validated
public class ContentAssetController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private AssetAccessPort assets;
    @Resource
    private StoreServiceAccountPort serviceAccounts;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/upload-tickets")
    @Operation(summary = "签发素材上传票据（CONTENT_ASSET，B_END 免审）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> uploadTicket(@RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        long account = serviceAccounts.requireServiceAccount(post.orgId());
        var ticket = assets.createUploadTicket(account, "CONTENT_ASSET",
                String.valueOf(body.get("mimeType")),
                Long.parseLong(String.valueOf(body.get("sizeBytes"))),
                body.get("sha256") == null ? null : String.valueOf(body.get("sha256")));
        return success(Map.of("assetId", ticket.assetId(), "uploadUrl", ticket.uploadUrl()));
    }

    @PostMapping("{assetId}/complete")
    @Operation(summary = "上传完成（免审直 ACCEPTED）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> complete(@PathVariable("assetId") Long assetId) {
        PostSnapshot post = post();
        long account = serviceAccounts.requireServiceAccount(post.orgId());
        var completed = assets.completeUpload(account, assetId);
        return success(Map.of("assetId", completed.assetId(), "status", completed.uploadStatus()));
    }

    @PostMapping("{assetId}/download-tickets")
    @Operation(summary = "素材下载地址（生产=COS 签名 URL，供 provider 拉取）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> downloadTicket(@PathVariable("assetId") Long assetId) {
        PostSnapshot post = post();
        long account = serviceAccounts.requireServiceAccount(post.orgId());
        var view = assets.createDownloadUrl(account, assetId);
        return success(Map.of("downloadUrl", view.downloadUrl(), "expiresAt",
                view.expiresAt() == null ? 0L : view.expiresAt()));
    }

}
