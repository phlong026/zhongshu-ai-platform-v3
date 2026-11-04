package cn.iocoder.yudao.module.platform.org.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * M01-T04：离职交接待分配记录——紧急撤权在途对象的可查询、可补交接落地行（Codex R1 P1-3）。
 * status：OPEN 待分配（紧急撤权后未交接）/ HANDED_OVER 已核销（正常交接或补交接完成）。
 * inFlight 登记时扫描快照，-1 = 扫描失败未知（补交接按全量转移，不依赖该数字）。
 */
@TableName("pf_handover_pending")
@TenantIgnore
@Data
@Accessors(chain = true)
public class PlatformHandoverPendingDO {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_HANDED_OVER = "HANDED_OVER";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orgId;
    private Long fromUserId;
    private Long toUserId;
    private String domain;
    private Long inFlight;
    private String status;
    private Long operatorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private OffsetDateTime handedOverAt;
}
