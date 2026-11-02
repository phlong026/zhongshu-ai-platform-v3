package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 供应（D-6）：terms 字段组=供应商/单价/运费/依据；三态 PENDING_TERMS/READY_FINANCE/CANCELLED（到货态由 receipts 派生）；
 * payer 枚举保留三方=设计超前（蓝本 recordFlow 固定 STORE）；terms 后已付款锁定（paid 快照+Port 实时复核）。
 */
@TableName("fr_eng_supply")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngSupplyDO {

    public static final String STATUS_PENDING_TERMS = "PENDING_TERMS";
    public static final String STATUS_READY_FINANCE = "READY_FINANCE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String PAYER_STORE = "STORE";
    public static final String PAYER_HQ = "HQ";
    public static final String PAYER_OWNER_DIRECT = "OWNER_DIRECT";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long materialId;
    private BigDecimal quantity;
    private String supplier;
    private String contact;
    private LocalDate promiseDate;
    private Long unitPriceCents;
    private Long freightCents;
    private String payer;
    /** 采购依据（文件 JSON 引用，凭据走基-1 票据链） */
    private String basis;
    private String status;
    private Long termsUserId;
    private OffsetDateTime termsAt;
    private Boolean paid;
    /** 财务应付锚（terms 生成时经 PayablePort 写入；付款本体在 finance） */
    private Long payableId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
