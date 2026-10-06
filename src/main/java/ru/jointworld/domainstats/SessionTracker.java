package ru.jointworld.domainstats;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** A connection is identified by object identity, never by a reusable player UUID */
public final class SessionTracker {
    private final Set<Object> connected = Collections.newSetFromMap(new IdentityHashMap<>());

    public synchronized boolean firstConnection(Object connection) {
        return connected.add(connection);
    }

    public synchronized void disconnected(Object connection) {
        connected.remove(connection);
    }
}
