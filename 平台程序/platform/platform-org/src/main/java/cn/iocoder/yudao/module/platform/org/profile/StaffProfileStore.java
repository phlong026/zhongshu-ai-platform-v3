package cn.iocoder.yudao.module.platform.org.profile;

/**
 * 员工本人资料的存储面。与 {@link cn.iocoder.yudao.module.platform.org.SystemDirectory} 同一解耦先例：
 * 生产实现走底座 system 模块，测试用内存 Fake 替换，避免拉起 system 全家。
 */
public interface StaffProfileStore {

    /** 员工资料读模型（avatar 为底座原值，空串/未设置的归一化在视图层做） */
    record UserProfile(long userId, String nickname, String mobile, String avatar) {
    }

    /** 按用户编号取资料；不存在返回 null */
    UserProfile findByUserId(long userId);

    /**
     * 更新姓名与头像。
     *
     * @param avatar null=不写头像列（保持原值）；空串=清除；非空=更新为该 URL。
     *               三态由本批 avatar 键语义（08 文档 §四）归一而来。
     */
    void updateProfile(long userId, String nickname, String avatar);

}
