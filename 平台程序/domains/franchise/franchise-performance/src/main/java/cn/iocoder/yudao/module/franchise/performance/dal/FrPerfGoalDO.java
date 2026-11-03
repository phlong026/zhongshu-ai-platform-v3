package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 月度目标（三级：公司级五指标列+部门级/人员部门级 JSONB；归档期拒改——锁信号=task_archive 存在） */
@TableName("fr_perf_goal")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfGoalDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    /** 'YYYY-MM' */
    private String period;
    private Long salesCents;
    private Long collectionsCents;
    private Integer effectiveCount;
    private Integer customersCount;
    private Integer nodesCount;
    private String departmentGoals;
    private String staffDepartmentGoals;
    /** 旧人员标量目标兼容读（蓝本 goals.staff） */
    private String legacyStaff;
    private Integer version;
    private String history;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
