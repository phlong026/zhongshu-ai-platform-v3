package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 归档快照（archive_no 递增+supersedes 链；sourceSignature 冻结——修订仅当签名变化） */
@TableName("fr_perf_task_archive")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfTaskArchiveDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String period;
    private Integer archiveNo;
    private Long supersedes;
    private String tasks;
    private String sourceSignature;
    private String status;
    private String note;
    private Long authorUserId;
    private OffsetDateTime createTime;
}
