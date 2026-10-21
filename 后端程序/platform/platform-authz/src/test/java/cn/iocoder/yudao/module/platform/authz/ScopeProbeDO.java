package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/** 测试用业务表：company_id 为归属组织，design_org_id 为额外可见组织 */
@TableName("authz_scope_probe")
@TenantIgnore
@Data
@Accessors(chain = true)
public class ScopeProbeDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long companyId;
    private Long designOrgId;
    private String batch;

}
