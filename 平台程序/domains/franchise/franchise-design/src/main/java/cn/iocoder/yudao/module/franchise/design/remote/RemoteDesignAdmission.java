package cn.iocoder.yudao.module.franchise.design.remote;

import java.util.HashMap;
import java.util.Map;

/** Per-process admission, before command persistence or billing; never queues servlet threads. */
public final class RemoteDesignAdmission {
    private final int globalLimit;
    private final int organizationLimit;
    private final Map<Long, Integer> organizations = new HashMap<>();
    private int active;

    public RemoteDesignAdmission(int globalLimit, int organizationLimit) {
        if (globalLimit < 1 || globalLimit > 256 || organizationLimit < 1 || organizationLimit > globalLimit) {
            throw new IllegalArgumentException("Invalid remote design concurrency limits");
        }
        this.globalLimit = globalLimit;
        this.organizationLimit = organizationLimit;
    }

    public synchronized Permit enter(long organization) {
        int count = organizations.getOrDefault(organization, 0);
        if (active >= globalLimit || count >= organizationLimit) throw new BusyException();
        active++;
        organizations.put(organization, count + 1);
        return new Permit(organization);
    }

    public final class Permit implements AutoCloseable {
        private final long organization;
        private boolean released;

        private Permit(long organization) { this.organization = organization; }

        @Override
        public void close() {
            synchronized (RemoteDesignAdmission.this) {
                if (released) return;
                released = true;
                active--;
                int remaining = organizations.get(organization) - 1;
                if (remaining == 0) organizations.remove(organization);
                else organizations.put(organization, remaining);
            }
        }
    }

    public static final class BusyException extends RuntimeException {
        private BusyException() { super("Remote design concurrency limit reached"); }
    }
}
