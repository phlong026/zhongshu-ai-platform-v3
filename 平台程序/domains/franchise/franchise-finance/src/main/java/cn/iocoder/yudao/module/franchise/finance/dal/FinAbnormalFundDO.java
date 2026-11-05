package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 异常资金登记（M08-T08/R05）：原单缺失/超上限/日期凭据待核的真实资金事实——独立留痕，不进有效账 */
@TableName("fr_fin_abnormal_fund")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinAbnormalFundDO {

    public static final String STATUS_PENDING_LINK = "已核待关联";
    public static final String STATUS_LINKED = "已关联";
    public static final String STATUS_VOIDED = "已作废";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    /** 客户收款/客户退款/支出退回 */
    private String fundDirection;
    private Long amountCents;
    /** 付款方/收款方（已知则填） */
    private String counterparty;
    private Long relatedProjectId;
    private Long relatedCommissionId;
    /** 期次或费用类别 */
    private String stageOrCategory;
    /** NULL=日期待核 → 读侧单列「待归月」（不回退 create_time） */
    private LocalDate businessDate;
    private String method;
    private String transactionNo;
    private String reason;
    private Long voucherFileId;
    private String status;
    /** 转正关联目标类型 receipt/refund/payment（仅标注，不改有效账数字） */
    private String linkedType;
    private Long linkedId;
    private Long operatorUserId;
    private Long voidByUserId;
    private String voidReason;
    private OffsetDateTime voidedAt;
    private Long tenantId;
    private OffsetDateTime createTime;
}
