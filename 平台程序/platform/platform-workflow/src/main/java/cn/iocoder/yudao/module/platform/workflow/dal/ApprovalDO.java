package cn.iocoder.yudao.module.platform.workflow.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 审批事项 */
@TableName("pf_approval")
@TenantIgnore
@Data
@Accessors(chain = true)
public class ApprovalDO {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String domain;
    /** 审批所属组织：只有该组织的指定岗位能审批 */
    private Long orgId;
    private String subjectType;
    private Long subjectId;
    private Long subjectVersion;
    /** 关键内容摘要；不同即需重新审批 */
    private String signature;
    private Long amountCents;
    private String status;
    /** 有权审批的岗位编码，如 boss */
    private String approverRoleCode;
    private Long submitterUserId;
    private Long submitterPostId;
    private Long approverUserId;
    private Long approverPostId;
    private String note;
    private OffsetDateTime createTime;
    private OffsetDateTime decidedAt;

}
