package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.*;

/**
 * 从登录用户与请求头 {@link #HEADER} 解析当前工作身份。每个请求实时校验一次任职（撤销、停用立即生效），结果缓存在请求内。
 */
@Component
@Slf4j
public class RequestCurrentPost implements CurrentPost {

    private static final String ATTRIBUTE = RequestCurrentPost.class.getName();
    private static final Optional<PostSnapshot> NONE = Optional.empty();

    private final StaffPostService staffPostService;

    public RequestCurrentPost(StaffPostService staffPostService) {
        this.staffPostService = staffPostService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PostSnapshot> find() {
        HttpServletRequest request = WebFrameworkUtils.getRequest();
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        if (request == null || user == null || !Objects.equals(user.getUserType(), UserTypeEnum.ADMIN.getValue())) {
            return NONE;
        }
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached != null) {
            return (Optional<PostSnapshot>) cached;
        }
        Optional<PostSnapshot> resolved = resolve(user.getId(), request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, resolved);
        return resolved;
    }

    private Optional<PostSnapshot> resolve(long userId, String header) {
        if (header != null && !header.isBlank()) {
            long postId;
            try {
                postId = Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                throw exception(POST_HEADER_INVALID);
            }
            Optional<PostSnapshot> post = staffPostService.findActivePost(userId, postId);
            if (post.isEmpty()) {
                log.warn("[resolve][userId={} postId={}] 请求的工作身份不属于本人或已失效", userId, postId);
                throw exception(POST_NOT_USABLE);
            }
            return post;
        }
        List<StaffPostDO> posts = staffPostService.listActivePosts(userId);
        if (posts.isEmpty()) {
            return NONE;
        }
        if (posts.size() > 1) {
            throw exception(POST_SELECTION_REQUIRED);
        }
        // 唯一任职也要走完整校验（组织可能已停用）
        return staffPostService.findActivePost(userId, posts.get(0).getId());
    }

}
