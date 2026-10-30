package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class YudaoStaffAccountDirectory implements StaffAccountDirectory {

    @Resource
    private AdminUserMapper adminUserMapper;

    @Override
    public Optional<StaffAccount> find(long userId) {
        return Optional.ofNullable(adminUserMapper.selectById(userId)).map(YudaoStaffAccountDirectory::toAccount);
    }

    @Override
    public List<StaffAccount> findByMobile(String mobile) {
        return adminUserMapper.selectList(AdminUserDO::getMobile, mobile).stream()
                .map(YudaoStaffAccountDirectory::toAccount).toList();
    }

    private static StaffAccount toAccount(AdminUserDO user) {
        return new StaffAccount(user.getId(), user.getMobile(),
                Objects.equals(user.getStatus(), CommonStatusEnum.ENABLE.getStatus()));
    }

}
