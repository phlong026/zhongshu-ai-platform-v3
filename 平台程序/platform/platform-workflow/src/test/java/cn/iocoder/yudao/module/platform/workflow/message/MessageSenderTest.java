package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 Fake 单测（无 Spring）：发送方落库参数、幂等命中与并发撞键、白名单 fail-fast。
 * 真 PG 证据（唯一索引并发/CHECK 约束）由 script/platform-message-e2e.py 覆盖。
 */
class MessageSenderTest {

    private final FakeMessageStore store = new FakeMessageStore();
    private final MessageSender sender = new MessageSender(store);

    private static SendRequest request(String dedupeKey) {
        return new SendRequest(7L, 8L, "e2e", "BUSINESS", "customer", 99L,
                "客户跟进提醒", "林女士的回访即将到期", dedupeKey);
    }

    @Test
    void sendPersistsWithReadFalseAndFilledId() {
        MessageDO sent = sender.send(request("key-1"));

        assertThat(sent.getId()).isNotNull();
        assertThat(sent.getUserId()).isEqualTo(7L);
        assertThat(sent.getOrgId()).isEqualTo(8L);
        assertThat(sent.getSenderDomain()).isEqualTo("e2e");
        assertThat(sent.getType()).isEqualTo("BUSINESS");
        assertThat(sent.getRoute()).isEqualTo("customer");
        assertThat(sent.getObjectId()).isEqualTo(99L);
        assertThat(sent.getRead()).isFalse();
        assertThat(sent.getReadTime()).isNull();
        assertThat(sent.getCreateTime()).isNotNull();
    }

    @Test
    void sameDedupeKeyReturnsExistingRowWithoutUpdate() {
        MessageDO first = sender.send(request("key-1"));
        first.setTitle("被篡改的首次内容"); // 幂等重发不得以新内容覆盖首次落库

        MessageDO second = sender.send(request("key-1"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(store.rows).hasSize(1);
        assertThat(store.findById(first.getId()).getTitle()).isEqualTo("被篡改的首次内容");
    }

    @Test
    void concurrentInsertConflictRecoversExistingRow() {
        // 真实时序模拟：前置 findByDedupe 为空（对手尚未提交）→ INSERT 撞唯一索引（delegate 落对手行后抛冲突）
        // → catch 内重查命中既有行。前置查重即命中的路径由 sameDedupeKeyReturnsExistingRowWithoutUpdate 覆盖。
        FakeMessageStore racing = new FakeMessageStore();
        MessageDO winner = new MessageDO().setUserId(7L).setOrgId(8L).setSenderDomain("e2e")
                .setType("BUSINESS").setRoute("customer").setTitle("并发先到").setDescription("x")
                .setDedupeKey("key-1").setRead(false);
        winner.setId(500L);
        racing.insertDelegate = m -> {
            racing.rows.put(500L, winner);
            throw new DuplicateKeyException("uk_pf_message_dedupe");
        };

        MessageDO result = new MessageSender(racing).send(request("key-1"));

        assertThat(result.getId()).isEqualTo(500L);
        assertThat(result.getTitle()).isEqualTo("并发先到");
        assertThat(racing.rows).hasSize(1);
    }

    @Test
    void conflictWithMissingRowRethrowsOriginal() {
        FakeMessageStore broken = new FakeMessageStore() {
            @Override
            public void insert(MessageDO message) {
                throw new DuplicateKeyException("uk_pf_message_dedupe");
            }

            @Override
            public MessageDO findByDedupe(String senderDomain, String dedupeKey) {
                return null; // 冲突后重查竟无行：防御透传原始冲突
            }
        };
        MessageSender brokenSender = new MessageSender(broken);

        assertThatThrownBy(() -> brokenSender.send(request("key-1"))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void rejectsInvalidDictionaryAndFieldValues() {
        assertThatThrownBy(() -> sender.send(new SendRequest(0L, 8L, "e2e", "BUSINESS", "customer", null, "t", "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 0L, "e2e", "BUSINESS", "customer", null, "t", "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orgId");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "E2E", "BUSINESS", "customer", null, "t", "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("senderDomain");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "e2e", "PROMO", "customer", null, "t", "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "unknown-route", null, "t", "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("route");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "customer", null, " ", "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "customer", null, "t".repeat(101), "d", "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "customer", null, "t", "d".repeat(501), "k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("description");
        assertThatThrownBy(() -> sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "customer", null, "t", "d", "k".repeat(129))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedupeKey");
        assertThat(store.rows).isEmpty(); // 全部被拒，未落任何行
    }

    @Test
    void acceptsBoundaryFieldLengthsAndAllDictionaryValues() {
        sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "customer", null, "t".repeat(100), "d".repeat(500), "k".repeat(128)));
        sender.send(new SendRequest(7L, 8L, "a", "LEARNING", "course", null, "t", "d", "k1"));
        sender.send(new SendRequest(7L, 8L, "fr-fin", "SYSTEM", "personal-performance", null, "t", "d", "k2"));
        sender.send(new SendRequest(7L, 8L, "x9x", "BUSINESS", "privacy", null, "t", "d", "k3"));
        sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "design-dispatch", null, "t", "d", "k4"));
        sender.send(new SendRequest(7L, 8L, "e2e", "BUSINESS", "design-progress", null, "t", "d", "k5"));
        assertThat(store.rows).hasSize(6);
    }

}
