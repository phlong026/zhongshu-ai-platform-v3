package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * SalePaymentStageDO（B2 板块 B；M06-T04 三期总闸）：
 * 三期=签订后 SIGN_AFTER / 基础完成后 BASE_DONE / 封顶后 CAP_DONE（金额由合同约定，允许 0=未约定）；
 * 各期可分笔收款（关联见 SaleStageReceiptDO）。first/tail 为存量映射保留值。状态：PENDING 未收 →
 * OPEN 首笔后未收满 → SETTLED 收满（PAID 为存量整期核销值，迁移后即 SETTLED）。
 */
@TableName("fr_sale_payment_stage")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SalePaymentStageDO {

    /** 存量两期值（迁移映射前），仅供兼容引用 */
    public static final String STAGE_FIRST = "first";
    public static final String STAGE_TAIL = "tail";
    public static final String STAGE_SIGN_AFTER = "SIGN_AFTER";
    public static final String STAGE_BASE_DONE = "BASE_DONE";
    public static final String STAGE_CAP_DONE = "CAP_DONE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_SETTLED = "SETTLED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Long contractId;
    private String stage;
    private Long amountCents;
    private java.time.LocalDate dueDate;
    private String status;
    private Long paidReceiptId;
    private OffsetDateTime paidAt;
    private String payFingerprint;
    private Boolean deleted;
    private OffsetDateTime createTime;

}
