package cn.iocoder.yudao.module.platform.org.profile;

import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore.UserProfile;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.PROFILE_AVATAR_INVALID;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.PROFILE_NICKNAME_INVALID;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.USER_NOT_FOUND;

/**
 * 员工本人资料（08 文档基-3）：读取与更新白名单字段（姓名/头像），手机号只读。
 * 校验在 service 层抛业务异常而非 Bean Validation（错误码 1_101_000_011/012）。
 */
@Service
public class StaffProfileService {

    static final int NICKNAME_MAX = 20;
    static final int AVATAR_MAX = 512; // 底座 system_users.avatar 为 varchar(512)，超长落库失败

    @Resource
    private StaffProfileStore store;

    /** 本人资料；头像空串归一化为 null（未设置语义） */
    public UserProfile profile(long userId) {
        UserProfile profile = requireProfile(userId);
        return normalize(profile);
    }

    /**
     * 更新姓名与头像，返回更新后的资料。契约（08 文档 §四）：
     * nickname 必填 String，先 trim，trim 后 1-20；avatar 键缺省=保持、显式 null/空串=清除、URL=更新。
     * 校验全部通过才落库，失败时资料不变。
     */
    public UserProfile update(long userId, Map<String, Object> body) {
        Object rawNickname = body.get("nickname"); // 键缺失与 JSON null 同为 null，均按必填缺失处理
        if (!(rawNickname instanceof String rawName)) {
            throw exception(PROFILE_NICKNAME_INVALID);
        }
        String nickname = rawName.trim();
        if (nickname.isEmpty() || nickname.length() > NICKNAME_MAX) {
            throw exception(PROFILE_NICKNAME_INVALID);
        }
        String avatar = null; // store 契约：null=不写列
        if (body.containsKey("avatar")) {
            Object rawAvatar = body.get("avatar");
            if (rawAvatar == null) {
                avatar = ""; // 显式 null 归一为空串清除（底座 updateById 对 null 不落库）
            } else if (rawAvatar instanceof String url) {
                if (!url.isEmpty() && !isValidAvatarUrl(url)) {
                    throw exception(PROFILE_AVATAR_INVALID);
                }
                avatar = url;
            } else {
                throw exception(PROFILE_AVATAR_INVALID);
            }
        }
        store.updateProfile(userId, nickname, avatar);
        return normalize(requireProfile(userId));
    }

    private UserProfile requireProfile(long userId) {
        UserProfile profile = store.findByUserId(userId);
        if (profile == null) {
            throw exception(USER_NOT_FOUND);
        }
        return profile;
    }

    private UserProfile normalize(UserProfile profile) {
        String avatar = profile.avatar() == null || profile.avatar().isEmpty() ? null : profile.avatar();
        return new UserProfile(profile.userId(), profile.nickname(), profile.mobile(), avatar);
    }

    /** http(s) URL、无空白字符、长度 ≤512（512 为底座列上限） */
    static boolean isValidAvatarUrl(String url) {
        if (url.length() > AVATAR_MAX) {
            return false;
        }
        String rest = null;
        if (url.startsWith("https://")) {
            rest = url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            rest = url.substring("http://".length());
        }
        return rest != null && !rest.isEmpty() && !rest.isBlank()
                && rest.chars().noneMatch(Character::isWhitespace);
    }

}
