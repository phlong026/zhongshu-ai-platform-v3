package cn.iocoder.yudao.module.platform.org.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileService;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 员工本人资料（08 文档基-3）：登录即可访问，不挂菜单权限、不要求 X-Post-Id
 * （同 {@code MyPostController} 先例）。只操作登录者本人数据，无组织 scope。
 * 手机号只读：换号涉及绑定作废与令牌吊销，属管理员成员管理能力，不开放本人自助。
 */
@Tag(name = "平台 - 员工资料")
@RestController
@RequestMapping("/platform/profile")
public class StaffProfileController {

    @Resource
    private StaffProfileService staffProfileService;

    /** 视图：avatar 空串/未设置归一化为 null */
    public record StaffProfileView(long id, String nickname, String mobile, String avatar) {

        static StaffProfileView of(UserProfile profile) {
            return new StaffProfileView(profile.userId(), profile.nickname(), profile.mobile(), profile.avatar());
        }
    }

    @GetMapping("/mine")
    @Operation(summary = "本人资料（姓名/手机号/头像）")
    public CommonResult<StaffProfileView> mine() {
        return success(StaffProfileView.of(staffProfileService.profile(currentUserId())));
    }

    @PutMapping("/mine")
    @Operation(summary = "更新本人姓名与头像；mobile 只读，传入被忽略",
            description = "avatar 键缺省=保持不变，显式 null/空串=清除，非空=更新为该 URL")
    public CommonResult<StaffProfileView> updateMine(@RequestBody Map<String, Object> body) {
        // 接收 Map 而非 VO：需区分 avatar 键缺省与显式 null（两种保持/清除语义），白名单解析在 service
        return success(StaffProfileView.of(staffProfileService.update(currentUserId(), body)));
    }

    private static long currentUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            // 未认证请求已被 Spring Security 拦截（CommonResult.code=401）；防御兜底
            throw new IllegalStateException("登录态缺失");
        }
        return userId;
    }

}
