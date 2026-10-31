package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 收款修订留痕：UNIQUE(receipt_id, base_revision_version) 保证同基础版本仅一次生效 */
@TableName("fr_fin_receipt_revision")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinReceiptRevisionDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long receiptId;
    private Integer baseRevisionVersion;
    private Long originalAmountCents;
    private Long newAmountCents;
    private String note;
    private Long operatorUserId;
    private Long tenantId;
    private OffsetDateTime createTime;
}