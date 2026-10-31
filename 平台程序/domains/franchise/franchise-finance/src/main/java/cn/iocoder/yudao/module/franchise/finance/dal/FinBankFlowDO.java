package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 银行流水（dev/e2e 注入） */
@TableName("fr_fin_bank_flow")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinBankFlowDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String bankRef;
    private Long amountCents;
    private LocalDate bankDate;
    private String status;
    private String source;
    private Long tenantId;
    private OffsetDateTime createTime;
}