package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 收款约定（开工准备即固化两行 START/TOPPING；拍板无审批直接生效） */
@TableName("fr_eng_collection_term")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngCollectionTermDO {

    public static final String STAGE_START = "START";
    public static final String STAGE_TOPPING = "TOPPING";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private String stage;
    private Long amountCents;
    private OffsetDateTime createTime;

}
