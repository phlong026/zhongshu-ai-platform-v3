package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 周期复核（人员×月；adjustments 并入 PerfMetrics 聚合——source_key 命中改归属/剔除） */
@TableName("fr_perf_cycle")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfCycleDO {

    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_RETURNED = "RETURNED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String period;
    private Long userId;
    private String status;
    private String sourceSignature;
    /** 提交快照 {sources, actuals, target, note, at} */
    private String submission;
    private String adjustments;
    /** 复核结论 {result 通过|退回, note, reviewerUserId, at} */
    private String review;
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
