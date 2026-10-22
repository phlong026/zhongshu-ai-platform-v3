package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 参考方案与预算版本，只追加；BUDGET 的尺寸是设计委托的依据 */
@TableName("fr_customer_scheme")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CustomerSchemeDO {

    public static final String KIND_BUDGET = "BUDGET";
    public static final String KIND_PLAN = "PLAN";
    public static final String KIND_FACADE = "FACADE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private String kind;
    private Integer version;
    private String title;
    private String payload;
    private BigDecimal width;
    private BigDecimal depth;
    private Integer floors;
    private BigDecimal area;
    private Long totalCents;
    private Long authorUserId;
    private OffsetDateTime createTime;

}
