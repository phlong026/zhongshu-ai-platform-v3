package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 月末对账（按月覆盖写；boss/finance；口径=fr_fin 当月已登记回款/已核实付退） */
@TableName("fr_perf_reconciliation")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfReconciliationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String period;
    private Long incomeCents;
    private Long paidCents;
    private Long netCents;
    private Integer receiptCount;
    private Integer paymentCount;
    private Long byUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
