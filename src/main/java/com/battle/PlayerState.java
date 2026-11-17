package com.battle;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/** Trạng thái người chơi trước khi vào trận (chỉ lưu trong RAM ở v0.0.1). */
public record PlayerState(Location location, ItemStack[] contents, GameMode gameMode) {}
