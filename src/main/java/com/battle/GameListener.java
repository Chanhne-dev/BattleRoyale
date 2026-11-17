package com.battle;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class GameListener implements Listener {
    private final Match match;

    public GameListener(Match match) { this.match = match; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        UUID vid = victim.getUniqueId();
        if (!match.isParticipant(vid)) return;

        GameState st = match.state();
        if (st == GameState.ENDING || st == GameState.SPAWNING) { e.setCancelled(true); return; }
        if (st != GameState.GRACE && st != GameState.ACTIVE) return;
        if (!match.isAlive(vid)) return;

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            Entity d = ee.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Player s) attacker = s;
            else if (d instanceof Player pl) attacker = pl;
        }

        if (attacker != null && !attacker.getUniqueId().equals(vid)) {
            UUID aid = attacker.getUniqueId();
            if (st == GameState.GRACE
                    || !match.isAlive(aid)
                    || match.blocksFriendlyFire(vid, aid)) {
                e.setCancelled(true);
                return;
            }
            match.recordAttack(vid, aid);
        }

        // Đòn chí mạng: tự xử lý thay vì để chết (không cần màn hình respawn)
        if (e.getFinalDamage() >= victim.getHealth()) {
            e.setCancelled(true);
            UUID killer = attacker != null && !attacker.getUniqueId().equals(vid)
                    ? attacker.getUniqueId() : match.lastAttacker(vid);
            match.eliminate(victim, killer);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { match.onQuit(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { match.onJoin(e.getPlayer()); }
}
