package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 当前节点行表（corrections/reopen_history 留痕 JSONB；更正日期链含封顶完成≤封顶款收款日） */
@TableName("fr_eng_plan_node")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngPlanNodeDO {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_DONE = "DONE";
    public static final String MILESTONE_TOPPING = "TOPPING";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Integer nodeIndex;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private String milestone;
    private Long responsibleUserId;
    private LocalDate completedAt;
    private String corrections;
    private String reopenHistory;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
