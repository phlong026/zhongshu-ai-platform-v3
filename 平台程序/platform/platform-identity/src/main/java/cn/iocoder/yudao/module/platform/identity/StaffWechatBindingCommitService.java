package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.STAFF_NOT_ENROLLED;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.WECHAT_ALREADY_BOUND_OTHER;

/** 微信换码完成后的短事务：锁定并复核账号与任职后才提交绑定。 */
@Service
public class StaffWechatBindingCommitService {

    @Resource private JdbcTemplate jdbc;
    @Resource private StaffPostService staffPostService;
    @Resource private WechatBindingMapper bindingMapper;
    @Resource private StaffWechatBindingService bindingService;

    private record LockedAccount(String mobile, int status) {}

    @Transactional(rollbackFor = Exception.class)
    public void commit(String appid, String openid, long userId, String expectedMobile, long postId) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        List<LockedAccount> accounts = jdbc.query("""
                        SELECT mobile,status FROM system_users
                        WHERE id=? AND tenant_id=? AND deleted=0 FOR UPDATE
                        """, (rs, row) -> new LockedAccount(rs.getString(1), rs.getInt(2)), userId, tenantId);
        if (accounts.isEmpty() || accounts.get(0).status() != CommonStatusEnum.ENABLE.getStatus()
                || !Objects.equals(accounts.get(0).mobile(), expectedMobile)) {
            throw exception(STAFF_NOT_ENROLLED);
        }
        // 锁序保持 用户(system_users) → 岗位；撤岗必须等待本事务结束，或先完成并令本次复核失败。
        staffPostService.revalidateActivePost(userId, postId);

        WechatBindingDO occupied = bindingMapper.selectActive(appid, openid);
        if (occupied != null) {
            if (occupied.getUserId().equals(userId)) return;
            throw exception(WECHAT_ALREADY_BOUND_OTHER);
        }
        bindingService.bind(appid, openid, userId, accounts.get(0).mobile());
    }
}
