package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** SaleRequirementDO（B2 板块 B） */
@TableName("fr_sale_requirement")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SaleRequirementDO {

    public static final String STATUS = null; // 只追加，无状态

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Integer version;
    private java.math.BigDecimal width;
    private java.math.BigDecimal depth;
    private Integer floors;
    private String style;
    private String prompt;
    private String note;
    private Long authorUserId;
    private OffsetDateTime createTime;

}
