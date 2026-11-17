package com.battle;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chọn điểm spawn ngẫu nhiên, cách nhau >= min-spawn-distance (mặc định 32 block = 2 chunk).
 * Chunk tải async, kiểm tra block chạy trên RegionScheduler của chunk (yêu cầu của Folia).
 */
public final class SpawnManager {
    private static final Set<Material> UNSAFE = Set.of(
            Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.LAVA, Material.WATER, Material.POWDER_SNOW);

    private final BattlePlugin plugin;
    private final BattleConfig cfg;

    public SpawnManager(BattlePlugin plugin, BattleConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public CompletableFuture<List<Location>> findSpots(World world, int count) {
        CompletableFuture<List<Location>> done = new CompletableFuture<>();
        next(world, count, new ArrayList<>(), 0, done);
        return done;
    }

    private void next(World world, int count, List<Location> spots, int tries,
                      CompletableFuture<List<Location>> done) {
        if (spots.size() >= count) { done.complete(spots); return; }

        ThreadLocalRandom r = ThreadLocalRandom.current();
        double radius = Math.max(1, cfg.startSize / 2 - cfg.spawnEdgeMargin);
        int t = tries, x = 0, z = 0;
        boolean ok = false;
        while (t < cfg.spawnAttempts && !ok) {
            t++;
            double ang = r.nextDouble(Math.PI * 2);
            double dist = Math.sqrt(r.nextDouble()) * radius;
            x = (int) Math.floor(cfg.centerX + Math.cos(ang) * dist);
            z = (int) Math.floor(cfg.centerZ + Math.sin(ang) * dist);
            ok = farEnough(spots, x, z);
        }
        if (!ok) {
            done.completeExceptionally(new IllegalStateException(
                    "Không đủ chỗ spawn cho " + count + " điểm (min distance " + cfg.minSpawnDistance + ")"));
            return;
        }
        final int fx = x, fz = z, ft = t;
        checkColumn(world, fx, fz).whenComplete((loc, ex) -> {
            if (loc != null) spots.add(loc);
            next(world, count, spots, ft, done);
        });
    }

    private boolean farEnough(List<Location> spots, int x, int z) {
        double min2 = cfg.minSpawnDistance * cfg.minSpawnDistance;
        for (Location s : spots) {
            double dx = s.getX() - x, dz = s.getZ() - z;
            if (dx * dx + dz * dz < min2) return false;
        }
        return true;
    }

    private CompletableFuture<Location> checkColumn(World world, int x, int z) {
        CompletableFuture<Location> res = new CompletableFuture<>();
        int cx = x >> 4, cz = z >> 4;
        world.getChunkAtAsync(cx, cz).whenComplete((chunk, ex) -> {
            if (ex != null || chunk == null) { res.complete(null); return; }
            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                try {
                    int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    Block ground = world.getBlockAt(x, y, z);
                    res.complete(isSafe(ground) ? new Location(world, x + 0.5, y + 1, z + 0.5) : null);
                } catch (Throwable t) {
                    res.complete(null);
                }
            });
        });
        return res;
    }

    private boolean isSafe(Block ground) {
        Material m = ground.getType();
        if (!m.isSolid() || UNSAFE.contains(m) || Tag.LEAVES.isTagged(m) || Tag.ICE.isTagged(m)) return false;
        Block up1 = ground.getRelative(BlockFace.UP);
        Block up2 = ground.getRelative(BlockFace.UP, 2);
        return up1.isPassable() && !up1.isLiquid() && up2.isPassable() && !up2.isLiquid();
    }
}
