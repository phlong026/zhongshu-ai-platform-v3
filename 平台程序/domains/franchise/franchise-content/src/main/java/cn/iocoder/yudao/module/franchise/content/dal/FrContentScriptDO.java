package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 口播文案（16 §四）：JSONB 列（persona_snapshot/prompt_snapshot）经 JdbcTemplate CAST 写入，DO 侧为 String。 */
@TableName("fr_content_script")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentScriptDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long authorUserId;
    private Long taskId;
    private String sourceType;
    private String sourceUrl;
    private String platform;
    private String rawText;
    private String optimizedText;
    private String requirement;
    private Long personaId;
    private String personaSnapshot;
    private String engine;
    private String model;
    private String promptSnapshot;
    private Integer durationSeconds;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
