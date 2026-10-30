package cn.iocoder.yudao.module.design.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 补充需求「AI 优化」配置。供应商密钥只在 ai-runtime 进程里，后端只需要知道 runtime 的地址；
 * 内部签名用的共享密钥复用 {@code zhongshu.ai.internal-secret}（与 runtime 领任务时同一把），不在此重复声明。
 */
@Data
@ConfigurationProperties(prefix = "zhongshu.ai.note-polish")
public class NotePolishProperties {

    /** ai-runtime 的 origin（不含路径/凭据），如 http://127.0.0.1:8081；为空表示未启用 */
    private String runtimeUrl;
}
