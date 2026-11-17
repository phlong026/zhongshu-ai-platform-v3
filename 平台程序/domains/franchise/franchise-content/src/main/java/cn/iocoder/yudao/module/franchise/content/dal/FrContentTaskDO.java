package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 生成任务七态机（16 §四/D-1.5-7）：状态迁移经 ContentTaskStore 单语句 CAS 固化。 */
@TableName("fr_content_task")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long authorUserId;
    private String kind;
    private String inputSnapshot;
    private String resultSnapshot;
    private String inputSha256;
    private String provider;
    private String providerTaskId;
    private String resultUrl;
    private String engine;
    private String model;
    private String status;
    private String resultState;
    private String resultError;
    private String failedReason;
    /** 公司积分池预扣键；任务终态后可据此幂等恢复结算。 */
    private String poolBizKey;
    /** 创建时随真实预扣冻结的计量数量；后续不得按可能变化的公式重算。 */
    private Long poolReservedUnits;
    /** SETTLE=付费产物；RELEASE=本地 rules 免费兜底。 */
    private String poolTerminalIntent;
    /** 已跨过外呼边界；此后无明确回执的异常必须保留 PENDING。 */
    private OffsetDateTime dispatchStartedAt;
    private Integer poolReconcileAttempts;
    private OffsetDateTime poolReconcileAfter;
    private String poolReconcileError;
    private String poolReconcileLeaseToken;
    private OffsetDateTime poolReconcileLeaseUntil;
    private String manualVerifyDecision;
    private Long manualVerifyActualUnits;
    private String manualVerifyReason;
    private String manualVerifyEvidence;
    private Long manualVerifyOperatorUserId;
    private Long manualVerifyOperatorPostId;
    private String manualVerifyIdempotencyKey;
    private OffsetDateTime manualVerifiedAt;
    private OffsetDateTime createTime;
    private OffsetDateTime completeTime;

}
