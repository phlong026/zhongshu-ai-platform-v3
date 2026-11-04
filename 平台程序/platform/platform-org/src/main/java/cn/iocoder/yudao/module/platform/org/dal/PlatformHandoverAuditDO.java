package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * M01-T04：离职交接持久化审计（Codex R2 P2-6）——每次交接一行：模式、双方、操作者、
 * 被撤任职、逐域清单 JSON。交接清单落库可追溯，不依赖运行日志。
 */
@TableName("pf_handover_audit")
@TenantIgnore
@Data
@Accessors(chain = true)
public class PlatformHandoverAuditDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orgId;
    private String mode;
    private Long fromUserId;
    private Long toUserId;
    private Long operatorUserId;
    private String revokedPostIds;
    private String itemsJson;
    private OffsetDateTime createTime;
}
