package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 退款：线下事实登记制，登记即已核实；更正链经 replaced_by_id CAS + replaces_id */
@TableName("fr_fin_refund")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinRefundDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long receiptId;
    private String refundDirection; // M08-T08: client/expense
    private Long paymentId; // M08-T08: expense 退回关联原付款单
    private Long companyId;
    private Long amountCents;
    private String status;
    private String reason;
    private Long operatorUserId;
    private Long replacedById;
    private Long replacesId;
    private LocalDate date;
    private String transactionNo;
    private Long voucherFileId;
    private Long voidByUserId;
    private String voidReason;
    private OffsetDateTime voidedAt;
    private Long tenantId;
    private OffsetDateTime createTime;
}