package cn.iocoder.yudao.module.franchise.design.publiccase;

import java.util.HashMap;
import java.util.Map;

/** Process-local concurrency admission keyed by the complete B tenant and organization scope. */
public final class PublicCaseAdmission {

    private final int globalLimit;
    private final int scopeLimit;
    private final Map<Scope, Integer> scopes = new HashMap<>();
    private int active;

    public PublicCaseAdmission(int globalLimit, int scopeLimit) {
        if (globalLimit < 1 || globalLimit > 256 || scopeLimit < 1 || scopeLimit > globalLimit) {
            throw new IllegalArgumentException("Invalid public case concurrency limits");
        }
        this.globalLimit = globalLimit;
        this.scopeLimit = scopeLimit;
    }

    public synchronized Permit enter(long tenantId, long orgId) {
        if (tenantId <= 0 || orgId <= 0) throw new IllegalArgumentException("Invalid public case scope");
        Scope scope = new Scope(tenantId, orgId);
        int scoped = scopes.getOrDefault(scope, 0);
        if (active >= globalLimit || scoped >= scopeLimit) throw new BusyException();
        active++;
        scopes.put(scope, scoped + 1);
        return new Permit(scope);
    }

    public final class Permit implements AutoCloseable {
        private final Scope scope;
        private boolean released;

        private Permit(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void close() {
            synchronized (PublicCaseAdmission.this) {
                if (released) return;
                released = true;
                active--;
                int remaining = scopes.get(scope) - 1;
                if (remaining == 0) scopes.remove(scope);
                else scopes.put(scope, remaining);
            }
        }
    }

    private record Scope(long tenantId, long orgId) { }

    public static final class BusyException extends RuntimeException {
        private BusyException() {
            super("Public case concurrency limit reached");
        }
    }
}
