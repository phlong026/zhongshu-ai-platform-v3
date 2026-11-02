package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 绩效任务（manual+10 类 auto；auto 进度由 PerfMetrics 实时派生不落本表） */
@TableName("fr_perf_task")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfTaskDO {

    public static final String TYPE_MANUAL = "manual";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String period;
    private String title;
    private Long assigneeUserId;
    private String assigneeName;
    private String assigneeDept;
    private String type;
    private Long targetCents;
    private LocalDate due;
    /** 手动补填 {note, photos[], at, actorUserId}（photos 随 COS 批降级） */
    private String manualDone;
    private String history;
    private Boolean suggested;
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
