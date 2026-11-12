package cn.iocoder.yudao.module.franchise.asset.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.asset.AssetAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * B 端资产通道（基-1，14 文档 §四）：财务凭证/工程图纸/现场照片/合同附件四类 B_END 类型的
 * 通用票据链（签发→上传→complete→下载）。融合模式兼容原资产，B 模式使用自有资产表，
 * 属主=本组织服务账号（组织 scope 硬隔离）；登录+任职即可（D-基1-6），
 * 类型级岗位管控随消费域批次挂其域端点。
 */
@Tag(name = "加盟协同 - B 端资产")
@RestController
@RequestMapping("/franchise/assets")
public class AssetAccessController {

    private final AssetAccessService access;

    public AssetAccessController(AssetAccessService access) {
        this.access = access;
    }

    public record UploadTicketReq(String assetType, String mimeType, long sizeBytes, String sha256) {
    }

    @PostMapping("/upload-tickets")
    @Operation(summary = "签发上传票据（业务附件类型、MIME、大小与摘要校验）")
    public CommonResult<AssetAccessPort.UploadTicketView> uploadTicket(@RequestBody UploadTicketReq req) {
        return success(access.uploadTicket(access.post(), req.assetType(), req.mimeType(), req.sizeBytes(), req.sha256()));
    }

    @PostMapping("/{assetId}/complete")
    @Operation(summary = "上传完成：完整性与内容安全检查；终态重放幂等读")
    public CommonResult<AssetAccessPort.AssetCompleted> complete(@PathVariable("assetId") long assetId) {
        return success(access.completeUpload(access.post(), assetId));
    }

    @PostMapping("/{assetId}/download-tickets")
    @Operation(summary = "签发短期下载地址，B 模式使用一次性票据与有效岗位校验")
    public CommonResult<AssetAccessPort.DownloadUrlView> downloadTicket(@PathVariable("assetId") long assetId) {
        return success(access.createDownloadUrl(access.post(), assetId));
    }

}
