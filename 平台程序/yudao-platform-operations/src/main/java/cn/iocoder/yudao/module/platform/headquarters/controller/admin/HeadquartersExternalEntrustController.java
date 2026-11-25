package cn.iocoder.yudao.module.platform.headquarters.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersExternalEntrustKind;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadModel.Page;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/platform/headquarters/customers/{id}/external-entrust")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class HeadquartersExternalEntrustController {
    private final HeadquartersReadService reads;
    private final AssetAccessPort assets;
    private final HeadquartersDocumentAccessPort documents;
    private final OperateLogCommonApi audit;
    public HeadquartersExternalEntrustController(HeadquartersReadService reads, AssetAccessPort assets,
            HeadquartersDocumentAccessPort documents, OperateLogCommonApi audit) {
        this.reads=reads; this.assets=assets; this.documents=documents; this.audit=audit;
    }

    @GetMapping("/{kind}")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Page> versions(@PathVariable long id, @PathVariable String kind,
            @RequestParam(defaultValue="1") int pageNo, @RequestParam(defaultValue="20") int pageSize) {
        return success(reads.externalVersions(id,kind,pageNo,pageSize));
    }

    @GetMapping("/{kind}/{version}/assets/{asset}/content")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<byte[]> content(@PathVariable long id, @PathVariable String kind,
            @PathVariable int version, @PathVariable long asset) {
        String section=HeadquartersExternalEntrustKind.fromPath(kind).section(version);
        var request=new HeadquartersDocumentAccessPort.Request(HeadquartersDocumentAccessPort.Resource.CUSTOMER,id,section,asset);
        var scope=documents.requireReadable(request);
        var file=assets.readHeadquartersDocument(request);
        String extension=switch (file.mimeType()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw new ServiceException(400,"资料格式暂不支持预览");
        };
        var event=new OperateLogCreateReqDTO();
        event.setUserId(scope.readerUserId());event.setUserType(UserTypeEnum.ADMIN.getValue());
        event.setType("总部外部委托资料读取");event.setSubType(kind);event.setBizId(id);
        event.setAction("读取外部委托资料；版本="+version);
        event.setExtra(JsonUtils.toJsonString(Map.of("tenantId",Long.toString(scope.tenantId()),
                "postId",Long.toString(scope.readerPostId()),"companyId",Long.toString(scope.ownerOrgId()),
                "customerId",Long.toString(id),"kind",kind,"version",version,"assetId",Long.toString(asset))));
        event.setRequestMethod("GET");event.setRequestUrl("/platform/headquarters/customers/"+id+"/external-entrust/"+kind+"/"+version+"/assets/"+asset+"/content");
        String ip=ServletUtils.getClientIP(),agent=ServletUtils.getUserAgent();
        event.setUserIp(ip==null||ip.isBlank()?"unknown":ip);event.setUserAgent(agent==null||agent.isBlank()?"headquarters-document":agent);
        audit.createOperateLog(event);
        // Audit may cross an I/O boundary; it must not release bytes after a role, tenant or reference changes.
        if (!scope.equals(documents.requireReadable(request))) throw new ServiceException(403,"资料读取权限已变更");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache")
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=\"external-document."+extension+"\"")
                .contentType(MediaType.parseMediaType(file.mimeType())).body(file.bytes());
    }
}
