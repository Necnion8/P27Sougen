package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.config.SougenConfig;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.rifledluffy.chairs.RFChairs;
import com.rifledluffy.chairs.chairs.BlockFilter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Optional;

public class Chairs {

    private static boolean HAS_CLASS;

    static {
        HAS_CLASS = true;
        try {
            Class.forName("com.rifledluffy.chairs.RFChairs");
        } catch (ClassNotFoundException e) {
            HAS_CLASS = false;
        }
    }

    private Chairs() {
    }

    public static boolean available() {
        return HAS_CLASS && Bukkit.getServer().getPluginManager().isPluginEnabled("RFChairs");
    }

    public static Optional<Runnable> register(JavaPlugin plugin, SougenConfig config, SougenManager manager) {
        if (!available()) return Optional.empty();

        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            public void onRightClick(PlayerInteractEvent event) {
                if (!available()) return;

                Player player = event.getPlayer();
                if (!manager.containsPlayerInMap(player)) return;
                if (!config.isChairs()) return;
                if (event.getClickedBlock() == null) return;

                BlockData mapBlock = manager.getBlockDataInMap(event.getPlayer(), new BlockPosition(event.getClickedBlock().getX(), event.getClickedBlock().getY(), event.getClickedBlock().getZ()));
                if (mapBlock == null) return;

                if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
                if (event.getPlayer().isSneaking()) return;
                if (event.getHand() != EquipmentSlot.HAND) return;

                ItemStack item = event.getItem();

                if (item != null && item.getType() != Material.AIR && item.getType().isBlock()) return;

                if (!BlockFilter.isStairsBlock(mapBlock.getMaterial())
                        && !BlockFilter.isCarpetBlock(mapBlock.getMaterial())
                        && !BlockFilter.isSlabBlock(mapBlock.getMaterial())) return;

                event.setCancelled(true);
                handleClick(new PlayerInteractEvent(
                        event.getPlayer(),
                        event.getAction(),
                        event.getItem(),
                        createFakeBlock(event.getClickedBlock(), mapBlock),
                        event.getBlockFace(),
                        event.getHand()));
            }

        };
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        return Optional.of(() -> HandlerList.unregisterAll(listener));
    }

    private static void handleClick(PlayerInteractEvent event) {
        if (!available()) return;
        RFChairs.getInstance().chairManager.onRightClick(event);
    }

    public static Block createFakeBlock(Block delegate, BlockData blockData) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    if ("getType".equals(name) && method.getParameterCount() == 0) {
                        return blockData.getMaterial();
                    }
                    if ("setType".equals(name) && args != null && args.length >= 1 && args[0] instanceof Material) {
                        return null;
                    }
                    if ("equals".equals(name) && args != null && args.length == 1) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name) && method.getParameterCount() == 0) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name) && method.getParameterCount() == 0) {
                        return "OverriddenBlock{type=" + blockData.getMaterial() + ", delegate=" + delegate + "}";
                    }
                    if ("getState".equals(name) && method.getParameterCount() == 0) {
                        //noinspection UnstableApiUsage
                        return blockData.createBlockState();
                    }
                    if ("getBlockData".equals(name) && method.getParameterCount() == 0) {
                        return blockData;
                    }

                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

}
