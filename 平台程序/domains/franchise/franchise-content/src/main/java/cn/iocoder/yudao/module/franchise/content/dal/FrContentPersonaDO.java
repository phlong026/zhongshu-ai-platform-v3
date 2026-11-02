package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 人设（16 §四/D-1.5-10）：组织共享库；产物落 personaId+快照，修改不影响已生成产物。 */
@TableName("fr_content_persona")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentPersonaDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String name;
    private String who;
    private String sell;
    private String audience;
    private String cta;
    private String ipType;
    private String track;
    private String differentiator;
    private Boolean enabled;
    private Long authorUserId;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private Boolean deleted;

}
