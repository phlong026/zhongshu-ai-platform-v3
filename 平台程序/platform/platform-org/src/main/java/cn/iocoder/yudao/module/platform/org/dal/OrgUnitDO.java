package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** system_dept 的扩展：ORG 为组织，TEAM 为组织内团队 */
@TableName("pf_org_unit")
@TenantIgnore
@Data
@Accessors(chain = true)
public class OrgUnitDO {

    public static final String KIND_ORG = "ORG";
    public static final String KIND_TEAM = "TEAM";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(type = IdType.INPUT)
    private Long deptId;
    private String kind;
    /** 所属组织的 deptId；ORG 行等于自身 */
    private Long orgId;
    /** 组织类型，仅 ORG 行有值 */
    private String orgType;
    private String line;
    private String status;
    private OffsetDateTime updateTime;

    public boolean isOrg() {
        return KIND_ORG.equals(kind);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

}
