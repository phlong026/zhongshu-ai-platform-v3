package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileService;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.PROFILE_AVATAR_INVALID;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.PROFILE_NICKNAME_INVALID;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.USER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 员工本人资料 service 单测（08 文档基-3 AC10）。纯 Fake store 的无 Spring 单测：不写 system_users，
 * 真实 PG 落库与读回由 e2e（script/platform-profile-e2e.py AC4/AC9）覆盖。
 */
class StaffProfileServiceTest {

    private static final long USER = 42L;

    private StaffProfileService staffProfileService;
    private FakeStaffProfileStore store;

    @BeforeEach
    void setUp() {
        store = new FakeStaffProfileStore();
        staffProfileService = new StaffProfileService();
        ReflectionTestUtils.setField(staffProfileService, "store", store);
    }

    // ===== 读取与视图归一化 =====

    @Test
    void profileReadsStoreAndNormalizesEmptyAvatarToNull() {
        store.put(new UserProfile(USER, "张业务", "13800002688", ""));
        UserProfile view = staffProfileService.profile(USER);
        assertThat(view.nickname()).isEqualTo("张业务");
        assertThat(view.mobile()).isEqualTo("13800002688");
        assertThat(view.avatar()).isNull(); // 底座空串 → 未设置语义 null
    }

    @Test
    void profileOfUnknownUserThrowsUserNotFound() {
        assertThatThrownBy(() -> staffProfileService.profile(999))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(USER_NOT_FOUND.getCode()));
    }

    // ===== 昵称：trim 归一化 + 边界 + 拒绝集 =====

    @Test
    void nicknameIsTrimmedBeforeValidateAndSave() {
        store.put(profile());
        UserProfile updated = staffProfileService.update(USER, Map.of("nickname", " 张业务 "));
        assertThat(updated.nickname()).isEqualTo("张业务"); // trim 后值校验、保存、响应
        assertThat(store.current(USER).nickname()).isEqualTo("张业务");
    }

    @Test
    void nicknameBoundariesAccepted() {
        store.put(profile());
        assertThat(staffProfileService.update(USER, Map.of("nickname", "张")).nickname()).hasSize(1);
        assertThat(staffProfileService.update(USER, Map.of("nickname", "张".repeat(20))).nickname()).hasSize(20);
    }

    @Test
    void nicknameRejectionsThrowProfileNicknameInvalid() {
        store.put(profile());
        Map<String, Object> typed = new HashMap<>();
        typed.put("nickname", 123); // 类型错误
        Map<String, Object> nulled = new HashMap<>();
        nulled.put("nickname", null); // JSON null
        for (Map<String, Object> bad : java.util.List.<Map<String, Object>>of(
                Map.of(), // 缺键
                Map.of("nickname", ""), // 空串
                Map.of("nickname", "   "), // 纯空格
                Map.of("nickname", "张".repeat(21)), // 超长
                typed, nulled)) {
            assertThatThrownBy(() -> staffProfileService.update(USER, bad))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(PROFILE_NICKNAME_INVALID.getCode()));
        }
        assertThat(store.current(USER).nickname()).isEqualTo("原姓名"); // 失败请求不落库
    }

    // ===== 头像：键语义四态 =====

    @Test
    void avatarKeyMissingKeepsColumnUntouched() {
        store.put(new UserProfile(USER, "原姓名", "13800002688", "https://a/b.jpg"));
        staffProfileService.update(USER, Map.of("nickname", "新姓名"));
        assertThat(store.lastAvatarArg).isNull(); // store 收到「不写列」信号
        assertThat(store.current(USER).avatar()).isEqualTo("https://a/b.jpg");
    }

    @Test
    void avatarExplicitNullAndEmptyStringClearViaEmptyString() {
        store.put(new UserProfile(USER, "原姓名", "13800002688", "https://a/b.jpg"));
        staffProfileService.update(USER, avatarBody(null));
        assertThat(store.lastAvatarArg).isEqualTo(""); // 显式 null 归一为空串清除

        store.put(new UserProfile(USER, "原姓名", "13800002688", "https://a/b.jpg"));
        staffProfileService.update(USER, avatarBody(""));
        assertThat(store.lastAvatarArg).isEqualTo("");
        assertThat(store.current(USER).avatar()).isEqualTo("");
        assertThat(staffProfileService.profile(USER).avatar()).isNull(); // 视图归一化
    }

    @Test
    void avatarUrlUpdates() {
        store.put(profile());
        staffProfileService.update(USER, avatarBody("https://cdn.example.com/a.png"));
        assertThat(store.lastAvatarArg).isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void avatarLengthBoundary512Accepted513Rejected() {
        store.put(profile());
        String prefix = "https://x/";
        String exact = prefix + "a".repeat(512 - prefix.length());
        assertThat(exact).hasSize(512); // 底座列 varchar(512) 上限
        staffProfileService.update(USER, avatarBody(exact));
        assertThat(store.lastAvatarArg).isEqualTo(exact);

        String tooLong = exact + "a";
        assertThatThrownBy(() -> staffProfileService.update(USER, avatarBody(tooLong)))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(PROFILE_AVATAR_INVALID.getCode()));
    }

    @Test
    void avatarRejectionsThrowProfileAvatarInvalid() {
        store.put(new UserProfile(USER, "原姓名", "13800002688", "https://a/b.jpg"));
        for (Object bad : new Object[]{"javascript:alert(1)", "ftp://x", "https://a b.com", new HashMap<>()}) {
            assertThatThrownBy(() -> staffProfileService.update(USER, avatarBody(bad)))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(PROFILE_AVATAR_INVALID.getCode()));
        }
        assertThat(store.current(USER).avatar()).isEqualTo("https://a/b.jpg"); // 失败请求不落库
    }

    private UserProfile profile() {
        return new UserProfile(USER, "原姓名", "13800002688", null);
    }

    private Map<String, Object> avatarBody(Object avatar) {
        Map<String, Object> body = new HashMap<>();
        body.put("nickname", "张业务");
        body.put("avatar", avatar);
        return body;
    }

    /** 内存实现：记录 avatar 委托参数以断言三态归一 */
    static class FakeStaffProfileStore implements StaffProfileStore {

        final Map<Long, UserProfile> rows = new HashMap<>();
        String lastAvatarArg;

        void put(UserProfile profile) {
            rows.put(profile.userId(), profile);
            lastAvatarArg = null;
        }

        UserProfile current(long userId) {
            return rows.get(userId);
        }

        @Override
        public UserProfile findByUserId(long userId) {
            return rows.get(userId);
        }

        @Override
        public void updateProfile(long userId, String nickname, String avatar) {
            UserProfile old = rows.get(userId);
            String nextAvatar = avatar == null ? (old == null ? null : old.avatar()) : avatar;
            rows.put(userId, new UserProfile(userId, nickname, old == null ? "" : old.mobile(), nextAvatar));
            lastAvatarArg = avatar;
        }

    }

}
