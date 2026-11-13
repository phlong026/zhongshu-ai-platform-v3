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
    private String failedReason;
    /** 公司积分池预扣键（content:{taskId}:{subject}）；终态结算失败时可幂等重试（24 号评审 P1-4） */
    private String poolBizKey;
    private OffsetDateTime createTime;
    private OffsetDateTime completeTime;

}
