package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 到账匹配失败尝试留痕（独立事务，不随确认回滚） */
@TableName("fr_fin_match_attempt")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinMatchAttemptDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long bankFlowId;
    private Long triedReceiptId;
    private String reason;
    private Long tenantId;
    private OffsetDateTime createTime;
}