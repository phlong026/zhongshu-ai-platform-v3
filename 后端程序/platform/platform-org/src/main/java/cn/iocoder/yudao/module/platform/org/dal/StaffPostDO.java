package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 员工任职：一人一岗一条，兼岗为多条，撤销保留历史 */
@TableName("pf_staff_post")
@TenantIgnore
@Data
@Accessors(chain = true)
public class StaffPostDO {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long orgId;
    private String roleCode;
    private Long teamId;
    private String status;
    private Long createdBy;
    private OffsetDateTime createTime;
    private Long revokedBy;
    private OffsetDateTime revokedAt;

}
