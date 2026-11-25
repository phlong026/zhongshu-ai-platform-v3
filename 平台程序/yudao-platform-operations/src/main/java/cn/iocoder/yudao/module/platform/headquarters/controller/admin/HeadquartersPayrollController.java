package cn.iocoder.yudao.module.platform.headquarters.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.PayrollReadPort;
import cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** All-brand payroll reads reuse the company's versioned records without impersonating its finance staff. */
@RestController
@RequestMapping("/platform/headquarters/companies/{companyId}/payroll")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class HeadquartersPayrollController {
    private final HeadquartersReadGuard guard;
    private final PayrollReadPort payroll;

    public HeadquartersPayrollController(HeadquartersReadGuard guard, PayrollReadPort payroll) {
        this.guard=guard; this.payroll=payroll;
    }

    @GetMapping("/rules")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<CommonResult<Map<String,Object>>> rules(@PathVariable long companyId,
            @RequestParam(required=false) String family, @RequestParam(defaultValue="1") int pageNo,
            @RequestParam(defaultValue="20") int pageSize) {
        return read(companyId, actor -> payroll.rulesAuthorized(actor.tenantId(),companyId,family,pageNo,pageSize));
    }

    @GetMapping("/records")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<CommonResult<Map<String,Object>>> records(@PathVariable long companyId,
            @RequestParam(required=false) String month, @RequestParam(required=false) String family,
            @RequestParam(defaultValue="1") int pageNo, @RequestParam(defaultValue="20") int pageSize) {
        return read(companyId, actor -> payroll.recordsAuthorized(actor.tenantId(),companyId,month,family,pageNo,pageSize));
    }

    @GetMapping("/records/{id}")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<CommonResult<Map<String,Object>>> record(@PathVariable long companyId,@PathVariable long id) {
        return read(companyId, actor -> payroll.recordAuthorized(actor.tenantId(),companyId,id));
    }

    @GetMapping("/records/{id}/history")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<CommonResult<Map<String,Object>>> history(@PathVariable long companyId,@PathVariable long id,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return read(companyId, actor -> payroll.historyAuthorized(actor.tenantId(),companyId,id,pageNo,pageSize));
    }

    private ResponseEntity<CommonResult<Map<String,Object>>> read(long company,
            Function<HeadquartersReadGuard.Actor,Map<String,Object>> operation) {
        var actor=guard.require(); guard.company(actor,company);
        var result=new LinkedHashMap<>(operation.apply(actor)); result.put("readOnly",true);
        guard.requireCompanies(actor,Set.of(company));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(success(result));
    }
}
