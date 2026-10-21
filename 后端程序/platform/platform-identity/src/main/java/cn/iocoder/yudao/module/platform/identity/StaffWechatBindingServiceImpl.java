package cn.iocoder.yudao.module.platform.identity;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.platform.identity.dal.*;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO.*;
import static cn.iocoder.yudao.module.platform.identity.dal.WechatBindingEventDO.*;

@Service
@Slf4j
public class StaffWechatBindingServiceImpl implements StaffWechatBindingService {

    /** 这两类失效说明微信持有人可能已不是本人，需要吊销已签发的令牌 */
    private static final Set<String> TOKEN_REVOKING_REASONS = Set.of(REASON_MOBILE_CHANGED, REASON_USER_DISABLED);
    /** 员工没有任何任职记录时审计记到组织 0（平台级），不会被任何组织老板看到 */
    private static final long NO_ORG = 0L;

    @Resource
    private WechatBindingMapper bindingMapper;
    @Resource
    private WechatBindingEventMapper eventMapper;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private TokenIssuer tokenIssuer;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WechatBindingDO bind(String appid, String openid, long userId, String mobile) {
        WechatBindingDO previous = bindingMapper.selectActiveByUser(appid, userId);
        if (previous != null) {
            bindingMapper.revoke(previous.getId(), REASON_REBOUND);
        }
        WechatBindingDO binding = new WechatBindingDO().setAppid(appid).setOpenid(openid).setUserId(userId)
                .setMobileHash(hashMobile(mobile)).setStatus(STATUS_ACTIVE);
        bindingMapper.insert(binding);
        record(binding, previous == null ? TYPE_BOUND : TYPE_REBOUND, null,
                previous == null ? null : previous.getId(), null);
        return binding;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(WechatBindingDO binding, String reason, Long operatorUserId) {
        if (bindingMapper.revoke(binding.getId(), reason) != 1) {
            return; // 已被并发作废，审计由先作废的一方记录
        }
        record(binding, TYPE_REVOKED, reason, null, operatorUserId);
        if (TOKEN_REVOKING_REASONS.contains(reason)) {
            tokenIssuer.revokeAll(binding.getUserId());
        }
        log.warn("[revoke][appid={} userId={} bindingId={}] 微信绑定失效：{}，操作人 {}",
                binding.getAppid(), binding.getUserId(), binding.getId(), reason, operatorUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllForUser(long userId, String reason, Long operatorUserId) {
        if (!TOKEN_REVOKING_REASONS.contains(reason)) {
            throw new IllegalArgumentException("人员管理只能以改号或停用作废绑定：" + reason);
        }
        for (WechatBindingDO binding : bindingMapper.selectActiveByUserInAllApps(userId)) {
            if (bindingMapper.revoke(binding.getId(), reason) == 1) {
                record(binding, TYPE_REVOKED, reason, null, operatorUserId);
            }
        }
        // 没有微信绑定也要吊销：Web 端令牌同样可能由改号前的人持有
        tokenIssuer.revokeAll(userId);
        log.warn("[revokeAllForUser][userId={}] 人员管理触发绑定作废与令牌吊销：{}，操作人 {}", userId, reason, operatorUserId);
    }

    @Override
    public PageResult<WechatBindingEventDO> pageEvents(PageParam page, Long userId) {
        return eventMapper.selectPage(page, userId);
    }

    static String hashMobile(String mobile) {
        return DigestUtil.sha256Hex(mobile.trim());
    }

    private void record(WechatBindingDO binding, String type, String reason, Long previousBindingId, Long operatorUserId) {
        List<Long> orgIds = staffPostService.listOrgIdsEverHeld(binding.getUserId());
        OffsetDateTime now = OffsetDateTime.now();
        for (Long orgId : orgIds.isEmpty() ? List.of(NO_ORG) : orgIds) {
            eventMapper.insert(new WechatBindingEventDO().setOrgId(orgId).setUserId(binding.getUserId())
                    .setAppid(binding.getAppid()).setBindingId(binding.getId()).setPreviousBindingId(previousBindingId)
                    .setEventType(type).setReason(reason).setOperatorUserId(operatorUserId).setOccurredAt(now));
        }
    }

}
