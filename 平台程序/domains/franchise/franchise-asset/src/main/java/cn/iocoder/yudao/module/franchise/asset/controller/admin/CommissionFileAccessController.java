package cn.iocoder.yudao.module.franchise.asset.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort.Slot;
import cn.iocoder.yudao.module.franchise.asset.AssetAccessService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 登录岗位及委托文件范围同时校验；通用资产接口仍保持属主组织边界。 */
@RestController
@RequestMapping("/franchise/commissions/{commissionId}/dispatches/{dispatchSeq}/files/{slot}")
public class CommissionFileAccessController {
    private final AssetAccessService access;
    private final AssetAccessPort assets;

    public CommissionFileAccessController(AssetAccessService access, AssetAccessPort assets) {
        this.access = access;
        this.assets = assets;
    }

    @PostMapping("/download-tickets")
    @Operation(summary = "签发委托下发清单中指定文件的一次性下载票据")
    public CommonResult<AssetAccessPort.DownloadUrlView> ticket(@PathVariable long commissionId,
            @PathVariable int dispatchSeq, @PathVariable String slot) {
        return success(assets.createCommissionDownloadUrl(access.actor(access.post()), commissionId,
                dispatchSeq, Slot.valueOf(slot.toUpperCase(Locale.ROOT))));
    }
}
