package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppAssetDownloadTicketRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppAssetUploadTicketReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppAssetUploadTicketRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序/后台 - 资产上传与下载")
@RestController
@RequestMapping("/design/v1/assets")
@PermitAll // 自有 user_session Bearer 校验在每个方法内强制执行（requireAccountId）
public class AppAssetController {

    @Resource
    private AssetService assetService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping("/upload-tickets")
    @Operation(summary = "申请私有存储上传凭证：服务端按资产类型强制 MIME/大小，object key 由服务端生成")
    public CommonResult<AppAssetUploadTicketRespVO> createUploadTicket(
            @Valid @RequestBody AppAssetUploadTicketReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        AssetService.UploadTicket ticket = assetService.createUploadTicket(userId,
                reqVO.getAssetType(), reqVO.getMimeType(), reqVO.getSizeBytes(),
                reqVO.getSha256().toLowerCase());
        AppAssetUploadTicketRespVO vo = new AppAssetUploadTicketRespVO();
        vo.setAssetId(String.valueOf(ticket.assetId()));
        vo.setUploadUrl(ticket.uploadUrl());
        vo.setHeaders(Map.of("Content-Type", reqVO.getMimeType()));
        return success(vo);
    }

    @PostMapping("/{assetId}/complete")
    @Operation(summary = "上传完成：服务端校验 object、大小、SHA-256、魔数、像素、EXIF 剥离与内容审核")
    public CommonResult<Boolean> completeUpload(@PathVariable("assetId") String assetId,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        String status = assetService.completeUpload(userId, Long.parseLong(assetId));
        return success("ACCEPTED".equals(status));
    }

    @PostMapping("/{assetId}/download-tickets")
    @Operation(summary = "申请一次性下载票据：对象级权限（所有者或公开展示授权）+ 扫描门禁；匿名仅限公开展示授权资产（需求拍板：首页精选封面未登录可看）")
    public CommonResult<AppAssetDownloadTicketRespVO> createDownloadTicket(
            @PathVariable("assetId") String assetId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = resolveAccountId(authorization);
        var ticket = assetService.requestDownloadTicket(userId, Long.parseLong(assetId));
        AppAssetDownloadTicketRespVO vo = new AppAssetDownloadTicketRespVO();
        vo.setTicketId(ticket.getToken());
        vo.setDownloadUrl("pending-ticket-consumption");
        vo.setExpiresAt(LocalDateTime.ofInstant(ticket.getExpiresAt(), ZoneOffset.UTC));
        return success(vo);
    }

    @PostMapping("/{assetId}/downloads")
    @Operation(summary = "凭一次性票据换短期签名下载 URL（公开授权撤回后立即拒绝；匿名仅限公开展示授权资产）")
    public CommonResult<Map<String, Object>> resolveDownload(
            @PathVariable("assetId") String assetId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = resolveAccountId(authorization);
        String url = assetService.resolveDownloadTicket(userId, Long.parseLong(assetId), body.get("ticket"));
        return success(Map.of("downloadUrl", url, "expiresInSeconds", 300));
    }

    @GetMapping("/{assetId}/thumbnail")
    @Operation(summary = "缩略图（长边 480 JPEG；稳定 URL，列表/首页封面专用，客户端可按 Cache-Control 缓存）")
    public ResponseEntity<byte[]> getThumbnail(
            @PathVariable("assetId") String assetId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return rendition(assetId, "thumbnail", authorization);
    }

    @GetMapping("/{assetId}/preview")
    @Operation(summary = "预览图（长边 1280 JPEG；详情页大图专用，原图仍走一次性票据下载）")
    public ResponseEntity<byte[]> getPreview(
            @PathVariable("assetId") String assetId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return rendition(assetId, "preview", authorization);
    }

    /** 图片衍生版本统一出口：权限与一次性票据链路一致，但 URL 只含 assetId，可被客户端长期缓存。 */
    private ResponseEntity<byte[]> rendition(String assetId, String variant, String authorization) {
        long userId = resolveAccountId(authorization);
        try {
            AssetService.Rendition rendition = assetService.readRendition(userId, Long.parseLong(assetId), variant);
            // 公开案例图缓存 1 小时：足够覆盖一次浏览会话，又能在下架后 1 小时内自然失效
            // （原票据链路是 5 分钟签名 URL，缓存期是这里唯一放宽的时效；如需立即下架，缩短该值）。
            CacheControl cache = rendition.publicDisplay()
                    ? CacheControl.maxAge(Duration.ofHours(1)).cachePublic()
                    : CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(cache)
                    .body(rendition.bytes());
        } catch (NumberFormatException | ServiceException unavailable) {
            // 不存在/无权限/未生成统一 404：图片端点不返回 JSON 错误体，避免 <image> 把错误体当图片解码
            return ResponseEntity.notFound().build();
        }
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    /**
     * 下载读链路的匿名通道（需求 2026-10-16 拍板：未登录/未激活访客必须能看首页精选效果图）。
     * 无会话或会话失效/受限时按匿名哨兵 0 处理：0 永远不会命中 owner，只能通过
     * PUBLIC_DISPLAY 公开展示授权读到资产，且票据签发与解析两端实时校验、可随时撤回。
     * 上传/完成等其他端点仍走 requireAccountId 强制会话，不受影响。
     */
    private long resolveAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        if (token == null || token.isBlank()) {
            return 0L;
        }
        try {
            return identitySessionPort.requireUnrestricted(token).accountId();
        } catch (RuntimeException invalidOrRestrictedSession) {
            return 0L;
        }
    }

}
