package cn.iocoder.yudao.module.franchise.asset.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.asset.AssetAccessService;
import cn.iocoder.yudao.module.franchise.asset.LocalAssetService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** B 字节链路仍使用 ADMIN 登录和有效岗位；票据是附加授权，不能代替会话。 */
@RestController
@Slf4j
@RequestMapping("/franchise/assets")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class AssetContentController {
    private final AssetAccessService access;
    private final LocalAssetService assets;

    public AssetContentController(AssetAccessService access, LocalAssetService assets) {
        this.access = access;
        this.assets = assets;
    }

    @PutMapping("/{assetId}/content")
    public CommonResult<Boolean> upload(@PathVariable long assetId, @RequestParam String token,
                                        HttpServletRequest request) {
        var actor = access.actor(access.post());
        try { assets.receiveUpload(actor, assetId, token, request.getInputStream()); }
        catch (IOException e) {
            log.warn("B upload request failed org={} asset={} error={}", actor.orgId(), assetId, e.getClass().getSimpleName());
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(1_071_000_001, "上传请求读取失败");
        }
        return CommonResult.success(true);
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> download(@PathVariable long assetId, @RequestParam String token) {
        var result = assets.readDownload(access.actor(access.post()), assetId, token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment")
                .contentType(MediaType.parseMediaType(result.mimeType())).body(result.bytes());
    }
}
