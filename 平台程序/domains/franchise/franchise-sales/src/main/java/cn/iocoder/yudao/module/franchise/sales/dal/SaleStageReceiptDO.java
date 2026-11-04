package cn.iocoder.yudao.module.franchise.sales.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * M06-T04：期↔分笔关联——资金唯一事实在 fr_fin_receipt，本表只做「某期收了哪些笔」的关联与
 * 累计视图（一笔 receipt 至多归一期，uk_receipt_id）。存量整期核销经迁移落为首笔关联行。
 */
@TableName("fr_sale_stage_receipt")
@TenantIgnore
@Data
@Accessors(chain = true)
public class SaleStageReceiptDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long contractId;
    private Long stageId;
    private Long receiptId;
    private Long amountCents;
    private OffsetDateTime createTime;
}
