package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 收款登记行（登记即已核算 C4；经 BusinessReceiptPort 写通 finance，receipt_id 关联收据） */
@TableName("fr_eng_collection")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngCollectionDO {

    public static final String STAGE_START = "START";
    public static final String STAGE_TOPPING = "TOPPING";
    public static final String PURPOSE_START = "工程预收款";
    public static final String PURPOSE_TOPPING = "工程尾款";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private String stage;
    private Long amountCents;
    private String transactionNo;
    private LocalDate occurredAt;
    private Long fileAssetId;
    private Long receiptId;
    private String purpose;
    private Long registrarUserId;
    private OffsetDateTime createTime;

}
