package cn.iocoder.yudao.module.franchise.content.provider;

import java.util.Map;
import java.util.function.Consumer;

/** 数字人口播端口（D-1.5-5）：create_by_tts 提交（返回供应商任务号）+ 轮询状态 1/2/3/4。 */
public interface OralVideoPort {

    /** 提交即持久化：onCreated 在拿到任务号后、返回前回调（A2.1）。 */
    String createByTts(TtsRequest request);

    Snapshot poll(String providerTaskId, ProviderCallAudit audit);

    /** subtitle=平铺字幕参数（st_show/st_font_name/st_font_size/st_width/st_height/st_x/st_y/st_primary_color/st_outline_color）。 */
    record TtsRequest(String voice, String text, String avatar, String title,
                      Map<String, Object> subtitle, Consumer<String> onCreated, ProviderCallAudit audit) {
    }

    /** status=WAITING/PROCESSING/DONE/FAILED/UNKNOWN（vendor 1/2/3/4；未知一律 UNKNOWN 按在途继续轮询）。 */
    record Snapshot(String status, String videoUrl, Integer durationSec) {
    }

}
