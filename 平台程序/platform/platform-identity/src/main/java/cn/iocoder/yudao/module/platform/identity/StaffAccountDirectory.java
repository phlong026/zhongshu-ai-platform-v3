package cn.iocoder.yudao.module.platform.identity;

import java.util.List;
import java.util.Optional;

/**
 * 员工账号查询（yudao 后台用户）。测试中可替换。
 */
public interface StaffAccountDirectory {

    Optional<StaffAccount> find(long userId);

    /** 手机号对应的全部账号（正常情况下至多一个；多个时登录拒绝） */
    List<StaffAccount> findByMobile(String mobile);

    record StaffAccount(long userId, String mobile, boolean enabled) {
    }

}
