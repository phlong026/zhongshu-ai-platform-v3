package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 现场记录（普通/节点完成留痕 completion=true、更正留痕 corrections JSONB；验收=线下单人留痕 D-9） */
@TableName("fr_eng_site_record")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngSiteRecordDO {

    public static final String RESULT_PASS = "PASS";
    public static final String MODE_SINGLE_PERSON = "SINGLE_PERSON_RECORD";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private Long nodeId;
    private LocalDate occurredAt;
    private String note;
    private Boolean completion;
    private String result;
    private String reviewMode;
    /** 多图资产 JSONB 数组（基-1 票据链 ENG_SITE_PHOTO） */
    private String fileAssetIds;
    private String corrections;
    private Long authorUserId;
    private OffsetDateTime createTime;

}
