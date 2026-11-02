package cn.iocoder.yudao.module.franchise.content.provider;

/** 分享链接解析端口（D-1.5-3）：分享链接 → 无水印直链+文案+封面。 */
public interface LinkParsePort {

    ParsedLink parse(String shareUrl, ProviderCallAudit audit);

    /** platform=douyin|xiaohongshu；text=上游文案（creation 页「有文案直接用」的来源）；durationMs=0 表示上游未给。 */
    record ParsedLink(String platform, String videoUrl, String audioUrl, String title,
                      String text, String coverUrl, String author, long durationMs) {
    }

}
