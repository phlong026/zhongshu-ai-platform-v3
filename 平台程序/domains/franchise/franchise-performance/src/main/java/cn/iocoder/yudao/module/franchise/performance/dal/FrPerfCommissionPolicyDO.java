package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 回款提成规则（D-6：已有 release 后整行锁定 004；无 release 修改=version+1；两阶段对半下取整两列制） */
@TableName("fr_perf_commission_policy")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfCommissionPolicyDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long totalCents;
    private Long assigneeUserId;
    private String assigneeName;
    private Long startCents;
    /** M10-T07：基础完成后期提成约定（三期化；0=未约定） */
    private Long baseDoneCents;
    private Long toppingCents;
    private Integer version;
    private String history;
    private String evidenceRef;
    private String note;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
