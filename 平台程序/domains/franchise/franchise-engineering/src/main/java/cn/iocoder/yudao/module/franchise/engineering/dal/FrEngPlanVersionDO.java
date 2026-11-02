package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 计划版本快照（D-5：保存计划=版本+节点整体重建，旧版留快照；◆经典链 source/compiled_at 字段保留） */
@TableName("fr_eng_plan_version")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngPlanVersionDO {

    public static final String SOURCE_TEMPLATE = "TEMPLATE";
    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Integer version;
    /** 节点清单 JSONB 数组（快照时刻全量） */
    private String nodes;
    private String source;
    private OffsetDateTime compiledAt;
    private Long authorUserId;
    private OffsetDateTime createTime;

}
