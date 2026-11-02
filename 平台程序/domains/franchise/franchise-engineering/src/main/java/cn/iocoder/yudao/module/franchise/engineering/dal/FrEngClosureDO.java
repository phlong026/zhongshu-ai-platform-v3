package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 尾段三段（1:1；deliver→settle→archive 前置链；字段组同置由 DB CHECK 保证） */
@TableName("fr_eng_closure")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngClosureDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private LocalDate deliveredAt;
    private String recipient;
    private Long ownerAssetId;
    private Long fileAssetId;
    private String deliverNote;
    private Long deliveredBy;
    private LocalDate settledAt;
    private String settleNote;
    private Long settleAssetId;
    private Long settledBy;
    private OffsetDateTime archivedAt;
    private Long archivedBy;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
