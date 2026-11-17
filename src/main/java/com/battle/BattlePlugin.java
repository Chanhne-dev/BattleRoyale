package com.battle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class BattlePlugin extends JavaPlugin {
    private Match match;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BattleConfig cfg = new BattleConfig(getConfig(), getLogger());

        WorldProvider worlds = new WorldProvider(this, cfg);
        worlds.cleanupLeftovers();

        MapRestorer restorer = new MapRestorer(this, cfg);
        match = new Match(this, cfg, worlds, restorer);

        getServer().getPluginManager().registerEvents(new GameListener(match), this);
        getServer().getPluginManager().registerEvents(new ArenaListener(restorer), this);
        BattleCommand cmd = new BattleCommand(this, match, worlds);
        getCommand("battle").setExecutor(cmd);
        getCommand("battle").setTabCompleter(cmd);

        // Vòng lặp chính 1 giây/lần trên global region (Folia)
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> match.tick(), 20L, 20L);

        getLogger().info("Battle v" + getPluginMeta().getVersion() + " đã bật. World: " + worlds.statusLine());
    }

    @Override
    public void onDisable() {
        if (match != null) match.shutdown();
    }
}
