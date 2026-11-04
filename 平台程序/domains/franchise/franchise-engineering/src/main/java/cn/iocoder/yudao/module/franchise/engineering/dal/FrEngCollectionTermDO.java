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

    /** 存量两期值（迁移 818 映射前），仅供兼容引用 */
    public static final String STAGE_START = "START";
    public static final String STAGE_TOPPING = "TOPPING";
    /** M07-T06 三期（与销售域 M06-T04 枚举对齐） */
    public static final String STAGE_SIGN_AFTER = "SIGN_AFTER";
    public static final String STAGE_BASE_DONE = "BASE_DONE";
    public static final String STAGE_CAP_DONE = "CAP_DONE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private String stage;
    private Long amountCents;
    private OffsetDateTime createTime;

}
