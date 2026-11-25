package cn.iocoder.yudao.module.platform.announcement;

import java.util.List;

/** Wire types use string identifiers and UTC ISO-8601 timestamps. Content is plain text. */
public final class AnnouncementModel {
    private AnnouncementModel() { }

    public record Save(String title, String summary, String content, String visibility,
                       List<String> brandIds, boolean pinned, Long expectedRevision) { }
    public record Action(Long expectedRevision) { }
    public record Brand(String id, String name) { }
    public record Options(boolean canWrite, List<Brand> brands) { }
    public record Page<T>(List<T> list, long total) { }
    public record Summary(String id, String title, String summary, String visibility,
                          List<String> brandIds, boolean pinned, String status, long revision,
                          String publishedAt, String createdAt, String updatedAt) { }
    public record Detail(String id, String title, String summary, String content, String visibility,
                         List<String> brandIds, boolean pinned, String status, long revision,
                         String publishedAt, String createdAt, String updatedAt) {
        public Summary asSummary() {
            return new Summary(id, title, summary, visibility, brandIds, pinned, status, revision,
                    publishedAt, createdAt, updatedAt);
        }
    }
}
