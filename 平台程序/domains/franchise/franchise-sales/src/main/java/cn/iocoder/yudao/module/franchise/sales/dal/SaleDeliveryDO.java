package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** SaleDeliveryDO（B2 板块 B） */
@TableName("fr_sale_delivery")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SaleDeliveryDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Long contractId;
    private Long requirementId;
    private OffsetDateTime designDeliveredAt;
    private OffsetDateTime designConfirmedAt;
    private OffsetDateTime constructionDeliveredAt;
    private OffsetDateTime constructionAcceptedAt;
    private String payload;
    private Long revision;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
