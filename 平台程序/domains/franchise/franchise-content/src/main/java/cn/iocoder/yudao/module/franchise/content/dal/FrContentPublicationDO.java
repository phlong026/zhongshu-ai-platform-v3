package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 发布记录 direct-v1 状态机（16 §四/D-1.5-11）：五态 + 签名 + 链接唯一 + (work_id,account_id) 非失败防重。 */
@TableName("fr_content_publication")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentPublicationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long authorUserId;
    private Long workId;
    private String title;
    private String publishText;
    private String tags;
    private Long coverAssetId;
    private String accountId;
    private String platform;
    private OffsetDateTime scheduledAt;
    private String status;
    private String publishedUrl;
    private OffsetDateTime publishedAt;
    private String versions;
    private String signature;
    private String failureReason;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
