package com.battle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Khôi phục địa hình khi không thể tạo/xóa world (Folia gốc).
 * Trong trận ghi lại BlockState GỐC của mọi block bị thay đổi (nhật ký),
 * sau trận ghi lại về đúng chunk bằng RegionScheduler.
 */
public final class MapRestorer {
    private record Pos(int x, int y, int z) {}

    private static final BlockFace[] HORIZONTAL =
            {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final BattlePlugin plugin;
    private final BattleConfig cfg;
    private final Map<Pos, BlockState> journal = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile World world;
    private volatile boolean recording;

    public MapRestorer(BattlePlugin plugin, BattleConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public boolean isRecording() { return recording; }
    public boolean isBusy() { return pending.get() > 0; }

    public void start(World w) {
        if (!cfg.restoreEnabled) return;
        journal.clear();
        world = w;
        recording = true;
    }

    private boolean inArena(int x, int z) {
        double half = cfg.startSize / 2 + 16;
        return Math.abs(x - cfg.centerX) <= half && Math.abs(z - cfg.centerZ) <= half;
    }

    // ------------------------------------------------------------ ghi nhật ký

    public void record(Block b) {
        if (!recording) return;
        World w = world;
        if (w == null || !b.getWorld().equals(w)) return;
        Pos p = new Pos(b.getX(), b.getY(), b.getZ());
        if (!inArena(p.x(), p.z()) || journal.containsKey(p)) return;
        try {
            journal.putIfAbsent(p, b.getState());
        } catch (Throwable ignored) { }
    }

    public void record(BlockState s) {
        if (!recording) return;
        World w = world;
        if (w == null || !s.getWorld().equals(w)) return;
        Pos p = new Pos(s.getX(), s.getY(), s.getZ());
        if (!inArena(p.x(), p.z())) return;
        journal.putIfAbsent(p, s);
    }

    /** Ghi block, cùng block trên/dưới và 4 block bên cạnh (cùng chunk) để bắt cửa, đuốc, cây... */
    public void recordBreak(Block b) {
        record(b);
        record(b.getRelative(BlockFace.UP));
        record(b.getRelative(BlockFace.DOWN));
        for (BlockFace f : HORIZONTAL) {
            Block n = b.getRelative(f);
            // chỉ đọc block cùng chunk để tránh chạm vùng của region thread khác (Folia)
            if ((n.getX() >> 4) == (b.getX() >> 4) && (n.getZ() >> 4) == (b.getZ() >> 4)) record(n);
        }
    }

    public void recordWithAbove(Block b) {
        record(b);
        record(b.getRelative(BlockFace.UP));
    }

    // ------------------------------------------------------------ khôi phục

    public void finish(World w) {
        if (!recording || !w.equals(world)) return;
        recording = false;

        Map<Long, List<BlockState>> byChunk = new HashMap<>();
        for (BlockState s : journal.values()) {
            byChunk.computeIfAbsent(key(s.getX() >> 4, s.getZ() >> 4), k -> new ArrayList<>()).add(s);
        }
        int blocks = journal.size();
        journal.clear();

        for (Map.Entry<Long, List<BlockState>> e : byChunk.entrySet()) {
            final int cx = (int) (e.getKey() >> 32);
            final int cz = e.getKey().intValue();
            final List<BlockState> list = e.getValue();
            pending.incrementAndGet();
            Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                try {
                    for (BlockState s : list) {
                        try { s.update(true, false); } catch (Throwable ignored) { }
                    }
                } finally {
                    pending.decrementAndGet();
                }
            });
        }
        plugin.getLogger().info("Khôi phục " + blocks + " block trong " + byChunk.size() + " chunk.");

        if (cfg.clearEntities) clearEntities(w);
    }

    private void clearEntities(World w) {
        int r = (int) Math.ceil(cfg.startSize / 2 + 32);
        int minCx = ((int) cfg.centerX - r) >> 4, maxCx = ((int) cfg.centerX + r) >> 4;
        int minCz = ((int) cfg.centerZ - r) >> 4, maxCz = ((int) cfg.centerZ + r) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                final int fx = cx, fz = cz;
                if (!w.isChunkLoaded(fx, fz)) continue;
                pending.incrementAndGet();
                Bukkit.getRegionScheduler().execute(plugin, w, fx, fz, () -> {
                    try {
                        if (!w.isChunkLoaded(fx, fz)) return;
                        for (Entity e : w.getChunkAt(fx, fz).getEntities()) {
                            if (e instanceof Item || e instanceof ExperienceOrb || e instanceof Projectile
                                    || e instanceof FallingBlock || e instanceof TNTPrimed
                                    || e instanceof AreaEffectCloud) {
                                e.remove();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        pending.decrementAndGet();
                    }
                });
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
