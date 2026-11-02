package cn.iocoder.yudao.module.franchise.content.provider;

import java.util.List;
import java.util.function.Consumer;

/** AI 视频生成端口（D-1.5-6）：H3 提交/查询/直链过期探测。 */
public interface VideoGenerationPort {

    /** 提交即持久化：onCreated 在拿到任务号后、返回前回调（A2.1）。含首尾帧时 ratio 由实现归一为 adaptive。 */
    String submit(H3Request request, Consumer<String> onCreated, ProviderCallAudit audit);

    H3Snapshot poll(String providerTaskId, ProviderCallAudit audit);

    /** 直链 HEAD 过期探测：true=已失效，false=可用，null=无法判断（A2.3）。 */
    Boolean probeUrlExpired(String url, ProviderCallAudit audit);

    /** role=first_frame/last_frame/reference_image/reference_video/reference_audio；reference_image 需 name。 */
    record MediaRef(String role, String url, String name) {
    }

    /** resolution=768P|2K；durationSeconds 4-15 整数；ratio=adaptive|21:9|16:9|4:3|1:1|3:4|9:16。 */
    record H3Request(String description, List<MediaRef> media, String resolution,
                     int durationSeconds, String ratio) {
    }

    /** status=RUNNING/SUCCEEDED/FAILED/CANCELLED；未知状态一律 RUNNING 继续等（16a A2.2）。 */
    record H3Snapshot(String status, String resultUrl, Double outputSeconds, String failureReason) {
    }

}
