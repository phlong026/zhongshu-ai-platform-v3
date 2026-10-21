package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Arrays;

/** 岗位模板，由业务域注册；roleCode 即 system_role.code */
@TableName("pf_post_template")
@TenantIgnore
@Data
public class PostTemplateDO {

    @TableId(type = IdType.INPUT)
    private String roleCode;
    private String domain;
    /** 允许的组织类型，逗号分隔 */
    private String orgTypes;
    private String line;
    private Boolean lead;
    /** 负责人继承的专员岗位 */
    private String inheritsRoleCode;

    public boolean allows(String orgType) {
        return Arrays.stream(orgTypes.split(",")).map(String::trim).anyMatch(orgType::equals);
    }

}
