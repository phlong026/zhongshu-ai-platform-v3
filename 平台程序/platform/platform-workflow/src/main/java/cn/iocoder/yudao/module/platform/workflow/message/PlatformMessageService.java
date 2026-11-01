package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.MESSAGE_NOT_FOUND;

/**
 * 消息读侧：列表（游标分页）/未读数/已读，均只按登录人过滤（11 文档 D-基2-b）。
 * 已读幂等：重复已读返回现有行且 read_time 保持首次值。
 */
@Service
public class PlatformMessageService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final MessageStore store;

    public PlatformMessageService(MessageStore store) {
        this.store = store;
    }

    /** beforeId 缺省=从最新开始；limit 缺省 20、clamp [1,100]（读接口无副作用，越界静默收敛，D-基2-h） */
    public List<MessageDO> list(long userId, Long beforeId, Integer limit) {
        return store.findPage(userId, beforeId, clamp(limit));
    }

    static int clamp(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }

    public long unreadCount(long userId) {
        return store.countUnread(userId);
    }

    /**
     * 标记单条已读。不存在或非本人一律 MESSAGE_NOT_FOUND（同码，不泄露他人消息 id 存在性）；
     * 已读重放幂等返回现有行（UPDATE 0 行走此分支，read_time 不被覆盖）。
     */
    public MessageDO markRead(long userId, long messageId) {
        OffsetDateTime now = OffsetDateTime.now();
        if (store.markRead(userId, messageId, now) > 0) {
            return store.findById(messageId);
        }
        MessageDO existing = store.findById(messageId);
        if (existing == null || existing.getUserId() != userId) {
            throw exception(MESSAGE_NOT_FOUND);
        }
        return existing;
    }

    /** 本人全部未读置已读，返回更新行数（无可读时 0，幂等成功） */
    public int markAllRead(long userId) {
        return store.markAllRead(userId, OffsetDateTime.now());
    }

}
