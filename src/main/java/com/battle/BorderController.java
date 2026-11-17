package com.battle;

import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.util.function.Consumer;

/** Điều khiển vòng bo theo thời gian đã trôi (giây) kể từ lúc thả vào map. */
public final class BorderController {
    private final BattleConfig cfg;
    private final int[] shrinkStart;
    private final int[] shrinkEnd;

    public BorderController(BattleConfig cfg) {
        this.cfg = cfg;
        int n = cfg.phases.size();
        shrinkStart = new int[n];
        shrinkEnd = new int[n];
        int t = cfg.gracePeriod;
        for (int i = 0; i < n; i++) {
            BorderPhase p = cfg.phases.get(i);
            shrinkStart[i] = t + p.delay();
            shrinkEnd[i] = shrinkStart[i] + p.shrink();
            t = shrinkEnd[i];
        }
    }

    /** Gọi từ global region thread. */
    public void init(World world) {
        WorldBorder b = world.getWorldBorder();
        b.setCenter(cfg.centerX, cfg.centerZ);
        b.setSize(cfg.startSize);
        b.setDamageBuffer(0);
        b.setDamageAmount(1.0);
        b.setWarningDistance(cfg.warningDistance);
    }

    /** Gọi từ global region thread. */
    public void reset(World world) {
        world.getWorldBorder().reset();
    }

    /** Gọi mỗi giây từ global region thread. */
    public void onTick(int elapsed, World world, Consumer<String> announce) {
        for (int i = 0; i < shrinkStart.length; i++) {
            BorderPhase p = cfg.phases.get(i);
            if (elapsed == shrinkStart[i]) {
                WorldBorder b = world.getWorldBorder();
                b.setDamageAmount(p.damage());
                b.setSize(p.size(), p.shrink());
                announce.accept("<red><bold>Vòng bo đang thu nhỏ!</bold></red> <gray>(vòng "
                        + (i + 1) + "/" + shrinkStart.length + " -> " + (int) p.size() + " block)");
                return;
            }
            int left = shrinkStart[i] - elapsed;
            if (left == 30 || left == 10 || left == 5) {
                announce.accept("<yellow>Vòng bo sẽ thu sau " + left + " giây!</yellow>");
                return;
            }
        }
    }

    public String describe(int elapsed) {
        for (int i = 0; i < shrinkStart.length; i++) {
            if (elapsed < shrinkStart[i])
                return "Bo thu sau " + fmt(shrinkStart[i] - elapsed) + " (vòng " + (i + 1) + ")";
            if (elapsed < shrinkEnd[i])
                return "Bo đang thu " + fmt(shrinkEnd[i] - elapsed) + " (vòng " + (i + 1) + ")";
        }
        return "Bo đã chốt";
    }

    public static String fmt(int s) {
        return String.format("%d:%02d", Math.max(0, s) / 60, Math.max(0, s) % 60);
    }
}
