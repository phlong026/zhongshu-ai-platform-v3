package cn.iocoder.yudao.designprovider;

import java.util.concurrent.atomic.AtomicReference;

/** Allows one authenticated probe identity and idempotent retries of that exact request. */
final class OneShotSmokeGate {
    private final AtomicReference<String> acceptedSignature = new AtomicReference<>();

    boolean permit(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String accepted = acceptedSignature.get();
        if (signature.equals(accepted) || (accepted == null && acceptedSignature.compareAndSet(null, signature))) {
            return true;
        }
        // Another identical first request may have won the CAS concurrently.
        return signature.equals(acceptedSignature.get());
    }
}
