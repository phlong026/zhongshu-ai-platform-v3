package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** ◆材料去向（经典链吸收）：领用耗用/报损/退回/余料结转；首批仅登记态（待决④对账随阶段 3） */
@TableName("fr_eng_material_movement")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngMaterialMovementDO {

    public static final String KIND_CONSUME = "CONSUME";
    public static final String KIND_DAMAGE = "DAMAGE";
    public static final String KIND_RETURN = "RETURN";
    public static final String KIND_SURPLUS_TRANSFER = "SURPLUS_TRANSFER";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long materialId;
    private String kind;
    private BigDecimal quantity;
    private String settlementStatus;
    private Long receiverUserId;
    private String note;
    private Long authorUserId;
    private OffsetDateTime createTime;

}
