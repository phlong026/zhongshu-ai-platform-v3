package cn.iocoder.yudao.module.design.asset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内容安全审核端口（架构 §6.9：内容审核与版权授权是两个独立状态）
 *
 * 直通 Stub（自动 PASSED）；真实内容安全 Adapter（如云检测）就绪后替换，
 * 未通过 moderation 的资产不能下载、发布或进入 Provider。
 *
 * 装配条件（T13-01）：zhongshu.design.asset.moderation.provider=stub。内容审核真实接入为后续批次（B3 后置），
 * 本期保留端口与门禁；生产禁止 stub，由 ZhongshuWiringEnvironmentPostProcessor 启动守卫强制。
 */
@Component
@ConditionalOnProperty(prefix = "zhongshu.design.asset.moderation", name = "provider", havingValue = "stub")
public class StubContentModerationAdapter implements ContentModerationPort {

    /**
     * 审核故障夹具（B2 板块 A / P-7⑤，仅 local dev 控制器切换）：
     * 置位后 review 抛 ModerationUnavailableException，用于验收退避期 PENDING 语义；生产装配不存在此类。
     */
    public static final java.util.concurrent.atomic.AtomicBoolean FAULT_MODE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public boolean pass(String assetType, byte[] content) {
        if (FAULT_MODE.get()) {
            throw new ModerationUnavailableException();
        }
        // P0 直通；内容审核决策与版权授权互不影响
        return true;
    }

}
