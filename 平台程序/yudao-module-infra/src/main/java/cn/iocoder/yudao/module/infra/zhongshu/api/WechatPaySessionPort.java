package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * 微信支付会话端口（虚拟支付 wx.requestVirtualPayment 签名专用）
 *
 * <p>背景：小程序虚拟支付的 C 端拉起参数 {@code signature} = HMAC-SHA256(signData, session_key)，
 * 必须由服务端用该用户<b>当前有效</b>的 session_key 计算。本端口用客户端在支付前临时换取的
 * 一次性 login code 做 code2session，取回 openid + session_key 供签名。
 *
 * <p>安全合同（与 {@link IdentitySessionPort} 的密钥纪律一致）：
 * <ul>
 *   <li>session_key 属敏感凭据：实现方<b>绝不写日志、绝不出现在异常消息</b>，仅经
 *       {@link PaySession} 返回给支付签名方，用后即弃，不落库不缓存；</li>
 *   <li>login code 是一次性凭证：实现方单次使用，失败不重试；</li>
 *   <li>调用方（虚拟支付适配器）必须校验 {@link PaySession#openid()} 与订单会话 openid 一致，
 *       不一致拒绝签名（防止用他人 code 生成跨账号签名）。</li>
 * </ul>
 *
 * 实现方：identity 模块（real 直连微信 code2session；stub 返回确定性假值供联调）；
 * 消费方：commerce 模块虚拟支付适配器。端口放 infra 共享包以避免业务模块互相依赖。
 */
public interface WechatPaySessionPort {

    /** 用一次性 login code 换取签名会话；code 无效/微信侧失败/协议异常直接抛业务异常（不重试） */
    PaySession exchangeForPay(String appid, String loginCode);

    /** code2session 结果：openid + session_key（敏感，用后即弃） */
    record PaySession(String openid, String sessionKey) {
    }

}
