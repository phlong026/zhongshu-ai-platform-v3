package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.platform.workflow.message.MessageConstants.*;

/**
 * 消息发送 Port：业务域注册一条员工消息的进程内入口（11 文档 D-基2-d/e）。
 *
 * <p>幂等：同 (senderDomain, dedupeKey) 重发返回既有行（首次落库为真，不更新内容）；
 * 并发撞唯一索引捕获 {@link DuplicateKeyException} 后重查返回既有行。
 *
 * <p><b>事务约束：本方法不得加 @Transactional，调用方必须在事务外调用。</b>
 * 若处于调用方事务内，并发撞键后 PG 事务已 abort，catch 内重查必失败，
 * 幂等承诺退化为异常透传（先例：ApprovalServiceImpl.submit 同场景只能报冲突）。
 * 业务域在自身事务内产生的事件，事务提交后补发——丢消息可容忍、重复由幂等键兜底。
 */
@Service
@Slf4j
public class MessageSender {

    private static final Pattern SENDER_DOMAIN = Pattern.compile("^" + SENDER_DOMAIN_PATTERN + "$");

    private final MessageStore store;

    public MessageSender(MessageStore store) {
        this.store = store;
    }

    public MessageDO send(SendRequest request) {
        validate(request);
        MessageDO existing = store.findByDedupe(request.senderDomain(), request.dedupeKey());
        if (existing != null) {
            return existing;
        }
        MessageDO message = new MessageDO()
                .setUserId(request.userId()).setOrgId(request.orgId())
                .setSenderDomain(request.senderDomain()).setType(request.type()).setRoute(request.route())
                .setObjectId(request.objectId()).setTitle(request.title()).setDescription(request.description())
                .setDedupeKey(request.dedupeKey())
                .setRead(false).setCreateTime(OffsetDateTime.now());
        try {
            store.insert(message);
        } catch (DuplicateKeyException e) { // 并发重发同一业务事件，被 (sender_domain, dedupe_key) 唯一索引拦下
            log.warn("[send][{}/{}] 并发重发命中幂等键", request.senderDomain(), request.dedupeKey());
            MessageDO winner = store.findByDedupe(request.senderDomain(), request.dedupeKey());
            if (winner == null) {
                throw e; // 理论不可达：唯一索引刚拦下必有行；防御透传原始冲突
            }
            return winner;
        }
        return message;
    }

    /** 进程内受信调用方：参数错=编程错误，fail-fast，不设业务码（11 文档 §五.1） */
    private static void validate(SendRequest request) {
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("消息收件人 userId 必须为正数");
        }
        if (request.orgId() <= 0) {
            throw new IllegalArgumentException("消息归属 orgId 必须为正数");
        }
        if (request.senderDomain() == null || !SENDER_DOMAIN.matcher(request.senderDomain()).matches()) {
            throw new IllegalArgumentException("senderDomain 须匹配 " + SENDER_DOMAIN_PATTERN + "，且已登记");
        }
        if (request.type() == null || !TYPES.contains(request.type())) {
            throw new IllegalArgumentException("消息 type 须为白名单值 " + TYPES);
        }
        if (request.route() == null || !ROUTES.contains(request.route())) {
            throw new IllegalArgumentException("消息 route 须为白名单值 " + ROUTES);
        }
        requireText(request.title(), TITLE_MAX_LENGTH, "title");
        requireText(request.description(), DESCRIPTION_MAX_LENGTH, "description");
        requireText(request.dedupeKey(), DEDUPE_KEY_MAX_LENGTH, "dedupeKey");
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("消息 " + field + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("消息 " + field + " 长度不得超过 " + maxLength);
        }
    }

}
