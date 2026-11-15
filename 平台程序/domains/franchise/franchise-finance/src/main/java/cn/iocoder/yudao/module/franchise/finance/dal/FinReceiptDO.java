package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 实收：登记即生效（唯一有效实收状态）；修订走 revision_version 乐观锁 */
@TableName("fr_fin_receipt")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinReceiptDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long customerId;
    private String sourceType;
    private String sourceRef;
    private Long originPaymentId;
    private String name;
    private Long amountCents;
    private String status;
    private LocalDate receivedDate;
    private OffsetDateTime registeredAt;
    private Long registrarUserId;
    private String method;
    private String transactionNo;
    private Long voucherFileId;
    private Integer revisionVersion;
    private String voidReason;
    private Long voidByUserId;
    private Long tenantId;
    private OffsetDateTime createTime;
}
