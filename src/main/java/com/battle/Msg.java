package com.battle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gold>[Battle]</gold> ";

    private Msg() {}

    public static Component c(String mini) { return MM.deserialize(mini); }

    public static void send(CommandSender to, String mini) { to.sendMessage(c(PREFIX + mini)); }
}
