package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import cn.iocoder.yudao.module.platform.workflow.message.MessageSender;
import cn.iocoder.yudao.module.platform.workflow.message.MessageStore;
import cn.iocoder.yudao.module.platform.workflow.message.MessageView;
import cn.iocoder.yudao.module.platform.workflow.message.SendRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 消息种子接口：e2e 经它注入消息——走完整 {@link MessageSender} 校验+幂等代码路径（而非 psql 直插），
 * 让发送方逻辑获得真 PG 证据（11 文档 §四.5）。只在 local profile 下注册（照抄 SmsDevController 守卫）。
 */
@Tag(name = "开发 - 员工消息种子（e2e 专用）")
@RestController
@RequestMapping("/admin-api/platform/dev-setup/message")
@Profile("local")
@PermitAll
public class MessageDevController {

    @Resource
    private MessageSender messageSender;
    @Resource
    private MessageStore messageStore;

    public record SeedReq(Long userId, Long orgId, String senderDomain, String type, String route,
                          Long objectId, String title, String description, String dedupeKey) {
    }

    public record SeedResult(MessageView message, boolean deduplicated) {
    }

    @PostMapping("/seed")
    @Operation(summary = "以指定身份与幂等键发送一条消息（同键重发 deduplicated=true 且 id 不变）")
    public CommonResult<SeedResult> seed(@RequestBody SeedReq req) {
        boolean deduplicated = messageStore.findByDedupe(req.senderDomain(), req.dedupeKey()) != null;
        MessageDO message = messageSender.send(new SendRequest(req.userId(), req.orgId(), req.senderDomain(),
                req.type(), req.route(), req.objectId(), req.title(), req.description(), req.dedupeKey()));
        return success(new SeedResult(MessageView.of(message), deduplicated));
    }

}
