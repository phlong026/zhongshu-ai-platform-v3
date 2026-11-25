package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseDirectoryService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseOnboardService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseRegionCatalog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/platform/org")
@PreAuthorize("@ss.hasPermission('platform:org:onboard')")
public class FranchiseDirectoryController {
    private final FranchiseDirectoryService directory;
    private final FranchiseRegionCatalog regions;
    public FranchiseDirectoryController(FranchiseDirectoryService directory,FranchiseRegionCatalog regions) {
        this.directory=directory;this.regions=regions;
    }
    @GetMapping("/franchise-directory")
    public CommonResult<PageResult<FranchiseDirectoryService.Item>> page(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String brandId,@RequestParam(required=false) String matchStatus,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return CommonResult.success(directory.page(keyword,brandId,matchStatus,pageNo,pageSize));
    }
    @GetMapping("/franchise-directory/candidates")
    public CommonResult<List<FranchiseDirectoryService.Item>> candidates(@RequestParam(defaultValue="") String keyword) {
        return CommonResult.success(directory.candidates(keyword));
    }
    @GetMapping("/franchise-directory/regions")
    public CommonResult<List<FranchiseRegionCatalog.Region>> regions(@RequestParam(required=false) String parentCode) {
        return CommonResult.success(regions.children(parentCode));
    }
    @PostMapping("/franchise-directory/import/precheck")
    @ApiAccessLog(enable=false)
    public CommonResult<FranchiseDirectoryService.ImportResult> precheck(@RequestParam MultipartFile file,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","private, no-store");
        return CommonResult.success(directory.importWorkbook(bytes(file),true,actor()));
    }
    @PostMapping("/franchise-directory/import")
    @ApiAccessLog(enable=false)
    public CommonResult<FranchiseDirectoryService.ImportResult> submit(@RequestParam MultipartFile file,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","private, no-store");
        return CommonResult.success(directory.importWorkbook(bytes(file),false,actor()));
    }
    @PostMapping("/franchise-directory")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<FranchiseDirectoryService.Item> create(@RequestBody FranchiseDirectoryService.RecordRequest input) {
        return CommonResult.success(directory.create(input,actor()));
    }
    @PutMapping("/franchise-directory/{id}")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<FranchiseDirectoryService.Item> update(@PathVariable String id,@RequestBody FranchiseDirectoryService.RecordRequest input) {
        return CommonResult.success(directory.update(id,input,actor()));
    }
    @PutMapping("/franchise-directory/{id}/map-display")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<FranchiseDirectoryService.Item> updateMapDisplay(@PathVariable String id,@RequestBody FranchiseDirectoryService.MapDisplayRequest input) {
        actor();
        return CommonResult.success(directory.updateMapDisplay(id,input));
    }
    @PostMapping("/franchise-onboard/from-directory")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<FranchiseOnboardService.Result> onboard(@RequestBody FranchiseDirectoryService.FromDirectoryRequest input,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        return CommonResult.success(directory.onboard(input,actor()));
    }
    private static byte[] bytes(MultipartFile file) throws IOException {
        if(file==null||file.isEmpty()||file.getSize()>5L*1024*1024)throw new ServiceException(400,"请选择不超过5MB的Excel文件");
        return file.getBytes();
    }
    private static long actor() {
        Long actor=SecurityFrameworkUtils.getLoginUserId();if(actor==null)throw new ServiceException(401,"请先登录");return actor;
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Object> invalid(IllegalArgumentException error) { return CommonResult.error(400,error.getMessage()); }
}
