package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** SaleQuoteDO（B2 板块 B） */
@TableName("fr_sale_quote")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SaleQuoteDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Long schemeId;
    private String schemeKind;
    private Integer schemeVersion;
    private String items;
    private Long totalCents;
    private String priceVersion;
    private String customerName;
    private String customerPhone;
    private String address;
    private java.math.BigDecimal width;
    private java.math.BigDecimal depth;
    private Integer floors;
    private java.math.BigDecimal area;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private String updater;
    private OffsetDateTime updateTime;

}
