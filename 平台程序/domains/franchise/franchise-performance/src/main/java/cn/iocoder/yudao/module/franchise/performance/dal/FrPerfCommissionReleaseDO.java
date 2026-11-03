package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 提成应付留痕（project+stage 唯一幂等；payable_id 锚 finance；reconciliations 人工核对链） */
@TableName("fr_perf_commission_release")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrPerfCommissionReleaseDO {

    public static final String STAGE_START = "START";
    public static final String STAGE_TOPPING = "TOPPING";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long policyId;
    private Long projectId;
    private String stage;
    private Long payableId;
    private Integer policyVersion;
    private String receiptSignature;
    private String receiptIds;
    private String reconciliations;
    private Long releaseUserId;
    private OffsetDateTime releaseAt;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
