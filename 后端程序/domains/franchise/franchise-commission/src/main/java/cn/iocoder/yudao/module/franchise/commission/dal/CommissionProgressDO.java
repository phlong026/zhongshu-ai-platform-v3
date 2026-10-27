package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 设计行政记录（COMPLETE / DISPATCH）与业务修改意见（FEEDBACK）：只追加 */
@TableName("fr_commission_progress")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CommissionProgressDO {

    public static final String KIND_COMPLETE = "COMPLETE";
    public static final String KIND_DISPATCH = "DISPATCH";
    public static final String KIND_FEEDBACK = "FEEDBACK";

    public static final String FEEDBACK_PENDING = "PENDING";
    public static final String FEEDBACK_RESOLVED = "RESOLVED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long designOrgId;
    private Long commissionId;
    private String kind;
    /** 同委托同种类内递增；DISPATCH 的 seq 即下发版本 */
    private Integer seq;
    private String note;
    /** DISPATCH：本次交付清单 */
    private String recipient;
    private String feedbackStatus;
    /** FEEDBACK：针对哪一版下发 */
    private Integer dispatchSeq;
    private Long actorUserId;
    private Long actorPostId;
    private OffsetDateTime createTime;

}
