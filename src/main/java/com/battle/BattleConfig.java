package com.battle;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class BattleConfig {
    // world
    public final boolean createMode;          // true = create, false = static
    public final boolean fallbackToStatic;
    public final String staticWorld;
    public final String namePrefix;
    public final boolean sourceTemplate;      // true = template, false = generate
    public final String templatePath;
    public final long seed;
    public final boolean deleteAfterMatch;
    public final long unloadDelayTicks;
    public final boolean restoreEnabled, clearEntities;
    // arena
    public final double centerX, centerZ;
    // match
    public final Mode defaultMode;
    public final int maxDuration, gracePeriod, startCountdown, fullCountdown, spawnAttempts;
    public final double minSpawnDistance, spawnEdgeMargin;
    // border
    public final double startSize;
    public final int warningDistance;
    public final List<BorderPhase> phases;
    public final List<ItemStack> kit;
    private final Map<Mode, int[]> limits = new EnumMap<>(Mode.class);

    public BattleConfig(FileConfiguration c, Logger log) {
        createMode = c.getString("world.mode", "static").equalsIgnoreCase("create");
        fallbackToStatic = c.getBoolean("world.fallback-to-static", true);
        staticWorld = c.getString("world.static-world", "");
        String prefix = c.getString("world.name-prefix", "battle_match_").toLowerCase(Locale.ROOT);
        if (!prefix.matches("[a-z0-9_]{3,}")) {
            log.warning("world.name-prefix không hợp lệ ('" + prefix + "'), dùng 'battle_match_'.");
            prefix = "battle_match_";
        }
        namePrefix = prefix;
        sourceTemplate = c.getString("world.source", "generate").equalsIgnoreCase("template");
        templatePath = c.getString("world.template", "templates/arena");
        seed = c.getLong("world.seed", 0L);
        deleteAfterMatch = c.getBoolean("world.delete-after-match", true);
        unloadDelayTicks = Math.max(20L, c.getLong("world.unload-delay-ticks", 100L));
        restoreEnabled = c.getBoolean("restore.enabled", true);
        clearEntities = c.getBoolean("restore.clear-entities", true);

        centerX = c.getDouble("arena.center-x", 0);
        centerZ = c.getDouble("arena.center-z", 0);
        defaultMode = Mode.fromId(c.getString("mode", "solo"));

        maxDuration = c.getInt("match.max-duration", 900);
        gracePeriod = c.getInt("match.grace-period", 45);
        startCountdown = c.getInt("match.start-countdown", 30);
        fullCountdown = c.getInt("match.full-countdown", 10);
        minSpawnDistance = c.getDouble("match.min-spawn-distance", 32);
        spawnEdgeMargin = c.getDouble("match.spawn-edge-margin", 20);
        spawnAttempts = c.getInt("match.spawn-attempts", 300);

        for (Mode m : Mode.values()) {
            limits.put(m, new int[]{
                    c.getInt("modes." + m.id() + ".min-players", 4),
                    c.getInt("modes." + m.id() + ".max-players", 20)});
        }

        startSize = c.getDouble("border.start-size", 400);
        warningDistance = c.getInt("border.warning-distance", 10);
        List<BorderPhase> ps = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("border.phases")) {
            ps.add(new BorderPhase(
                    ((Number) m.get("wait")).intValue(),
                    ((Number) m.get("shrink")).intValue(),
                    ((Number) m.get("size")).doubleValue(),
                    ((Number) m.get("damage")).doubleValue()));
        }
        phases = List.copyOf(ps);

        int total = gracePeriod;
        for (BorderPhase p : phases) total += p.delay() + p.shrink();
        if (total > maxDuration) {
            log.warning("Tổng grace + các phase bo = " + total + "s > max-duration (" + maxDuration + "s).");
        }

        List<ItemStack> items = new ArrayList<>();
        for (String s : c.getStringList("starter-kit")) {
            String[] parts = s.split(":");
            Material mat = Material.matchMaterial(parts[0].trim());
            if (mat == null) { log.warning("starter-kit: vật phẩm không hợp lệ: " + s); continue; }
            int amt = 1;
            if (parts.length > 1) {
                try { amt = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
            }
            items.add(new ItemStack(mat, amt));
        }
        kit = List.copyOf(items);
    }

    public int minPlayers(Mode m) { return limits.get(m)[0]; }
    public int maxPlayers(Mode m) { return limits.get(m)[1]; }
}
