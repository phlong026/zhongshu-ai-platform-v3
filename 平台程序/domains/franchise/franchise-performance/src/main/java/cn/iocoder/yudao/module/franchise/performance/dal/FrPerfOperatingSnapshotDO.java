package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 经营快照（只允许过去月 008；source_signature 内嵌 report；变化 version+1 supersedes） */
@TableName("fr_perf_operating_snapshot")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfOperatingSnapshotDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String period;
    private Integer version;
    private Long supersedes;
    private String report;
    private LocalDate asOf;
    private String note;
    private String status;
    private Long authorUserId;
    private OffsetDateTime createTime;
}
