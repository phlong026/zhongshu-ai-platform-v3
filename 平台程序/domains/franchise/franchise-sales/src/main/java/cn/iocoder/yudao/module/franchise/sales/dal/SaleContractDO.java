package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** SaleContractDO（B2 板块 B） */
@TableName("fr_sale_contract")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SaleContractDO {

    public static final String STATUS_WAIT_SIGN = "WAIT_SIGN";
    public static final String STATUS_SIGNED = "SIGNED";
    public static final String STATUS_VOIDED = "VOIDED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Long totalCents;
    private Long firstCents;
    private java.time.LocalDate signedDate;
    private Long assetId;
    private String status;
    private Long revision;
    private Integer paymentPlanVersion;
    private String paymentPlanMode;
    private Long requirementId;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
