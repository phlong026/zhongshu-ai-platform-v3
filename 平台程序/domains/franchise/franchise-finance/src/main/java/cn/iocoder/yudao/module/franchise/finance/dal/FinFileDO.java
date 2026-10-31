package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 财务凭证文件 */
@TableName("fr_fin_file")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinFileDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String fileName;
    private String storageKey;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private Long uploadedByUserId;
    private Long tenantId;
    private OffsetDateTime createTime;
}