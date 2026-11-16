package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import java.util.Map;
import java.util.Set;

/** 与小程序岗位课程目录保持一致；筛选进度和章节写入都执行服务端岗位校验。 */
public final class CourseAccess {
    private CourseAccess() {}
    private static final Map<String, Set<String>> COURSES = Map.ofEntries(
            Map.entry("sales", Set.of("ai-video", "sales")), Map.entry("sales-lead", Set.of("ai-video", "sales")),
            Map.entry("engineer", Set.of("engineering")), Map.entry("project-manager", Set.of("engineering")),
            Map.entry("finance", Set.of("finance")), Map.entry("finance-lead", Set.of("finance")),
            Map.entry("hq-design-admin", Set.of("design")), Map.entry("hq-designer", Set.of("design")),
            Map.entry("hq-design-lead", Set.of("design")), Map.entry("boss", Set.of("management")),
            Map.entry("owner", Set.of("management")), Map.entry("hq-operations", Set.of("ai-video")),
            Map.entry("hq-operations-lead", Set.of("ai-video")));
    public static boolean allows(String role, String course) {
        return role != null && course != null && COURSES.getOrDefault(role, Set.of()).contains(course);
    }
    public static void require(String role, String course) {
        if (!allows(role, course)) throw new ServiceException(403, "当前岗位无此课程权限");
    }
    public static void requireChapter(String role, String course, int chapter) {
        require(role, course);
        if (chapter < 0 || chapter >= ("ai-video".equals(course) ? 8 : 6))
            throw new ServiceException(400, "课程章节不存在");
    }
}
