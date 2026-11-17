package com.battle;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Solo = đội 1 người. Mọi chế độ dùng chung lớp này. */
public final class Team {
    private final int id;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    private final AtomicInteger kills = new AtomicInteger();

    public Team(int id) { this.id = id; }

    public int id() { return id; }
    public Set<UUID> members() { return members; }

    public void add(UUID u) { members.add(u); alive.add(u); }
    public void markDead(UUID u) { alive.remove(u); }
    public boolean isAlive(UUID u) { return alive.contains(u); }
    public int aliveCount() { return alive.size(); }
    public int kills() { return kills.get(); }
    public void addKill() { kills.incrementAndGet(); }
}
