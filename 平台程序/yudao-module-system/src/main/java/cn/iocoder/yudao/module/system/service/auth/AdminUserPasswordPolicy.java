package cn.iocoder.yudao.module.system.service.auth;

import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;

/** Password policy extension supplied by business modules without a reverse module dependency. */
public interface AdminUserPasswordPolicy {
    void validateLogin(AdminUserDO user);

    /** Called in the password update transaction, before validating or changing the stored password. */
    default void beforePasswordChange(AdminUserDO user) { }

    /** Called only after a successful password write, in that same transaction. */
    default void passwordChanged(AdminUserDO user) { }
}
