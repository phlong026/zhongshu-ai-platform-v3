package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 交易身份：流水号永久防重（软删/作废不释放）；更正链经 owner 原子切换（条件 UPDATE 行数=1） */
@TableName("fr_fin_txn_no")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinTxnNoDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String txnNo;
    private String ownerType;   // receipt / payment / refund
    private Long ownerId;
    private Boolean closed;     // 更正换号时旧身份行关闭
    private Long tenantId;
    private OffsetDateTime createTime;
}