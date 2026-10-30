package cn.iocoder.yudao.module.platform.command.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * 命令事件：业务命令成功执行后在同一事务内追加，只追加不修改。
 */
@TableName("pf_command_event")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CommandEventDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 业务域编码，如 franchise */
    private String domain;
    /** 聚合类型，如 commission */
    private String aggregateType;
    private Long aggregateId;
    /** 命令执行后聚合的版本号 */
    private Long revision;
    /** 命令名，如 commission-submit */
    private String command;
    /** 执行时当前岗位所在组织 */
    private Long orgId;
    private Long actorUserId;
    private Long actorPostId;
    private String actorRoleCode;
    /** 命令输入快照（JSON 文本），不得包含敏感明文 */
    private String payload;
    /** payload 的 SHA-256，由 {@link cn.iocoder.yudao.module.platform.command.CommandLog} 计算 */
    private String payloadHash;
    private String idempotencyKey;
    private OffsetDateTime occurredAt;

}
