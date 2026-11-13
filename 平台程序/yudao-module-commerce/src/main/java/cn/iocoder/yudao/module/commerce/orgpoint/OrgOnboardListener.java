package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.module.platform.org.OrgOnboardedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 组织开通 → 公司积分池（24 号任务书 B-8）。
 *
 * <p>幂等语义：
 * <ul>
 *   <li>designEnabled=true：建池（已存在不动）+ 首次赠点（biz_key=onboard-grant:{orgId} 唯一）；</li>
 *   <li>designEnabled=false / 省略：不动既有开通状态（不建池、不赠点、不停用）。</li>
 * </ul>
 * 监听同步执行：池写入失败会抛出并回滚开通事务（同一事务内），避免"组织已建但无池"的中间态。
 */
@Slf4j
@Component
public class OrgOnboardListener {

    private final OrgPointService orgPointService;

    public OrgOnboardListener(OrgPointService orgPointService) {
        this.orgPointService = orgPointService;
    }

    @EventListener
    public void onOnboarded(OrgOnboardedEvent event) {
        // 评审 P1-2：只要要求赠点也必须建池（否则赠点写库+余额 0 的账实不一致）
        if (!event.designEnabled() && event.initialCredits() <= 0) {
            return;
        }
        orgPointService.ensurePoolAccount(event.orgId());
        if (event.initialCredits() > 0) {
            boolean granted = orgPointService.grantOnboardOnce(event.orgId(), event.initialCredits(),
                    "组织开通赠点");
            if (granted) {
                log.info("[onOnboarded][orgId={} 开通赠点 {}]", event.orgId(), event.initialCredits());
            }
        }
    }
}
