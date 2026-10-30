package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * 设计委托（一个客户一条）：状态机见 M2 设计 §7（sales-offline-v1）。
 * 显示状态由存储状态 + 首尾款进度（first_ready_at / tail_paid_at / tail_ready_at）推出。
 */
@TableName("fr_commission")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CommissionDO {

    public static final String STATUS_FEE_PENDING = "FEE_PENDING";
    public static final String STATUS_FEE_RETURNED = "FEE_RETURNED";
    public static final String STATUS_DESIGNING = "DESIGNING";
    public static final String STATUS_DESIGN_COMPLETED = "DESIGN_COMPLETED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_HANDOVER_SUBMITTED = "HANDOVER_SUBMITTED";
    public static final String STATUS_HANDOVER_RETURNED = "HANDOVER_RETURNED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long designOrgId;
    private Long customerId;
    private String customerName;
    private String status;
    private Integer submissionVersion;
    /** 当前有效下发版本；登记修改意见后清空 */
    private Integer dispatchSeq;
    private Integer handoverVersion;
    /** 首款：门店已付足且总部已核实到账（franchise-api DesignFeeProgressApi 写入） */
    private OffsetDateTime firstReadyAt;
    /** 尾款：门店已付足 */
    private OffsetDateTime tailPaidAt;
    /** 尾款：门店已付足且总部已核实到账 */
    private OffsetDateTime tailReadyAt;
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
