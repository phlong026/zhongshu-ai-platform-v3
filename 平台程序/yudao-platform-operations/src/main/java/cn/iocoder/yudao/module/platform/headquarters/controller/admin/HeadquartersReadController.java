package cn.iocoder.yudao.module.platform.headquarters.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersExportService;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadModel;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="总部 - 全品牌只读工作台")
@RestController
@RequestMapping("/platform/headquarters")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class HeadquartersReadController {
    private final HeadquartersReadService reads;
    private final HeadquartersExportService exports;
    public HeadquartersReadController(HeadquartersReadService reads,HeadquartersExportService exports) {this.reads=reads;this.exports=exports;}

    @GetMapping("/context")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<HeadquartersReadModel.Context> context() {return success(reads.context());}

    @GetMapping("/summary")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Map<String,Object>> summary(@RequestParam(required=false)Long brandId,@RequestParam(required=false)Long companyId) {
        return success(reads.summary(new HeadquartersReadModel.Filter(brandId,companyId,"","",null,null,1,20)));
    }

    @GetMapping({"/customers","/projects","/finance/{kind}"})
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<HeadquartersReadModel.Page> page(jakarta.servlet.http.HttpServletRequest request,
            @PathVariable(required=false)String kind,@RequestParam(required=false)Long brandId,@RequestParam(required=false)Long companyId,
            @RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String status,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo,
            @RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize) {
        String dataset=kind==null?(request.getRequestURI().endsWith("/customers")?"customers":"projects"):financeKind(kind);
        return success(reads.page(dataset,new HeadquartersReadModel.Filter(brandId,companyId,keyword,status,dateFrom,dateTo,pageNo,pageSize)));
    }

    @GetMapping("/customers/{id}")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Map<String,Object>> customer(@PathVariable long id) {return success(reads.detail("customers",id));}

    @GetMapping("/projects/{id}")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Map<String,Object>> project(@PathVariable long id) {return success(reads.detail("projects",id));}

    @GetMapping({"/projects/{id}/documents","/projects/{id}/site-records"})
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<HeadquartersReadModel.Page> projectItems(jakarta.servlet.http.HttpServletRequest request,
            @PathVariable long id,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize) {
        String kind=request.getRequestURI().endsWith("/documents")?"documents":"site-records";
        return success(reads.projectItems(id,kind,pageNo,pageSize));
    }

    @GetMapping("/finance/{kind}/{id}")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Map<String,Object>> finance(@PathVariable String kind,@PathVariable long id) {return success(reads.detail(financeKind(kind),id));}

    @GetMapping("/customers/{id}/workflow")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<CustomerWorkflowModel.View> workflow(@PathVariable long id) {return success(reads.workflow(id));}

    @GetMapping("/customers/{id}/workflow-records")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<PageResult<CustomerWorkflowModel.RecordView>> records(@PathVariable long id,
            @RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize) {return success(reads.workflowRecords(id,pageNo,pageSize));}

    @GetMapping("/companies/{id}/books")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<List<Map<String,Object>>> books(@PathVariable long id,@RequestParam(defaultValue="")String keyword) {return success(reads.books(id,keyword));}

    @GetMapping("/companies/{id}/ledger")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Map<String,Object>> ledger(@PathVariable long id,@RequestParam(defaultValue="company")String scope) {return success(reads.ledger(id,scope));}

    /** The sole action endpoint records an export audit; no business command endpoint is exposed. */
    @PostMapping("/exports")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<byte[]> export(@RequestParam String dataset,@RequestParam(required=false)Long brandId,
            @RequestParam(required=false)Long companyId,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String status,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo) {
        byte[] bytes=exports.export(dataset,new HeadquartersReadModel.Filter(brandId,companyId,keyword,status,dateFrom,dateTo,1,20));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=\"headquarters-"+dataset+".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(bytes);
    }

    private static String financeKind(String kind) {
        if(!Set.of("receipts","payables","payments","refunds").contains(kind))throw new ServiceException(400,"财务类别无效");
        return kind;
    }
}
