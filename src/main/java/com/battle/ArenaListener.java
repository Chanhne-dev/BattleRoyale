package com.battle;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.InventoryHolder;

/** Ghi lại block gốc trước khi bị thay đổi (MONITOR, chỉ các sự kiện không bị hủy). */
public final class ArenaListener implements Listener {
    private final MapRestorer r;

    public ArenaListener(MapRestorer r) { this.r = r; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (r.isRecording()) r.recordBreak(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!r.isRecording()) return;
        if (e instanceof BlockMultiPlaceEvent m) {
            for (BlockState s : m.getReplacedBlockStates()) r.record(s);
        } else {
            r.record(e.getBlockReplacedState());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!r.isRecording()) return;
        for (Block b : e.blockList()) r.recordWithAbove(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!r.isRecording()) return;
        for (Block b : e.blockList()) r.recordWithAbove(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (r.isRecording()) r.recordBreak(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {   // gồm cả BlockSpreadEvent (lửa, cỏ lan)
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent e) {
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (r.isRecording()) r.record(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (r.isRecording()) r.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!r.isRecording()) return;
        BlockFace d = e.getDirection();
        r.record(e.getBlock().getRelative(d));
        for (Block b : e.getBlocks()) { r.record(b); r.record(b.getRelative(d)); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!r.isRecording()) return;
        BlockFace d = e.getDirection();
        r.record(e.getBlock().getRelative(d));
        for (Block b : e.getBlocks()) {
            r.record(b);
            r.record(b.getRelative(d));
            r.record(b.getRelative(d.getOppositeFace()));
        }
    }

    /** Lưu nội dung rương TRƯỚC khi người chơi lấy đồ để khôi phục lại sau trận. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!r.isRecording()) return;
        recordHolder(e.getInventory().getHolder());
    }

    private void recordHolder(InventoryHolder h) {
        if (h instanceof DoubleChest dc) {
            recordHolder(dc.getLeftSide());
            recordHolder(dc.getRightSide());
        } else if (h instanceof BlockState bs) {
            r.record(bs.getBlock());
        }
    }
}
