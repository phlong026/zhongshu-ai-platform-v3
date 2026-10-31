package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.finance.FinBankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 银行流水注入（B1 到账核对冒烟/联调用）：仅 local profile 注册（同 SmsDevController 口径），
 * prod 由对账导入替代。挂在 /admin-api/platform/dev-setup/** 之下沿用 local 放行列表；
 * 操作者需携带财务岗 X-Post-Id 以确定 company 归属。
 */
@Tag(name = "开发 - 银行流水注入")
@RestController
@RequestMapping("/admin-api/platform/dev-setup/bank-flow")
@Profile("local")
public class BankFlowDevController {

    @Resource
    private FinBankService bankService;
    @PostMapping
    @Operation(summary = "注入一条模拟银行流水（仅 local；金额分）")
    public CommonResult<Long> inject(@RequestParam("companyId") long companyId,
                                     @RequestParam("bankRef") String bankRef,
                                     @RequestParam("amountCents") long amountCents,
                                     @RequestParam(value = "bankDate", required = false) LocalDate bankDate) {
        return success(bankService.injectFlow(null, companyId, bankRef, amountCents,
                bankDate == null ? LocalDate.now() : bankDate, "dev"));
    }

}
