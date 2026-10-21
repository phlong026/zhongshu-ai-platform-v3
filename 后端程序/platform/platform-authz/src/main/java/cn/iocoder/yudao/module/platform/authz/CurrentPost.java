package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.POST_REQUIRED;

/**
 * 当前请求的工作身份（任职）。业务域通过它拿到「谁、以什么岗位、在哪个组织」办事。
 */
public interface CurrentPost {

    /** 前端（Web 与小程序）在每个请求上携带当前任职编号 */
    String HEADER = "X-Post-Id";

    /**
     * 当前工作身份；未登录、非后台用户、不在请求中或该用户没有任何有效任职时为空。
     * 请求头指向的任职不属于本人或已失效、或未带请求头而本人有多个任职时抛业务异常。
     */
    Optional<PostSnapshot> find();

    default PostSnapshot require() {
        return find().orElseThrow(() -> exception(POST_REQUIRED));
    }

}
