package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** SalePaymentStageDO（B2 板块 B） */
@TableName("fr_sale_payment_stage")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SalePaymentStageDO {

    public static final String STAGE_FIRST = "first";
    public static final String STAGE_TAIL = "tail";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Long contractId;
    private String stage;
    private Long amountCents;
    private String status;
    private Long paidReceiptId;
    private OffsetDateTime paidAt;
    private String payFingerprint;
    private Boolean deleted;
    private OffsetDateTime createTime;

}
