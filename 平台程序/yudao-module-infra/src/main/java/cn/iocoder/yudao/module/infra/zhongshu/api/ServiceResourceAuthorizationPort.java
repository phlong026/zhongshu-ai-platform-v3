package cn.iocoder.yudao.module.infra.zhongshu.api;

/** Service submission policy. Implementations validate and lock the persisted A organization/resource mapping. */
public interface ServiceResourceAuthorizationPort {
    void requireActive(PlatformCostPort.Subject subject);
}
