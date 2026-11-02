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
 * 收货与补发统一表（◆经典链质检/补发机制吸收）：RECEIPT=收货（quality OK/REJECTED + quality_resolution 闭环）；
 * RESHIP=差异自动补发行（数量=应收-实收，parent_receipt_id 挂原收货）；transaction_no 全公司唯一。
 */
@TableName("fr_eng_supply_receipt")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngSupplyReceiptDO {

    public static final String KIND_RECEIPT = "RECEIPT";
    public static final String KIND_RESHIP = "RESHIP";
    public static final String QUALITY_OK = "OK";
    public static final String QUALITY_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long supplyId;
    private String kind;
    private Long parentReceiptId;
    private String transactionNo;
    private String batchCode;
    private BigDecimal expectedQuantity;
    private BigDecimal actualQuantity;
    private String quality;
    /** 质检处置 JSONB（result=更换后合格 + note/file） */
    private String qualityResolution;
    private LocalDate occurredAt;
    private Long fileAssetId;
    private String note;
    private String corrections;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
