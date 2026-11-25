package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 工程档案（一业务委托至多一工程；status=派生态固化列：PENDING_START→IN_PROGRESS→READY_DELIVER→DELIVERED→ARCHIVED）。
 * 金额一律分（F11）；合同快照随开工准备固化。
 */
@TableName("fr_eng_project")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngProjectDO {

    public static final String SOURCE_HANDOVER = "HANDOVER";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_INCREMENT = "INCREMENT";

    public static final String STATUS_PENDING_START = "PENDING_START";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_READY_DELIVER = "READY_DELIVER";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    /** 移交来源=crm 客户；手工新建可空（仅存称呼） */
    private Long customerId;
    private String customerName;
    private String phone;
    private Long sourceCommissionId;
    /** M06-T03：关联统一建房合同（客户无活跃合同时 NULL 兼容；合同变更不回写） */
    private Long saleContractId;
    private String sourceType;
    private Long parentProjectId;
    /** 负责人（移交带入/换负责人动作维护；记录流办理=本人，D-14 owner） */
    private Long managerUserId;
    private Long assignedEngineerUserId;
    private String city;
    private BigDecimal area;
    private Integer floors;
    private String name;
    private Long contractTotalCents;
    private Long firstCents;
    private Long contractAssetId;
    private Integer contractVersion;
    private Integer paymentPlanVersion;
    private LocalDate contractDate;
    private String drawingVersion;
    private LocalDate actualStartAt;
    private String status;
    private String preparedNote;
    private OffsetDateTime preparedAt;
    /** 施工人员名单 JSONB 数组（开工准备带入） */
    private String preparedWorkers;
    private Long revision;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
