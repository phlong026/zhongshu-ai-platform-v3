package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 Fake 存储：模拟唯一索引冲突与「仅未读可更新」语义，供 Sender/Service 纯单测。
 */
class FakeMessageStore implements MessageStore {

    final Map<Long, MessageDO> rows = new ConcurrentHashMap<>();
    long sequence = 1000;
    /** 并发撞键模拟：置 true 后 insert 已存在幂等键抛 DuplicateKeyException（真实唯一索引行为） */
    boolean throwDuplicateOnConflict = true;
    // 参数记录（断言透传用）
    Long lastFindPageUserId;
    Long lastFindPageBeforeId;
    Integer lastFindPageLimit;
    OffsetDateTime lastMarkReadTime;
    OffsetDateTime lastMarkAllReadTime;

    @Override
    public MessageDO findByDedupe(String senderDomain, String dedupeKey) {
        return rows.values().stream()
                .filter(m -> m.getSenderDomain().equals(senderDomain) && m.getDedupeKey().equals(dedupeKey))
                .findFirst().orElse(null);
    }

    @Override
    public void insert(MessageDO message) {
        if (findByDedupe(message.getSenderDomain(), message.getDedupeKey()) != null) {
            if (throwDuplicateOnConflict) {
                throw new DuplicateKeyException("uk_pf_message_dedupe");
            }
            return; // 模拟 ON CONFLICT DO NOTHING 形态时由用例自定
        }
        message.setId(++sequence);
        rows.put(message.getId(), message);
    }

    @Override
    public MessageDO findById(long id) {
        return rows.get(id);
    }

    @Override
    public List<MessageDO> findPage(long userId, Long beforeId, int limit) {
        lastFindPageUserId = userId;
        lastFindPageBeforeId = beforeId;
        lastFindPageLimit = limit;
        return rows.values().stream()
                .filter(m -> m.getUserId() == userId)
                .filter(m -> beforeId == null || m.getId() < beforeId)
                .sorted(Comparator.comparingLong(MessageDO::getId).reversed())
                .limit(limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public long countUnread(long userId) {
        return rows.values().stream().filter(m -> m.getUserId() == userId && !Boolean.TRUE.equals(m.getRead())).count();
    }

    @Override
    public int markRead(long userId, long messageId, OffsetDateTime readTime) {
        lastMarkReadTime = readTime;
        MessageDO message = rows.get(messageId);
        if (message == null || message.getUserId() != userId || Boolean.TRUE.equals(message.getRead())) {
            return 0;
        }
        message.setRead(true);
        message.setReadTime(readTime);
        return 1;
    }

    @Override
    public int markAllRead(long userId, OffsetDateTime readTime) {
        lastMarkAllReadTime = readTime;
        int[] updated = {0};
        rows.values().stream().filter(m -> m.getUserId() == userId && !Boolean.TRUE.equals(m.getRead()))
                .forEach(m -> {
                    m.setRead(true);
                    m.setReadTime(readTime);
                    updated[0]++;
                });
        return updated[0];
    }

}
