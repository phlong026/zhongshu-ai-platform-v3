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

/** 材料计划行（material_plan_id=计划单分组：调整/材料催办挂该维度；0=取消） */
@TableName("fr_eng_material")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngMaterialDO {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long materialPlanId;
    private Long nodeId;
    private String name;
    private String spec;
    private String unit;
    private BigDecimal quantity;
    private LocalDate requiredDate;
    private String status;
    private OffsetDateTime createTime;

}
