package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 视频草稿（一人一份，author 唯一；16 §四）。 */
@TableName("fr_content_video_draft")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentVideoDraftDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String draftKey;
    private Long revision;
    private Long companyId;
    private Long authorUserId;
    private String mode;
    private String description;
    private Long firstFrameAssetId;
    private Long lastFrameAssetId;
    private String parameters;
    /** 参考素材（JSONB；任务书 §四 名为 references——PG 保留字，列名定为 reference_media） */
    private String referenceMedia;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
