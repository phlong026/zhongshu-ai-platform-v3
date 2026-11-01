package cn.iocoder.yudao.module.franchise.asset.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
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
 * 通用票据链（签发→上传→complete→下载）。资产本体在 design asset 表（零新表），
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
    @Operation(summary = "签发上传票据（assetType ∈ B_END 四类；非法类型/MIME/大小 → design 资产校验码）")
    public CommonResult<DesignServicePort.UploadTicketView> uploadTicket(@RequestBody UploadTicketReq req) {
        return success(access.uploadTicket(access.post(), req.assetType(), req.mimeType(), req.sizeBytes(), req.sha256()));
    }

    @PostMapping("/{assetId}/complete")
    @Operation(summary = "上传完成：B_END 免审直 ACCEPTED；终态重放幂等读（REJECTED 带 rejectedReason）")
    public CommonResult<DesignServicePort.AssetCompleted> complete(@PathVariable("assetId") long assetId) {
        return success(access.completeUpload(access.post(), assetId));
    }

    @PostMapping("/{assetId}/download-tickets")
    @Operation(summary = "下载地址：COS=签名 URL+到期；local=local:// 占位（字节核验走 dev 读取端点）")
    public CommonResult<DesignServicePort.DownloadUrlView> downloadTicket(@PathVariable("assetId") long assetId) {
        return success(access.createDownloadUrl(access.post(), assetId));
    }

}
