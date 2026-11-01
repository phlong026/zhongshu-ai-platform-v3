package cn.iocoder.yudao.module.platform.workflow.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import cn.iocoder.yudao.module.platform.workflow.message.PlatformMessageService;
import cn.iocoder.yudao.module.platform.workflow.message.MessageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 平台员工消息（基-2 消息中心，11 文档）：登录即可访问，不挂菜单权限、不要求 X-Post-Id
 * （同 {@code StaffProfileController} 先例）。消息按人收：列表/已读/未读数均只操作登录者本人数据，
 * 越权面=无，未认证由 Security 拦为 (200,401)。
 */
@Tag(name = "平台 - 员工消息")
@RestController
@RequestMapping("/platform/messages")
public class PlatformMessageController {

    @Resource
    private PlatformMessageService platformMessageService;

    @GetMapping
    @Operation(summary = "本人消息列表：id 倒序；beforeId 游标取更早一页；limit 缺省 20、收敛 [1,100]")
    public CommonResult<List<MessageView>> list(@RequestParam(value = "beforeId", required = false) Long beforeId,
                                                @RequestParam(value = "limit", required = false) Integer limit) {
        return success(platformMessageService.list(currentUserId(), beforeId, limit)
                .stream().map(MessageView::of).toList());
    }

    @GetMapping("/unread-count")
    @Operation(summary = "本人未读数（COUNT 实时）")
    public CommonResult<UnreadCountView> unreadCount() {
        return success(new UnreadCountView(platformMessageService.unreadCount(currentUserId())));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "标记单条已读（幂等重放返回现有行）；不存在或非本人一律 MESSAGE_NOT_FOUND")
    public CommonResult<MessageView> read(@PathVariable("id") long id) {
        return success(MessageView.of(platformMessageService.markRead(currentUserId(), id)));
    }

    @PostMapping("/read-all")
    @Operation(summary = "本人全部未读置已读，返回更新行数（无可读时 0，幂等成功）")
    public CommonResult<ReadAllView> readAll() {
        return success(new ReadAllView(platformMessageService.markAllRead(currentUserId())));
    }

    private static long currentUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            // 未认证请求已被 Spring Security 拦截（CommonResult.code=401）；防御兜底
            throw new IllegalStateException("登录态缺失");
        }
        return userId;
    }

    public record UnreadCountView(long count) {
    }

    public record ReadAllView(int updated) {
    }

}
