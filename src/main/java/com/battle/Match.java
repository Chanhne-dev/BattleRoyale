package com.battle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Một trận đấu (v0.0.1: duy nhất 1 trận / server).
 * Vòng lặp chính chạy mỗi giây trên global region scheduler (tick()).
 * Thao tác trên người chơi luôn đi qua entity scheduler.
 */
public final class Match {
    private final BattlePlugin plugin;
    private final BattleConfig cfg;
    private final BorderController border;
    private final SpawnManager spawns;
    private final WorldProvider worlds;
    private final MapRestorer restorer;
    private volatile World arena;

    private final Map<UUID, PlayerState> saved = new ConcurrentHashMap<>();
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final List<Team> teams = new CopyOnWriteArrayList<>();
    private final Map<UUID, Team> teamOf = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastAttacker = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAttackTime = new ConcurrentHashMap<>();

    private final BossBar bar = BossBar.bossBar(Msg.c("Battle"), 1f,
            BossBar.Color.RED, BossBar.Overlay.PROGRESS);

    private volatile GameState state = GameState.WAITING;
    private volatile Mode mode;
    private int countdown, elapsed, generation;
    private boolean forced;

    public Match(BattlePlugin plugin, BattleConfig cfg, WorldProvider worlds, MapRestorer restorer) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.worlds = worlds;
        this.restorer = restorer;
        this.mode = cfg.defaultMode;
        this.border = new BorderController(cfg);
        this.spawns = new SpawnManager(plugin, cfg);
    }

    // ------------------------------------------------------------ truy vấn

    public GameState state() { return state; }
    public Mode mode() { return mode; }
    public int participantCount() { return participants.size(); }
    public boolean isParticipant(UUID id) { return participants.contains(id); }

    public boolean isAlive(UUID id) {
        Team t = teamOf.get(id);
        return t != null && t.isAlive(id);
    }

    /** true nếu đòn đánh này phải bị chặn vì cùng đội (friendly fire tắt). */
    public boolean blocksFriendlyFire(UUID a, UUID b) {
        if (a.equals(b) || mode.friendlyFire()) return false;
        Team ta = teamOf.get(a);
        return ta != null && ta == teamOf.get(b);
    }

    public void recordAttack(UUID victim, UUID attacker) {
        if (victim.equals(attacker)) return;
        lastAttacker.put(victim, attacker);
        lastAttackTime.put(victim, System.currentTimeMillis());
    }

    public UUID lastAttacker(UUID victim) {
        Long t = lastAttackTime.get(victim);
        if (t == null || System.currentTimeMillis() - t > 10_000) return null;
        return lastAttacker.get(victim);
    }

    // ------------------------------------------------------------ lệnh người chơi / admin

    /** @return null nếu thành công, ngược lại là thông báo lỗi. */
    public synchronized String join(Player p) {
        UUID id = p.getUniqueId();
        if (state != GameState.WAITING && state != GameState.STARTING) return "Trận đang diễn ra, hãy chờ trận sau.";
        if (participants.contains(id)) return "Bạn đã ở trong hàng chờ.";
        if (participants.size() >= cfg.maxPlayers(mode)) return "Hàng chờ đã đầy.";
        saved.put(id, new PlayerState(p.getLocation(), cloneAll(p.getInventory().getContents()), p.getGameMode()));
        participants.add(id);
        p.showBossBar(bar);
        broadcast("<yellow>" + p.getName() + "</yellow> đã tham gia <gray>("
                + participants.size() + "/" + cfg.maxPlayers(mode) + ")");
        return null;
    }

    /** @return false nếu người này không ở trong trận/hàng chờ. */
    public synchronized boolean leave(Player p) {
        UUID id = p.getUniqueId();
        if (!participants.contains(id)) return false;
        if (state == GameState.WAITING || state == GameState.STARTING) {
            participants.remove(id);
            saved.remove(id);
            p.hideBossBar(bar);
            broadcast("<yellow>" + p.getName() + "</yellow> đã rời hàng chờ.");
            return true;
        }
        if (isAlive(id)) eliminate(p, lastAttacker(id));
        restore(p);
        return true;
    }

    public synchronized String setMode(Mode m) {
        if (state != GameState.WAITING) return "Chỉ đổi chế độ khi đang chờ.";
        mode = m;
        return null;
    }

    public synchronized String forceStart() {
        if (state != GameState.WAITING && state != GameState.STARTING) return "Trận đã bắt đầu.";
        if (participants.size() <= mode.teamSize())
            return "Cần ít nhất 2 " + (mode == Mode.DUO ? "đội (từ 3 người)." : "người.");
        forced = true;
        if (state == GameState.STARTING) countdown = Math.min(countdown, 3);
        return null;
    }

    public synchronized void abort(String reason) {
        if (state == GameState.WAITING && participants.isEmpty()) return;
        broadcast(reason);
        cleanup();
    }

    // ------------------------------------------------------------ vòng lặp chính

    public synchronized void tick() {
        switch (state) {
            case WAITING -> tickWaiting();
            case STARTING -> tickStarting();
            case GRACE, ACTIVE -> tickPlaying();
            default -> { }
        }
        updateBar();
    }

    private boolean enoughToStart() {
        int n = participants.size();
        return n >= cfg.minPlayers(mode) || (forced && n >= 2);
    }

    private void tickWaiting() {
        if (participants.isEmpty()) { forced = false; return; }
        if (enoughToStart()) {
            state = GameState.STARTING;
            countdown = forced ? 5 : cfg.startCountdown;
            broadcast("<green>Đủ người! Trận bắt đầu sau " + countdown + " giây.");
        }
    }

    private void tickStarting() {
        participants.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (!enoughToStart()) {
            state = GameState.WAITING;
            broadcast("<red>Không đủ người, đã hủy đếm ngược.");
            return;
        }
        if (participants.size() >= cfg.maxPlayers(mode) && countdown > cfg.fullCountdown) {
            countdown = cfg.fullCountdown;
        }
        if (countdown <= 0) {
            if (restorer.isBusy()) return;   // đang khôi phục đấu trường
            begin();
            return;
        }
        if (countdown <= 5 || countdown % 10 == 0) {
            titleAll("<yellow>" + countdown, "<gray>Chế độ " + mode.id().toUpperCase());
        }
        countdown--;
    }

    private void begin() {
        if (participants.size() < 2) { state = GameState.WAITING; return; }

        // Chia đội ngẫu nhiên (Solo = đội 1 người, Duo = đội 2, người lẻ chơi 1 mình)
        List<UUID> ids = new ArrayList<>(participants);
        Collections.shuffle(ids);
        teams.clear();
        teamOf.clear();
        int ts = mode.teamSize();
        for (int i = 0; i < ids.size(); i += ts) {
            Team t = new Team(teams.size() + 1);
            for (int j = i; j < Math.min(i + ts, ids.size()); j++) {
                t.add(ids.get(j));
                teamOf.put(ids.get(j), t);
            }
            teams.add(t);
        }
        if (teams.size() < 2) {
            teams.clear();
            teamOf.clear();
            forced = false;
            state = GameState.WAITING;
            broadcast("<red>Cần ít nhất 2 " + (mode == Mode.DUO ? "đội" : "người") + " để bắt đầu.");
            return;
        }

        state = GameState.SPAWNING;
        broadcast("<aqua>Đang chuẩn bị đấu trường...");

        final int g = generation;
        worlds.acquire().whenComplete((world, ex) -> {
            synchronized (Match.this) {
                if (g != generation) {                       // trận đã bị hủy trong lúc tạo world
                    if (world != null) worlds.release(world);
                    return;
                }
                if (ex != null || world == null) {
                    plugin.getLogger().warning("Không có world đấu trường: " + (ex == null ? "null" : ex));
                    broadcast("<red>Không tạo được đấu trường. Trận bị hủy.");
                    cleanup();
                    return;
                }
                arena = world;
                if (!worlds.isManaged(world)) restorer.start(world);
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> border.init(world));
                broadcast("<aqua>Đang chọn điểm xuất phát...");

                spawns.findSpots(world, teams.size())
                        .orTimeout(20, TimeUnit.SECONDS)
                        .whenComplete((spots, ex2) -> {
                            synchronized (Match.this) {
                                if (g != generation) return;
                                if (ex2 != null || spots == null) {
                                    plugin.getLogger().warning("Spawn thất bại: " + (ex2 == null ? "null" : ex2.getMessage()));
                                    broadcast("<red>Không tìm được điểm spawn. Trận bị hủy.");
                                    cleanup();
                                    return;
                                }
                                teleportAll(spots, g);
                            }
                        });
            }
        });
    }

    private void teleportAll(List<Location> spots, int g) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            Team team = teams.get(i);
            Location spot = spots.get(i);   // Duo: cả đội cùng 1 điểm
            for (UUID id : team.members()) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) { team.markDead(id); continue; }
                futures.add(prepare(p, spot));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((v, e) -> {
                    synchronized (Match.this) {
                        if (g != generation) return;
                        startGrace();
                    }
                });
    }

    private CompletableFuture<Void> prepare(Player p, Location loc) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        p.getScheduler().run(plugin, t -> {
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.setSaturation(20f);
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            for (ItemStack it : cfg.kit) p.getInventory().addItem(it.clone());
            p.teleportAsync(loc).whenComplete((ok, ex) -> f.complete(null));
        }, () -> f.complete(null));
        return f;
    }

    private void startGrace() {
        state = GameState.GRACE;
        elapsed = 0;
        broadcast("<green>Trận đấu bắt đầu! Không PvP trong " + cfg.gracePeriod + " giây.");
        titleAll("<green><bold>BẮT ĐẦU", "<gray>Hãy tìm vũ khí và đồ ăn!");
        checkWin();
    }

    private void tickPlaying() {
        elapsed++;
        if (state == GameState.GRACE && elapsed >= cfg.gracePeriod) {
            state = GameState.ACTIVE;
            broadcast("<red><bold>PvP đã bật!</bold></red>");
            titleAll("<red>PvP BẬT", "<gray>Sống sót đến cuối cùng");
        }
        World world = arena;
        if (world != null) border.onTick(elapsed, world, this::broadcast);
        if (elapsed >= cfg.maxDuration) { timeout(); return; }
        sendActionBars();
    }

    // ------------------------------------------------------------ loại người / kết thúc

    /** Gọi trên thread của người bị loại (event damage / quit / lệnh leave). */
    public synchronized void eliminate(Player p, UUID killerId) {
        UUID id = p.getUniqueId();
        Team t = teamOf.get(id);
        if (t == null || !t.isAlive(id)) return;
        if (state != GameState.GRACE && state != GameState.ACTIVE) return;

        t.markDead(id);

        Location loc = p.getLocation();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) loc.getWorld().dropItemNaturally(loc, it);
        }
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);

        String msg = "<red>" + p.getName() + "</red> đã bị loại";
        if (killerId != null) {
            Team kt = teamOf.get(killerId);
            if (kt != null && kt != t) kt.addKill();
            Player k = Bukkit.getPlayer(killerId);
            if (k != null) msg += " bởi <yellow>" + k.getName() + "</yellow>";
        }
        int aliveTeams = (int) teams.stream().filter(x -> x.aliveCount() > 0).count();
        broadcast(msg + " <gray>(còn " + aliveTeams + (mode == Mode.DUO ? " đội" : " người") + ")");
        checkWin();
    }

    private void checkWin() {
        if (state != GameState.GRACE && state != GameState.ACTIVE) return;
        List<Team> alive = teams.stream().filter(x -> x.aliveCount() > 0).toList();
        if (alive.size() <= 1) {
            finish(alive.isEmpty() ? null : alive.get(0), "Chiến thắng!");
        }
    }

    private void timeout() {
        List<Team> alive = teams.stream().filter(x -> x.aliveCount() > 0).toList();
        int max = alive.stream().mapToInt(Team::kills).max().orElse(0);
        List<Team> top = alive.stream().filter(x -> x.kills() == max).toList();
        if (top.size() == 1) finish(top.get(0), "Hết giờ - thắng nhờ nhiều kill nhất!");
        else finish(null, "Hết giờ - hòa!");
    }

    private void finish(Team winner, String reason) {
        state = GameState.ENDING;
        if (winner != null) {
            String names = namesOf(winner);
            broadcast("<gold><bold>" + names + "</bold></gold> <green>" + reason);
            titleAll("<gold>CHIẾN THẮNG", "<yellow>" + names);
        } else {
            broadcast("<yellow>" + reason + " Không có người thắng.");
            titleAll("<yellow>HÒA", "<gray>Không có người thắng");
        }
        final int g = generation;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            synchronized (Match.this) {
                if (g == generation) cleanup();
            }
        }, 200L);
    }

    private String namesOf(Team t) {
        StringJoiner j = new StringJoiner(", ");
        for (UUID id : t.members()) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            j.add(n == null ? "?" : n);
        }
        return j.toString();
    }

    private void cleanup() {
        generation++;
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        participants.clear();
        teams.clear();
        teamOf.clear();
        lastAttacker.clear();
        lastAttackTime.clear();
        forced = false;
        elapsed = 0;
        state = GameState.WAITING;
        World w = arena;
        arena = null;
        if (w != null) {
            restorer.finish(w);
            worlds.release(w);
        }
    }

    // ------------------------------------------------------------ join/quit

    public synchronized void onQuit(Player p) {
        UUID id = p.getUniqueId();
        if (!participants.contains(id)) return;
        if (state == GameState.WAITING || state == GameState.STARTING) {
            participants.remove(id);
            saved.remove(id);
            broadcast("<yellow>" + p.getName() + "</yellow> đã rời hàng chờ.");
            return;
        }
        if (isAlive(id)) eliminate(p, lastAttacker(id));   // v0.0.1: thoát = bị loại
    }

    public void onJoin(Player p) {
        UUID id = p.getUniqueId();
        if (participants.contains(id)) {
            // trận vẫn đang chạy, người này đã bị loại -> làm khán giả
            p.getScheduler().run(plugin, t -> p.setGameMode(GameMode.SPECTATOR), null);
            p.showBossBar(bar);
        } else if (saved.containsKey(id)) {
            restore(p);   // trận đã kết thúc trong lúc họ offline
        }
    }

    private void restore(Player p) {
        PlayerState s = saved.remove(p.getUniqueId());
        if (s == null) return;
        p.hideBossBar(bar);
        p.getScheduler().run(plugin, t -> {
            p.setGameMode(s.gameMode());
            p.getInventory().clear();
            p.getInventory().setContents(s.contents());
            p.setHealth(20.0);
            p.setFoodLevel(20);
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.teleportAsync(s.location());
        }, null);
    }

    /** Best-effort khi tắt plugin (scheduler không còn dùng được). */
    public void shutdown() {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            PlayerState s = saved.get(id);
            if (p == null || s == null) continue;
            try {
                p.setGameMode(s.gameMode());
                p.getInventory().setContents(s.contents());
            } catch (Throwable ignored) { }
        }
        try {
            World w = arena;
            if (w != null && !worlds.isManaged(w)) border.reset(w);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ giao diện

    private void updateBar() {
        int size = participants.size();
        switch (state) {
            case WAITING -> {
                bar.name(Msg.c("<yellow>Chờ người chơi: " + size + "/" + cfg.minPlayers(mode) + " (" + mode.id() + ")"));
                bar.color(BossBar.Color.YELLOW);
                bar.progress(clamp(size / (float) cfg.minPlayers(mode)));
            }
            case STARTING -> {
                bar.name(Msg.c("<green>Bắt đầu sau " + countdown + "s"));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp(countdown / (float) Math.max(1, cfg.startCountdown)));
            }
            case SPAWNING -> {
                bar.name(Msg.c("<aqua>Đang chuẩn bị..."));
                bar.progress(1f);
            }
            case GRACE, ACTIVE -> {
                String phase = state == GameState.GRACE
                        ? "Grace " + BorderController.fmt(cfg.gracePeriod - elapsed) + " | " : "";
                bar.name(Msg.c("<red>" + phase + border.describe(elapsed)
                        + " <gray>| Còn " + BorderController.fmt(cfg.maxDuration - elapsed)));
                bar.color(BossBar.Color.RED);
                bar.progress(clamp(1f - elapsed / (float) cfg.maxDuration));
            }
            case ENDING -> {
                bar.name(Msg.c("<gold>Trận đã kết thúc"));
                bar.progress(1f);
            }
        }
    }

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    private void sendActionBars() {
        int alive = teams.stream().mapToInt(Team::aliveCount).sum();
        for (Team t : teams) {
            final int kills = t.kills();
            for (UUID id : t.members()) {
                if (!t.isAlive(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.getScheduler().run(plugin, task -> {
                    Location l = p.getLocation();
                    WorldBorder wb = l.getWorld().getWorldBorder();
                    Location c = wb.getCenter();
                    double half = wb.getSize() / 2;
                    double d = Math.min(half - Math.abs(l.getX() - c.getX()),
                            half - Math.abs(l.getZ() - c.getZ()));
                    String zone = d >= 0 ? "<green>Cách mép bo " + (int) d + "m" : "<red>NGOÀI BO! " + (int) -d + "m";
                    p.sendActionBar(Msg.c(zone + " <gray>| <white>Sống: " + alive + " <gray>| <white>Kill đội: " + kills));
                }, null);
            }
        }
    }

    public void broadcast(String mini) {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(Msg.c(Msg.PREFIX + mini));
        }
    }

    private void titleAll(String title, String sub) {
        Title tt = Title.title(Msg.c(title), Msg.c(sub),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)));
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showTitle(tt);
        }
    }

    private static ItemStack[] cloneAll(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }
}
