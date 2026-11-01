package cn.iocoder.yudao.module.platform.org.profile;

import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 基于 yudao system 模块 {@link AdminUserService} 的员工资料存储实现。
 * 复用底座的用户存在校验、mobile/email 唯一性与资料变更事件；本模块只收敛白名单字段。
 */
@Component
public class YudaoStaffProfileStore implements StaffProfileStore {

    @Resource
    private AdminUserService adminUserService;

    @Override
    public UserProfile findByUserId(long userId) {
        AdminUserDO user = adminUserService.getUser(userId);
        if (user == null) {
            return null;
        }
        return new UserProfile(user.getId(), user.getNickname(), user.getMobile(), user.getAvatar());
    }

    @Override
    public void updateProfile(long userId, String nickname, String avatar) {
        UserProfileUpdateReqVO req = new UserProfileUpdateReqVO();
        req.setNickname(nickname);
        // avatar null=不设置字段：底座 updateById 在 MP NOT_NULL 策略下天然不写该列（保持原值）
        if (avatar != null) {
            req.setAvatar(avatar);
        }
        adminUserService.updateUserProfile(userId, req);
    }

}
