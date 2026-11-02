package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 催办双维度（D-10）：COLLECTION=stage 维度 / MATERIAL=material_plan_id 维度；同日同内容幂等（服务层） */
@TableName("fr_eng_followup")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngFollowupDO {

    public static final String KIND_COLLECTION = "COLLECTION";
    public static final String KIND_MATERIAL = "MATERIAL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private String kind;
    private String stage;
    private Long materialPlanId;
    private LocalDate promiseDate;
    private String note;
    private Long authorUserId;
    private OffsetDateTime createTime;

}
