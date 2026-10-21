package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 组织类型，由业务域注册 */
@TableName("pf_org_type")
@TenantIgnore
@Data
public class OrgTypeDO {

    @TableId(type = IdType.INPUT)
    private String code;
    private String domain;
    private String name;

}
