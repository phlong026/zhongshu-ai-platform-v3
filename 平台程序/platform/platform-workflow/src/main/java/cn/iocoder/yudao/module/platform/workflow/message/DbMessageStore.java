package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import cn.iocoder.yudao.module.platform.workflow.dal.MessageMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.annotation.Resource;

@Repository
public class DbMessageStore implements MessageStore {

    @Resource
    private MessageMapper messageMapper;

    @Override
    public MessageDO findByDedupe(String senderDomain, String dedupeKey) {
        return messageMapper.selectByDedupe(senderDomain, dedupeKey);
    }

    @Override
    public void insert(MessageDO message) {
        messageMapper.insert(message);
    }

    @Override
    public MessageDO findById(long id) {
        return messageMapper.selectById(id);
    }

    @Override
    public List<MessageDO> findPage(long userId, Long beforeId, int limit) {
        return messageMapper.selectPage(userId, beforeId, limit);
    }

    @Override
    public long countUnread(long userId) {
        return messageMapper.countUnread(userId);
    }

    @Override
    public int markRead(long userId, long messageId, OffsetDateTime readTime) {
        return messageMapper.updateIfUnread(userId, messageId, readTime);
    }

    @Override
    public int markAllRead(long userId, OffsetDateTime readTime) {
        return messageMapper.markAllRead(userId, readTime);
    }

}
