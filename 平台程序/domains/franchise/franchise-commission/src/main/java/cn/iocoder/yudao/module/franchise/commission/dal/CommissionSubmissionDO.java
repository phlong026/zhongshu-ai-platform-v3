package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 每次委托提交（首次、费用退回后重提）：费用与引用的参考方案在此版本冻结 */
@TableName("fr_commission_submission")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CommissionSubmissionDO {

    public static final String AUTH_STANDARD = "STANDARD";
    public static final String AUTH_EXCEPTION = "EXCEPTION";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long designOrgId;
    private Long commissionId;
    private Integer version;
    private String mode;
    private Long rateCents;
    private Integer pricingVersion;
    private Long attachmentAssetId;
    private String text;
    private String scope;
    /** JSON {name,url}，可空 */
    private String attachment;
    private Integer budgetVersion;
    private Integer planVersion;
    private Integer facadeVersion;
    private BigDecimal width;
    private BigDecimal depth;
    private Integer floors;
    private BigDecimal area;
    /** 所引用的预算/平面/立面 payload 副本，供总部查看（客户档案对总部不可见） */
    private String schemeSnapshot;
    private Long totalCents;
    private Long firstCents;
    private String recipient;
    private String authKind;
    private Long policyId;
    private Integer policyVersion;
    private Long submittedByUserId;
    private Long submittedByPostId;
    private OffsetDateTime createTime;

}
