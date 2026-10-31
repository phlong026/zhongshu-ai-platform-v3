package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 到账确认：bank_flow_id 与 receipt_id 双 UNIQUE 唯一入账 */
@TableName("fr_fin_bank_confirm")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinBankConfirmDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long bankFlowId;
    private Long receiptId;
    private Long companyId;
    private Long amountCents;
    private Long matchedByUserId;
    private OffsetDateTime matchedAt;
    private Long tenantId;
    private OffsetDateTime createTime;
}