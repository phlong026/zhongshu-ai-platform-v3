package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 成果与移交：每次提交一版，由被指定的工程负责人接收或退回 */
@TableName("fr_commission_handover")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CommissionHandoverDO {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long commissionId;
    private Integer version;
    private Integer dispatchSeq;
    /** 四份文件，各为 JSON {name,url} */
    private String resultFile;
    private String drawingFile;
    private String reviewFile;
    private String confirmationFile;
    private String drawingVersion;
    private LocalDate confirmationDate;
    /** 交底说明 */
    private String note;
    /** M06-T03：提交时冻结的当时有效下发方案版本（后续变更留版本不覆盖） */
    private String frozenSchemeVersion;
    private Long managerUserId;
    private String status;
    private String decisionNote;
    private Long decidedByUserId;
    private OffsetDateTime decidedAt;
    private Long submittedByUserId;
    private OffsetDateTime createTime;

}
