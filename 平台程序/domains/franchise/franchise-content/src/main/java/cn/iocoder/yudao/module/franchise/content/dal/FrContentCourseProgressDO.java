package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 课程进度（16 §四）：(author_user_id, course_id, chapter_index) 唯一。 */
@TableName("fr_content_course_progress")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentCourseProgressDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long authorUserId;
    private String courseId;
    private Integer chapterIndex;
    private OffsetDateTime completedAt;
    private OffsetDateTime createTime;

}
