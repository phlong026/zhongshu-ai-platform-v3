package cn.iocoder.yudao.module.design.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 站内消息（架构 §6.10）：我的/消息中心入口；按 Outbox 事件和收件人去重。
 */
@Service
public class MessageService implements cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventSink {

    private final JdbcTemplate jdbcTemplate;
    private final MessageFactResolver facts;

    public MessageService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.facts = new MessageFactResolver(dataSource);
    }

    @Override
    public boolean supports(String eventType) {
        // 任务完成/取消、审核结论、到账、发布——站内信只收白名单事件
        // 审查 H7：补齐合同 §6.10 最小事件集（任务失败/退款/支付到账异常/授权失效）
        return List.of("AI_JOB_SETTLED", "AI_JOB_CANCELLED", "SUBMISSION_REVIEWED",
                "ORDER_CREDITED", "SUBMISSION_PUBLISHED",
                "AI_JOB_FAILED", "ORDER_REFUND_REVERSED", "PAYMENT_ALERT", "RIGHTS_EXPIRED",
                "BUDGET_QUOTE_PUBLISHED", "BUDGET_QUOTE_WITHDRAWN")
                .contains(eventType);
    }

    /** Outbox Sink：at-least-once 投递；payload 必须携带 userId（或 ownerUserId） */
    @Override
    public void deliver(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event) {
        var target = target(event);
        var fact = facts.fact(event, target.userId());
        var copy = fact.version() == null ? messageCopy(event) : factCopy(event, fact);
        int inserted = jdbcTemplate.update(
                "INSERT INTO user_message (id, user_id, message_type, title, content, biz_type, biz_id, source_event_id, "
                        + "fact_version,occurred_at,action_type,target_snapshot,fact_snapshot) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                IdWorker.getId(), target.userId(), event.getEventType(), copy.title(),
                copy.content(), target.bizType(), target.bizId(), event.getEventId(),fact.version(),fact.occurredAt(),fact.action(),
                MessageFactResolver.json(fact.target()),MessageFactResolver.json(fact.values()));
        if(inserted==0 && fact.version()!=null) {
            var rows=jdbcTemplate.queryForList("SELECT fact_snapshot::text,target_snapshot::text FROM user_message "
                    + "WHERE tenant_id=0 AND user_id=? AND message_type=? AND biz_type=? AND biz_id=? AND fact_version=?",
                    target.userId(),event.getEventType(),target.bizType(),target.bizId(),fact.version());
            if(rows.size()!=1 || !MessageFactResolver.json(String.valueOf(rows.get(0).get("fact_snapshot"))).equals(MessageFactResolver.json(MessageFactResolver.json(fact.values())))
                    || !MessageFactResolver.json(String.valueOf(rows.get(0).get("target_snapshot"))).equals(MessageFactResolver.json(MessageFactResolver.json(fact.target()))))
                throw new IllegalStateException("MESSAGE_FACT_CONFLICT");
        }
    }

    private MessageCopy factCopy(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event, MessageFactResolver.Fact fact) {
        var values=fact.values();
        return switch(event.getEventType()) {
            case "AI_JOB_SETTLED" -> {
                String status=String.valueOf(values.get("status"));
                String headline=switch(status) {case "SUCCEEDED"->"设计方案生成完成";case "PARTIALLY_SUCCEEDED"->"设计方案部分生成完成";case "CANCELLED"->"设计任务已取消";default->"设计方案生成失败";};
                long refund=((Number)values.get("refundPoints")).longValue();
                yield new MessageCopy(headline,refund>0?"本次生成结算已退回"+refund+"设计点；提示词费用单独结算，请查看点数流水。":"请查看任务结果与点数流水；提示词费用单独结算。");
            }
            case "SUBMISSION_REVIEWED" -> switch(String.valueOf(values.get("decision"))) {
                case "CHANGES_REQUESTED" -> new MessageCopy("投稿需要修改","第"+values.get("reviewRound")+"轮审核已退修，请查看本轮意见后修改重提。");
                case "APPROVE" -> new MessageCopy("投稿审核已通过","审核通过后仍需独立发布；当前消息仅说明本轮审核结果。");
                default -> new MessageCopy("投稿审核未通过","请查看本轮审核结论。");
            };
            case "ORDER_CREDITED" -> new MessageCopy("充值设计点已到账","基础"+values.get("basePoints")+"设计点，赠送"+values.get("bonusPoints")+"设计点已记账，请查看原订单。");
            case "BUDGET_QUOTE_PUBLISHED" -> new MessageCopy("正式报价已发布","请查看本次发布的报价版本；实际金额以该版本公开报价为准。");
            case "BUDGET_QUOTE_WITHDRAWN" -> new MessageCopy("正式报价已撤回","该报价版本已撤回，请勿继续使用其金额作为当前报价。");
            default -> new MessageCopy(title(event.getEventType()),"点击查看对应业务事实与当前可用状态");
        };
    }

    private record Target(long userId, String bizType, String bizId) { }

    private Target target(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event) {
        if ("ORDER_CREDITED".equals(event.getEventType()) && MessageFactResolver.id(event.getBizId()) != null) {
            var rows=jdbcTemplate.query("SELECT user_id,id FROM recharge_order WHERE tenant_id=0 AND id=? AND deleted=FALSE",
                    (rs,i)->new Target(rs.getLong("user_id"),"recharge_order",rs.getString("id")),Long.parseLong(event.getBizId()));
            if(!rows.isEmpty())return rows.get(0);
        }
        if ("SUBMISSION_PUBLISHED".equals(event.getEventType())) {
            try {
                var rows = jdbcTemplate.query("SELECT user_id,id FROM case_submission WHERE tenant_id=0 AND id=? AND deleted=FALSE",
                        (rs,i) -> new Target(rs.getLong("user_id"), "case_submission", rs.getString("id")), Long.parseLong(event.getBizId()));
                if (!rows.isEmpty()) return rows.get(0);
            } catch (NumberFormatException ignored) { }
            throw new IllegalStateException("发布事件无法解析投稿人");
        }
        // Legacy refund events omitted userId. Resolve recipient and navigation from authoritative rows.
        if ("ORDER_REFUND_REVERSED".equals(event.getEventType())) {
            try {
                long refundId = Long.parseLong(event.getBizId());
                var rows = jdbcTemplate.query("SELECT o.user_id, o.id FROM refund_order r JOIN recharge_order o ON o.id=r.order_id "
                        + "WHERE r.tenant_id=0 AND o.tenant_id=0 AND r.id=? AND r.deleted=FALSE AND o.deleted=FALSE", (rs,i) -> new Target(rs.getLong("user_id"), "recharge_order", rs.getString("id")), refundId);
                if (!rows.isEmpty()) return rows.get(0);
            } catch (NumberFormatException ignored) { }
            throw new IllegalStateException("退款事件无法解析原订单");
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getPayload());
            var value = node.hasNonNull("userId") ? node.get("userId") : node.get("ownerUserId");
            if (value != null && value.asText().matches("[1-9][0-9]{0,18}"))
                return new Target(Long.parseLong(value.asText()), event.getBizType(), event.getBizId());
        } catch (Exception ignored) {
            // 落入下方统一异常
        }
        throw new IllegalStateException("事件 payload 缺少 userId: " + event.getEventType());
    }

    private record MessageCopy(String title, String content) { }

    private MessageCopy messageCopy(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event) {
        // Historic deliveries without a provable committed fact must not claim money/publication completed.
        if ("ORDER_CREDITED".equals(event.getEventType())) return new MessageCopy("充值记录状态已更新","请查看原订单和点数流水确认到账状态。");
        if ("ORDER_REFUND_REVERSED".equals(event.getEventType())) return new MessageCopy("退款记录状态已更新","请查看原订单确认退款与设计点冲正状态。");
        if ("SUBMISSION_PUBLISHED".equals(event.getEventType())) return new MessageCopy("投稿发布状态已更新","请查看投稿记录确认当前发布状态。");
        if (!"AI_JOB_SETTLED".equals(event.getEventType()))
            return new MessageCopy(title(event.getEventType()), "点击查看最新业务状态");
        try {
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getPayload());
            String status = payload.path("status").asText("");
            String headline = switch (status) {
                case "SUCCEEDED" -> "设计方案生成完成";
                case "PARTIALLY_SUCCEEDED" -> "设计方案部分生成完成";
                case "FAILED" -> "设计方案生成失败";
                case "CANCELLED" -> "设计任务已取消";
                default -> "设计任务处理结果已更新";
            };
            String content = "请查看任务结果与点数流水；提示词费用单独结算。";
            // 仅消费结算事务在退点记账成功后写入 outbox 的数值，不从失败/取消推断退款。
            var refund = payload.path("refund");
            if (List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED").contains(status)
                    && refund.isIntegralNumber() && refund.canConvertToLong() && refund.longValue() > 0) {
                content = "本次生成结算已退回" + refund.longValue() + "设计点；提示词费用单独结算，请查看点数流水。";
            }
            return new MessageCopy(headline, content);
        } catch (Exception ignored) {
            return new MessageCopy("设计任务处理结果已更新", "请查看任务结果与点数流水。");
        }
    }

    private String title(String type) {
        return switch (type) {
            case "AI_JOB_SETTLED" -> "设计任务处理结果已更新";
            case "AI_JOB_CANCELLED" -> "设计任务已取消";
            case "AI_JOB_FAILED" -> "设计任务未完成，请查看处理结果";
            case "SUBMISSION_REVIEWED" -> "投稿审核结果已更新";
            case "SUBMISSION_PUBLISHED" -> "您的作品已发布";
            case "ORDER_CREDITED" -> "充值设计点已到账";
            case "ORDER_REFUND_REVERSED" -> "退款已完成";
            case "PAYMENT_ALERT" -> "充值订单需要关注";
            case "RIGHTS_EXPIRED" -> "素材授权状态已变化";
            default -> "业务状态已更新";
        };
    }

    public long unreadCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_message m WHERE m.tenant_id=0 AND m.user_id = ? AND m.deleted = FALSE "
                        + "AND NOT EXISTS (SELECT 1 FROM message_receipt r WHERE r.user_id = m.user_id "
                        + "AND r.message_id = m.id AND r.deleted = FALSE)",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    /** 已读回执幂等 */
    public boolean markRead(long userId, long messageId) {
        Integer owned = jdbcTemplate.queryForObject("SELECT count(*) FROM user_message WHERE tenant_id=0 AND id=? AND user_id=? AND deleted=FALSE",
                Integer.class, messageId, userId);
        if (owned == null || owned == 0) throw new org.springframework.security.access.AccessDeniedException("消息不存在或无权访问");
        int inserted = jdbcTemplate.update(
                "INSERT INTO message_receipt (id, user_id, message_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, message_id) DO UPDATE SET deleted=FALSE,read_at=now(),update_time=now() WHERE message_receipt.deleted=TRUE AND message_receipt.tenant_id=0",
                IdWorker.getId(), userId, messageId);
        return inserted == 1;
    }

    public List<Map<String, Object>> list(long userId, int limit) {
        return listPage(userId, null, limit).list();
    }

    public record MessagePage(List<Map<String, Object>> list, String nextCursor) { }

    public MessagePage listPage(long userId, String cursor, int limit) {
        return listPage(userId,cursor,limit,"ALL");
    }

    /** Keyset pagination remains by immutable message ID; action status is derived independently of receipts. */
    public MessagePage listPage(long userId, String cursor, int limit, String filter) {
        String category=filter==null?"ALL":filter;
        if(!java.util.Set.of("ALL","NOTICE","TODO","DONE").contains(category)) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400,"消息分类无效");
        int size = Math.max(1, Math.min(limit, 100));
        long before = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            try { before = Long.parseLong(cursor); if (before <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "消息游标无效"); }
        }
        var matches=new java.util.ArrayList<Map<String,Object>>();
        while(matches.size()<=size) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT m.id, m.message_type, m.title, m.content, m.biz_type, m.biz_id, m.create_time, m.occurred_at, "
                        + "m.fact_version,m.action_type,m.target_snapshot::text, "
                        + "(r.id IS NOT NULL) AS read FROM user_message m "
                        + "LEFT JOIN message_receipt r ON r.message_id = m.id AND r.user_id = m.user_id AND r.deleted=FALSE "
                        + "WHERE m.tenant_id=0 AND m.user_id = ? AND m.deleted = FALSE AND m.id < ? "
                        + ("NOTICE".equals(category)?"AND m.action_type='NONE' ":java.util.Set.of("TODO","DONE").contains(category)?"AND m.action_type<>'NONE' ":"")
                        + "ORDER BY m.id DESC LIMIT 100",userId,before);
        for(var row:rows) {
            before=((Number)row.get("id")).longValue();
            String state=facts.actionState(userId,String.valueOf(row.get("action_type")),MessageFactResolver.json(String.valueOf(row.get("target_snapshot"))));
            row.put("action_state",state);
            if("TODO".equals(category)&&!"PENDING".equals(state) || "DONE".equals(category)&&!java.util.Set.of("COMPLETED","UNAVAILABLE").contains(state)) continue;
            matches.add(row);
            if(matches.size()>size)break;
        }
        if(rows.size()<100)break;
        }
        boolean more = matches.size() > size;
        var page = more ? matches.subList(0, size) : matches;
        return new MessagePage(List.copyOf(page), more ? String.valueOf(page.get(page.size() - 1).get("id")) : null);
    }

    public record MessageCounts(long unread,long pending) { }
    public MessageCounts counts(long userId) {
        long pending=0;String cursor=null;
        do { var page=listPage(userId,cursor,100,"TODO");pending+=page.list().size();cursor=page.nextCursor(); } while(cursor!=null);
        return new MessageCounts(unreadCount(userId),pending);
    }

    public Map<String,Object> target(long userId,long messageId) {
        var rows=jdbcTemplate.queryForList("SELECT biz_type,biz_id,target_snapshot::text FROM user_message WHERE tenant_id=0 AND id=? AND user_id=? AND deleted=FALSE",messageId,userId);
        if(rows.isEmpty())throw new org.springframework.security.access.AccessDeniedException("消息不存在或无权访问");
        var row=rows.get(0);var saved=new java.util.LinkedHashMap<>(MessageFactResolver.json(String.valueOf(row.get("target_snapshot"))));
        if(saved.isEmpty()) {
            String id=MessageFactResolver.id(row.get("biz_id"));
            if(id==null)throw new org.springframework.security.access.AccessDeniedException("历史消息未记录可定位目标");
            switch(String.valueOf(row.get("biz_type"))) {
                case "ai_job" -> {saved.put("type","JOB");saved.put("jobId",id);}
                case "case_submission" -> {saved.put("type","SUBMISSION");saved.put("submissionId",id);}
                case "recharge_order" -> {saved.put("type","ORDER");saved.put("orderId",id);}
                case "design_project" -> {saved.put("type","PROJECT");saved.put("projectId",id);}
                default -> throw new org.springframework.security.access.AccessDeniedException("历史消息未记录可定位目标");
            }
        }
        return facts.resolveTarget(userId,saved);
    }

}
