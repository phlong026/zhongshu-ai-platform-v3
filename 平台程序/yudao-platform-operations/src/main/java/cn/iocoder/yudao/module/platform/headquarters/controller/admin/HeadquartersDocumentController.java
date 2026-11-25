package cn.iocoder.yudao.module.platform.headquarters.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Request;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/headquarters")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class HeadquartersDocumentController {
    private final AssetAccessPort assets;
    public HeadquartersDocumentController(AssetAccessPort assets) { this.assets = assets; }

    @GetMapping("/customers/{id}/workflow/{step}/assets/{asset}/content")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public ResponseEntity<byte[]> customer(@PathVariable long id, @PathVariable String step, @PathVariable long asset) {
        return content(new Request(Resource.CUSTOMER, id, step, asset));
    }

    @GetMapping("/projects/{id}/documents/{document}/content")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public ResponseEntity<byte[]> document(@PathVariable long id, @PathVariable long document) {
        return content(new Request(Resource.PROJECT, id, "DOCUMENT", document));
    }

    @GetMapping("/projects/{id}/assets/{asset}/content")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public ResponseEntity<byte[]> project(@PathVariable long id, @PathVariable long asset, @RequestParam String section) {
        return content(new Request(Resource.PROJECT, id, section, asset));
    }

    @GetMapping("/finance/{kind}/{id}/assets/{asset}/content")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public ResponseEntity<byte[]> voucher(@PathVariable String kind, @PathVariable long id, @PathVariable long asset) {
        Resource resource = switch (kind) {
            case "receipts" -> Resource.RECEIPT;
            case "payments" -> Resource.PAYMENT;
            case "refunds" -> Resource.REFUND;
            default -> throw new ServiceException(400, "财务资料类别无效");
        };
        return content(new Request(resource, id, "VOUCHER", asset));
    }

    private ResponseEntity<byte[]> content(Request request) {
        var file = assets.readHeadquartersDocument(request);
        String extension = switch (file.mimeType()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw new ServiceException(400, "资料格式暂不支持预览");
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=\"business-document." + extension + "\"")
                .contentType(MediaType.parseMediaType(file.mimeType())).body(file.bytes());
    }
}
