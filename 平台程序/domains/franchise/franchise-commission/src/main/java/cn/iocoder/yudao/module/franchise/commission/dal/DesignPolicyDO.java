package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 标准设计费用：只追加，每次保存一个新版本；门店取 version 最大且 active 的版本匹配委托费用 */
@TableName("fr_design_policy")
@TenantIgnore
@Data
@Accessors(chain = true)
public class DesignPolicyDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Integer version;
    private Long totalCents;
    private Long firstCents;
    private String scope;
    private String recipient;
    private Boolean active;
    private Long approvedByUserId;
    private Long approvedByPostId;
    private OffsetDateTime createTime;

}
