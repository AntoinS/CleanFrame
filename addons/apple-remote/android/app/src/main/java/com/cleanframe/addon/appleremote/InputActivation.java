package com.cleanframe.addon.appleremote;

import java.util.concurrent.atomic.AtomicLong;

/** Even generations are stopped; odd generations are active. Old work never revives on re-enable. */
final class InputActivation {
    private final AtomicLong generation = new AtomicLong();
    long ticket() { return generation.get(); }
    boolean isEnabled() { return (generation.get() & 1) != 0; }
    boolean enable(long stoppedTicket) {
        return (stoppedTicket & 1) == 0 && generation.compareAndSet(stoppedTicket, stoppedTicket + 1);
    }
    void disable() { generation.updateAndGet(value -> (value + 2) & ~1L); }
    boolean allows(long ticket) { return (ticket & 1) != 0 && generation.get() == ticket; }
}
