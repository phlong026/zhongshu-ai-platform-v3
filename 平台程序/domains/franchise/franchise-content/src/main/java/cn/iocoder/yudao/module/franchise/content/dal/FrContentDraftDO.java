package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 草稿与作品（16 §四）：task_id 部分唯一（一任务一件）。 */
@TableName("fr_content_draft")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentDraftDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long authorUserId;
    private Long taskId;
    private String kind;
    private String title;
    private String script;
    private String configuration;
    private Long coverAssetId;
    private String status;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private Boolean deleted;

}
