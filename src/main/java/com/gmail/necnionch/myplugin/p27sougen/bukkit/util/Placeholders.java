package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
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
            if (params.equals("map_current")) {
                SougenMap map = manager.getPlayerMap(player);
                return map != null ? map.id() : "";
            }
            return null;
        }
    }

}
