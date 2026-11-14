package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;

/** 组织内容 AI 当日用量统计：(org_id, service, day) 唯一 + count；不作为业务准入或扣费门槛。 */
@TableName("fr_content_ai_usage")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentAiUsageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orgId;
    private String service;
    private LocalDate day;
    private Integer count;

}
