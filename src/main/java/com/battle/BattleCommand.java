package com.battle;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class BattleCommand implements TabExecutor {
    private final BattlePlugin plugin;
    private final Match match;
    private final WorldProvider worlds;

    public BattleCommand(BattlePlugin plugin, Match match, WorldProvider worlds) {
        this.plugin = plugin;
        this.match = match;
        this.worlds = worlds;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase()) {
            case "join" -> {
                if (!(s instanceof Player p)) { Msg.send(s, "<red>Chỉ người chơi mới dùng được."); return true; }
                String err = match.join(p);
                Msg.send(p, err == null ? "<green>Đã vào hàng chờ." : "<red>" + err);
            }
            case "leave" -> {
                if (!(s instanceof Player p)) { Msg.send(s, "<red>Chỉ người chơi mới dùng được."); return true; }
                Msg.send(p, match.leave(p) ? "<yellow>Đã rời trận." : "<red>Bạn không ở trong trận.");
            }
            case "status" -> Msg.send(s, "Trạng thái: <yellow>" + match.state() + "</yellow>, chế độ: <yellow>"
                    + match.mode().id() + "</yellow>, người chơi: <yellow>" + match.participantCount());
            case "mode" -> {
                if (!admin(s)) return true;
                if (a.length < 2) { Msg.send(s, "<red>/battle mode <solo|duo>"); return true; }
                String err = match.setMode(Mode.fromId(a[1]));
                Msg.send(s, err == null ? "<green>Đã đổi chế độ: " + match.mode().id() : "<red>" + err);
            }
            case "forcestart" -> {
                if (!admin(s)) return true;
                String err = match.forceStart();
                Msg.send(s, err == null ? "<green>Sẽ bắt đầu ngay." : "<red>" + err);
            }
            case "stop" -> {
                if (!admin(s)) return true;
                match.abort("<red>Admin đã dừng trận.");
                Msg.send(s, "<green>Đã dừng.");
            }
            case "world" -> {
                if (!admin(s)) return true;
                String sub = a.length > 1 ? a[1].toLowerCase() : "status";
                if (sub.equals("check")) {
                    Msg.send(s, "<yellow>Đang thử tạo world tạm, có thể mất vài giây...");
                    worlds.probe().thenAccept(r -> Msg.send(s, r));
                } else {
                    Msg.send(s, "World: <yellow>" + worlds.statusLine());
                }
            }
            case "setcenter" -> {
                if (!admin(s)) return true;
                if (!(s instanceof Player p)) { Msg.send(s, "<red>Chỉ người chơi mới dùng được."); return true; }
                Location l = p.getLocation();
                plugin.getConfig().set("arena.center-x", l.getBlockX() + 0.5);
                plugin.getConfig().set("arena.center-z", l.getBlockZ() + 0.5);
                if (!plugin.getConfig().getString("world.mode", "create").equalsIgnoreCase("create")) {
                    plugin.getConfig().set("world.static-world", l.getWorld().getName());
                }
                plugin.saveConfig();
                Msg.send(p, "<green>Đã lưu tâm đấu trường. <gray>(cần restart server để áp dụng)");
            }
            default -> help(s);
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("battle.admin")) return true;
        Msg.send(s, "<red>Bạn không có quyền.");
        return false;
    }

    private void help(CommandSender s) {
        Msg.send(s, "<yellow>/battle join | leave | status");
        if (s.hasPermission("battle.admin")) {
            Msg.send(s, "<yellow>/battle mode <solo|duo> | forcestart | stop | setcenter");
            Msg.send(s, "<yellow>/battle world [status|check]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("join", "leave", "status"));
            if (s.hasPermission("battle.admin")) out.addAll(List.of("mode", "forcestart", "stop", "setcenter", "world"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("mode")) {
            out.addAll(List.of("solo", "duo"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("world")) {
            out.addAll(List.of("status", "check"));
        }
        return out.stream().filter(x -> x.startsWith(a[a.length - 1].toLowerCase())).toList();
    }
}
