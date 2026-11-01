package cn.iocoder.yudao.module.platform.workflow.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 平台员工消息（pf_message，基-2 消息中心）：业务域经 {@code MessageSender} 推送、按人收、已读持久化 */
@TableName("pf_message")
@TenantIgnore
@Data
@Accessors(chain = true)
public class MessageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 收件人：按人收（人换岗位不丢消息），列表与已读均只按它过滤 */
    private Long userId;
    /** 归属组织：过滤/审计维度，接口不按它过滤 */
    private Long orgId;
    /** 发送方域（受控登记，如 fr-fin / e2e） */
    private String senderDomain;
    /** 消息类型白名单：BUSINESS/LEARNING/SYSTEM */
    private String type;
    /** 前端跳转路由键（白名单见 11 文档 §五.2） */
    private String route;
    /** 业务对象 id（可空；后端域雪花 id） */
    private Long objectId;
    private String title;
    private String description;
    /** 业务幂等键：同 (senderDomain, dedupeKey) 重发返回既有行 */
    private String dedupeKey;
    private Boolean read;
    private OffsetDateTime readTime;
    private OffsetDateTime createTime;

}
