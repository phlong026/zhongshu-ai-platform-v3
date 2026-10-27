package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 门店客户 */
@TableName("fr_customer")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CustomerDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long teamId;
    /** 负责人：登记客资的业务员 */
    private Long ownerUserId;
    private String name;
    private String phone;
    private String need;
    private String source;
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
