package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 付款：登记即已核实；作废释放额度并留痕 */
@TableName("fr_fin_payment")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinPaymentDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long payableId;
    private Long projectId;
    private Long companyId;
    private Long amountCents;
    /** M10-T05/R1：待关联现金金额（超出应付剩余额度的已核实部分；reconcile 后转正） */
    private Long pendingLinkCents;
    /** LINKED=已入有效账；PENDING_LINK=已核待关联现金（异常留痕） */
    private String linkStatus;
    private String status;
    private LocalDate payDate;
    private Long operatorUserId;
    private String transactionNo;
    private Long voucherFileId;
    private Long voidByUserId;
    private String voidReason;
    private OffsetDateTime voidedAt;
    private Long tenantId;
    private OffsetDateTime createTime;
}