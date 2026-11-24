package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import com.extendedclip.deluxemenus.menu.Menu;
import com.extendedclip.deluxemenus.menu.MenuHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class Menus {

    private static final Material SCROLL_SEPARATOR_MATERIAL = Material.AIR;
    private static final Set<Player> SCROLL_PLAYERS = new HashSet<>();
    private static boolean HAS_CLASS;

    static {
        HAS_CLASS = true;
        try {
            Class.forName("com.extendedclip.deluxemenus.menu.Menu");
        } catch (ClassNotFoundException e) {
            HAS_CLASS = false;
        }
    }

    private Menus() {
    }


    public static boolean available() {
        return HAS_CLASS && Bukkit.getServer().getPluginManager().isPluginEnabled("DeluxeMenus");
    }

    public static Optional<Runnable> register(JavaPlugin plugin) {
        if (!available()) return Optional.empty();

        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onInventoryClick(InventoryClickEvent event) {
                if (event.getWhoClicked() instanceof Player player && SCROLL_PLAYERS.contains(player)) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGHEST)
            public void onInventoryDrag(InventoryDragEvent event) {
                if (event.getWhoClicked() instanceof Player player && SCROLL_PLAYERS.contains(player)) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGHEST)
            public void onSwap(PlayerSwapHandItemsEvent event) {
                if (SCROLL_PLAYERS.contains(event.getPlayer())) {
                    event.setCancelled(true);
                }
            }

        };
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        return Optional.of(() -> HandlerList.unregisterAll(listener));
    }

    public static void playMenuScroll(JavaPlugin plugin, Player player, String menuName, int orientation) {
        if (!available()) return;
        if (orientation < 0 || orientation > 3) return;

        MenuHolder menuHolder = Menu.getMenuHolder(player).orElse(null);
        if (menuHolder == null || menuHolder.getMenu().isEmpty()) return;
        Menu oldMenu = menuHolder.getMenu().get();

        Menu newMenu = Menu.getMenuByName(menuName).orElse(null);
        if (newMenu == null) return;

        Inventory inv = menuHolder.getInventory();

        int size = inv.getSize();
        if (size % 9 != 0) return;

        // 0: Left, 1: Right, 2: Up, 3: Down
        boolean horizontal = orientation <= 1;
        int direction = (orientation == 0 || orientation == 2) ? 1 : -1;

        int columns = 9;
        int rows = size / columns;
        int extent = horizontal ? columns : rows;

        // DeluxeMenusの制御を切る
        player.openInventory(inv);
        SCROLL_PLAYERS.add(player);

        // 旧メニュー
        ItemStack[] oldContents = new ItemStack[size];

        oldMenu.getMenuItems().forEach((key, value) -> {
            if (key < 0 || key >= size) return;

            value.values().stream()
                    .filter(i -> i.options().viewRequirements()
                            .map(r -> r.evaluate(menuHolder))
                            .orElse(true))
                    .findFirst()
                    .ifPresent(item -> {
                        oldContents[key] = item.getItemStack(menuHolder);
                    });
        });

        // 新メニュー
        ItemStack[] newContents = new ItemStack[size];

        newMenu.getMenuItems().forEach((key, value) -> {
            if (key < 0 || key >= size) return;

            value.values().stream()
                    .filter(i -> i.options().viewRequirements()
                            .map(r -> r.evaluate(menuHolder))
                            .orElse(true))
                    .findFirst()
                    .ifPresent(item -> {
                        newContents[key] = item.getItemStack(menuHolder);
                    });
        });

        // 移動中の表示用アイテム
        ItemStack[] oldDisplay = prepareScrollContents(oldContents);
        ItemStack[] newDisplay = prepareScrollContents(newContents);

        // 区切り用アイテム
        ItemStack separator = new ItemStack(SCROLL_SEPARATOR_MATERIAL);
        ItemMeta separatorMeta = separator.getItemMeta();

        if (separatorMeta != null) {
            separatorMeta.displayName(Component.text(" "));
            separatorMeta.lore(null);
            separatorMeta.setHideTooltip(true);
            separator.setItemMeta(separatorMeta);
        }

        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || player.getOpenInventory().getTopInventory() != inv) {
                    SCROLL_PLAYERS.remove(player);
                    cancel();
                    return;
                }

                ItemStack[] frame = new ItemStack[size];

                for (int slot = 0; slot < size; slot++) {
                    int row = slot / columns;
                    int col = slot % columns;

                    int pos = horizontal ? col : row;

                    // 旧メニュー
                    int oldPos = pos - direction * ticks;

                    if (oldPos >= 0 && oldPos < extent) {
                        int oldSlot = horizontal ? row * columns + oldPos : oldPos * columns + col;
                        frame[slot] = oldDisplay[oldSlot];
                    }

                    // 新メニュー（区切り1スロット分の余白を追加）
                    int newPos = pos + direction * (extent + 1 - ticks);

                    if (newPos >= 0 && newPos < extent) {
                        int newSlot = horizontal ? row * columns + newPos : newPos * columns + col;
                        frame[slot] = newDisplay[newSlot];
                    }
                }

                // 旧・新メニューの間に1スロット分の区切りを配置
                if (ticks > 0 && ticks < extent + 1) {
                    int separatorPos = direction > 0 ? ticks - 1 : extent - ticks;

                    if (separatorPos >= 0 && separatorPos < extent) {
                        for (int cross = 0; cross < (horizontal ? rows : columns); cross++) {
                            int slot = horizontal ? cross * columns + separatorPos : separatorPos * columns + cross;
                            frame[slot] = separator;
                        }
                    }
                }

                // フレーム描画
                inv.setContents(frame);

                // 最終フレーム
                if (ticks >= extent + 1) {
                    SCROLL_PLAYERS.remove(player);
                    cancel();
                    newMenu.openMenu(player);
                    return;
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static boolean isScrolling(Player player) {
        return SCROLL_PLAYERS.contains(player);
    }


    private static ItemStack[] prepareScrollContents(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[contents.length];

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];

            if (item == null || item.getType().isAir()) continue;

            ItemStack clone = item.clone();
            ItemMeta meta = clone.getItemMeta();
            if (meta == null) continue;

            // 表示名とLoreを除去
            meta.displayName(null);
            meta.lore(null);

            // ツールチップを非表示
            meta.setHideTooltip(true);

            clone.setItemMeta(meta);

            result[i] = clone;
        }

        return result;
    }

}
