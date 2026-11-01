package cn.iocoder.yudao.module.platform.workflow.message;

/**
 * 发送请求：业务域注册一条员工消息。
 * 参数合法性由 {@link MessageSender#send} fail-fast 校验（进程内受信调用方，参数错=编程错误）。
 */
public record SendRequest(long userId, long orgId, String senderDomain, String type, String route,
                          Long objectId, String title, String description, String dedupeKey) {
}
