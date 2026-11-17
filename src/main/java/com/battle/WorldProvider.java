package com.battle;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cấp world đấu trường cho trận.
 *  - create: tạo world mới (sao chép template hoặc sinh mới) và gỡ/xóa sau trận.
 *  - static: dùng world dựng sẵn.
 *
 * LƯU Ý: Folia gốc đã gỡ API tạo/gỡ world lúc chạy. Lớp này gọi Bukkit.createWorld /
 * unloadWorld trên global region thread; nếu server không hỗ trợ sẽ bắt lỗi và
 * (tùy cấu hình) lùi về static-world.
 */
public final class WorldProvider {
    private static final Set<String> SKIP_FILES = Set.of("uid.dat", "session.lock");

    private final BattlePlugin plugin;
    private final BattleConfig cfg;
    private final Set<String> created = ConcurrentHashMap.newKeySet();
    private volatile boolean createUnsupported;
    private final Map<String, BorderSnap> snapshots = new ConcurrentHashMap<>();

    private record BorderSnap(double cx, double cz, double size, double damage, double buffer, int warning) {
        static BorderSnap of(WorldBorder b) {
            return new BorderSnap(b.getCenter().getX(), b.getCenter().getZ(), b.getSize(),
                    b.getDamageAmount(), b.getDamageBuffer(), b.getWarningDistance());
        }
    }

    private String staticName() { return cfg.staticWorld.isBlank() ? "(world chính)" : cfg.staticWorld; }

    public WorldProvider(BattlePlugin plugin, BattleConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public boolean isManaged(World w) { return created.contains(w.getName()); }

    public String statusLine() {
        return "mode=" + (cfg.createMode ? "create" : "static")
                + ", source=" + (cfg.sourceTemplate ? "template" : "generate")
                + ", fallback=" + cfg.fallbackToStatic
                + ", static=" + staticName()
                + ", createUnsupported=" + createUnsupported
                + ", worldsActive=" + created.size();
    }

    /** Gọi khi bật plugin: xóa world còn sót của trận trước (chưa được nạp). */
    public void cleanupLeftovers() {
        File[] dirs = Bukkit.getWorldContainer()
                .listFiles(f -> f.isDirectory() && f.getName().startsWith(cfg.namePrefix));
        if (dirs == null) return;
        for (File d : dirs) {
            if (Bukkit.getWorld(d.getName()) != null) continue;
            FileUtil.deleteDir(d.toPath());
            plugin.getLogger().info("Đã xóa world còn sót: " + d.getName());
        }
    }

    // ------------------------------------------------------------ acquire

    public CompletableFuture<World> acquire() {
        if (!cfg.createMode || createUnsupported) return staticWorld();

        CompletableFuture<World> out = new CompletableFuture<>();
        createRuntime().whenComplete((w, ex) -> {
            if (w != null) { out.complete(w); return; }
            Throwable cause = unwrap(ex);
            if (cause instanceof UnsupportedOperationException) {
                createUnsupported = true;
                plugin.getLogger().warning("Server không hỗ trợ tạo world lúc chạy: " + cause);
            } else {
                plugin.getLogger().warning("Tạo world thất bại: " + cause);
            }
            if (cfg.fallbackToStatic) {
                plugin.getLogger().warning("Dùng static-world " + staticName() + ".");
                staticWorld().whenComplete((sw, e2) -> {
                    if (e2 != null) out.completeExceptionally(e2); else out.complete(sw);
                });
            } else {
                out.completeExceptionally(cause);
            }
        });
        return out;
    }

    private CompletableFuture<World> staticWorld() {
        World w = cfg.staticWorld.isBlank() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(cfg.staticWorld);
        if (w == null) {
            StringBuilder names = new StringBuilder();
            for (World x : Bukkit.getWorlds()) names.append(x.getName()).append(' ');
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Không tìm thấy static-world '" + cfg.staticWorld + "'. Các world đang có: " + names));
        }
        CompletableFuture<World> f = new CompletableFuture<>();
        // đọc border trên global thread, lưu lại để trả về nguyên trạng sau trận
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                snapshots.computeIfAbsent(w.getName(), k -> BorderSnap.of(w.getWorldBorder()));
                f.complete(w);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private CompletableFuture<World> createRuntime() {
        final String name = cfg.namePrefix + Long.toString(System.currentTimeMillis(), 36);
        final Path dst = Bukkit.getWorldContainer().toPath().resolve(name);
        CompletableFuture<World> out = new CompletableFuture<>();
        CompletableFuture<Void> prepared = new CompletableFuture<>();

        if (cfg.sourceTemplate) {
            Path tpl = plugin.getDataFolder().toPath().resolve(cfg.templatePath);
            if (!Files.isDirectory(tpl)) {
                out.completeExceptionally(new IllegalStateException("Không thấy template: " + tpl));
                return out;
            }
            Bukkit.getAsyncScheduler().runNow(plugin, t -> {
                try {
                    FileUtil.copyDir(tpl, dst, SKIP_FILES);
                    prepared.complete(null);
                } catch (Throwable e) {
                    prepared.completeExceptionally(e);
                }
            });
        } else {
            prepared.complete(null);
        }

        prepared.whenComplete((v, ex) -> {
            if (ex != null) { deleteAsync(dst); out.completeExceptionally(ex); return; }
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    WorldCreator wc = new WorldCreator(name);
                    if (!cfg.sourceTemplate) {
                        wc.environment(World.Environment.NORMAL);
                        if (cfg.seed != 0) wc.seed(cfg.seed);
                    }
                    World w = Bukkit.createWorld(wc);
                    if (w == null) throw new IllegalStateException("createWorld trả về null");
                    created.add(name);
                    try {
                        w.setDifficulty(Difficulty.NORMAL);
                        w.setPVP(true);
                    } catch (Throwable ignored) { }
                    out.complete(w);
                } catch (Throwable t) {
                    deleteAsync(dst);
                    out.completeExceptionally(t);
                }
            });
        });
        return out;
    }

    // ------------------------------------------------------------ release

    /** Gọi sau khi trận kết thúc (người chơi đã được đưa đi). */
    public void release(World w) {
        if (!isManaged(w)) {   // world có sẵn: trả border về nguyên trạng ban đầu
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    WorldBorder b = w.getWorldBorder();
                    BorderSnap s = snapshots.get(w.getName());
                    if (s == null) { b.reset(); return; }
                    b.setCenter(s.cx(), s.cz());
                    b.setSize(s.size());
                    b.setDamageAmount(s.damage());
                    b.setDamageBuffer(s.buffer());
                    b.setWarningDistance(s.warning());
                } catch (Throwable ignored) { }
            });
            return;
        }
        if (!cfg.deleteAfterMatch) return;
        tryUnload(w, 3);
    }

    private void tryUnload(World w, int attemptsLeft) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            String name = w.getName();
            boolean ok = false;
            int left = attemptsLeft;
            try {
                ok = Bukkit.unloadWorld(w, false);
            } catch (Throwable e) {
                plugin.getLogger().warning("Không gỡ được world '" + name + "': " + e);
                left = 0;
            }
            if (ok) {
                created.remove(name);
                deleteAsync(Bukkit.getWorldContainer().toPath().resolve(name));
            } else if (left > 1) {
                tryUnload(w, left - 1);
            } else {
                plugin.getLogger().warning("World '" + name + "' chưa gỡ được; sẽ bị xóa ở lần khởi động sau.");
            }
        }, cfg.unloadDelayTicks);
    }

    private void deleteAsync(Path dir) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> FileUtil.deleteDir(dir));
    }

    // ------------------------------------------------------------ kiểm tra khả năng

    /** Thử tạo rồi gỡ một world tạm để biết server có hỗ trợ không (lệnh /battle world check). */
    public CompletableFuture<String> probe() {
        CompletableFuture<String> out = new CompletableFuture<>();
        final String name = cfg.namePrefix + "probe_" + Long.toString(System.currentTimeMillis(), 36);
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                World w = Bukkit.createWorld(new WorldCreator(name));
                if (w == null) { out.complete("Tạo world: THẤT BẠI (createWorld trả về null)."); return; }
                String msg = "Tạo world: OK. ";
                try {
                    boolean un = Bukkit.unloadWorld(w, false);
                    msg += un ? "Gỡ world: OK." : "Gỡ world: KHÔNG (world sẽ bị xóa khi khởi động lại).";
                    if (un) deleteAsync(Bukkit.getWorldContainer().toPath().resolve(name));
                } catch (Throwable e) {
                    msg += "Gỡ world: KHÔNG hỗ trợ (" + e + ").";
                }
                out.complete(msg);
            } catch (Throwable t) {
                out.complete("Tạo world: KHÔNG hỗ trợ (" + unwrap(t) + "). Hãy đặt world.mode: static (plugin sẽ tự khôi phục địa hình).");
            }
        });
        return out;
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        return t;
    }
}
