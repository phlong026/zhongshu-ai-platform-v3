package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 跟进记录，只追加 */
@TableName("fr_customer_follow")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CustomerFollowDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private String note;
    private String contactKind;
    private LocalDate occurredOn;
    private LocalDate nextVisitDate;
    private Long authorUserId;
    private Long authorPostId;
    private OffsetDateTime createTime;

}
