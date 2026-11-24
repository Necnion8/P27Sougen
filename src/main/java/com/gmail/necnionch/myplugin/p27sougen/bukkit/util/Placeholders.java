package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

public class Placeholders {

    private static boolean HAS_CLASS;
    private static boolean REGISTERED;

    static {
        HAS_CLASS = true;
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        } catch (ClassNotFoundException e) {
            HAS_CLASS = false;
        }
    }

    private Placeholders() {
    }


    public static boolean available() {
        return REGISTERED;
    }

    public static Optional<Runnable> register(JavaPlugin plugin, SougenManager manager) {
        REGISTERED = false;
        if (!HAS_CLASS || !Bukkit.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
            return Optional.empty();

        MyPlaceholder expansion = new MyPlaceholder(plugin, manager);
        REGISTERED = expansion.register();
        return Optional.of(() -> {
            REGISTERED = false;
            expansion.unregister();
        });
    }


    private static final class MyPlaceholder extends PlaceholderExpansion {

        private final JavaPlugin plugin;
        private final SougenManager manager;

        public MyPlaceholder(JavaPlugin plugin, SougenManager manager) {
            this.plugin = plugin;
            this.manager = manager;
        }

        @Override
        public @NotNull String getAuthor() {
            //noinspection UnstableApiUsage
            return plugin.getPluginMeta().getAuthors().toString();
        }

        @Override
        public @NotNull String getVersion() {
            //noinspection UnstableApiUsage
            return plugin.getPluginMeta().getVersion();
        }

        @Override
        public @NotNull String getIdentifier() {
            return plugin.getName().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
            switch (params) {
                case "map_current" -> {
                    SougenMap map = manager.getPlayerMap(player);
                    return map != null ? map.id() : "";
                }
                case "map_current_display" -> {
                    SougenMap map = manager.getPlayerMap(player);
                    if (map != null) {
                        String name = Optional.ofNullable(map.config().name()).orElse(map.id());
                        return ChatColor.translateAlternateColorCodes('&', name);
                    } else {
                        return "";
                    }
                }
                case "menu_is_scrolling" -> {
                    return String.valueOf(Menus.isScrolling(player));
                }
            }
            return null;
        }
    }

}
