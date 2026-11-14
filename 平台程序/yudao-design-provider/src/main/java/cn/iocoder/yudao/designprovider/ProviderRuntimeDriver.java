package cn.iocoder.yudao.designprovider;

/** Implemented only by the real job driver that advances, validates and settles runtime jobs. */
public interface ProviderRuntimeDriver {
    void verifyReady();
}
