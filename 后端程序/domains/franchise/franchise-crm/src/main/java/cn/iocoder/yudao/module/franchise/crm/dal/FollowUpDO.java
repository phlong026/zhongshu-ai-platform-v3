package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 客户跟进记录 */
@TableName("fr_follow_up")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FollowUpDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long customerId;
    private Long authorUserId;
    private Long authorPostId;
    private String note;
    private LocalDate nextVisitDate;
    private OffsetDateTime createTime;

}
