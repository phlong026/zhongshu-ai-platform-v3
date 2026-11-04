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

    /** 存量两期值（迁移 825 映射前），仅供兼容引用 */
    public static final String STAGE_START = "START";
    public static final String STAGE_TOPPING = "TOPPING";
    /** M10-T07 三期（与工程域 818/销售域 M06-T04 枚举对齐） */
    public static final String STAGE_SIGN_AFTER = "SIGN_AFTER";
    public static final String STAGE_BASE_DONE = "BASE_DONE";
    public static final String STAGE_CAP_DONE = "CAP_DONE";

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
