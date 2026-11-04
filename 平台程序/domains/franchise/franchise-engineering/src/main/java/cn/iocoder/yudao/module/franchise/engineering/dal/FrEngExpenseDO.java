package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * M09-T04：直接项目费用——非材料的直接成本（LABOR 对外劳务/TRAVEL 差旅/OTHER 其它）。
 * 确认（REGISTERED）即计成本；付款经 EngineeringPayablePort 挂 finance 应付（付款不重计成本）；
 * 取消（CANCELLED）不计成本；退回语义=减已付现金、不撤费用确认（退多付款不减正确费用）。
 */
@TableName("fr_eng_expense")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngExpenseDO {

    public static final String PURPOSE_LABOR = "LABOR";
    public static final String PURPOSE_TRAVEL = "TRAVEL";
    public static final String PURPOSE_OTHER = "OTHER";
    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private String name;
    private String purpose;
    private Long amountCents;
    private Long payableId;
    private String status;
    private String note;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
