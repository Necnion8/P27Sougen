package com.gmail.necnionch.myplugin.p27sougen.bukkit;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.config.SougenConfig;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.listener.PlayerFloatingListener;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SougenPlugin extends JavaPlugin implements Listener {

    private final SougenConfig config = new SougenConfig(this);
    private final SougenManager manager = new SougenManager(this, getLogger(), config);
    private final List<Runnable> unregisters = new ArrayList<>();

    @Override
    public void onEnable() {
        if (Holograms.available()) {
            getLogger().info("Hologram API Hooked!");
        }
        Portals.register(this).ifPresent(handler -> {
            getLogger().info("Portal API Hooked!");
            unregisters.add(handler);
        });

        config.load();
        manager.loadMaps();
        manager.setup();
        getServer().getPluginManager().registerEvents(this, this);
        setupPlayerFloatings();

        Placeholders.register(this, manager).ifPresent(handler -> {
            getLogger().info("Placeholder API Hooked!");
            unregisters.add(handler);
        });
        Menus.register(this).ifPresent(handler -> {
            getLogger().info("Menu API Hooked!");
            unregisters.add(handler);
        });
    }

    @Override
    public void onDisable() {
        manager.shutdown();
        unregisters.reversed().forEach(Runnable::run);
        unregisters.clear();
    }

    private void setupPlayerFloatings() {
        if (getServer().getAllowFlight()) return;
        FloatingKickController controller;
        try {
            controller = FloatingKickController.getOrCreate();
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to initialize FloatingKickController. Floating kick protection is unavailable. You may need to enable 'allow-flight' in server.properties.");
            getLogger().warning("[Cause] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        unregisters.add(PlayerFloatingListener.register(this, manager, controller));
    }

    public SougenManager getManager() {
        return manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        if (args.length >= 1 && "set".equalsIgnoreCase(args[0])) {
            Player player = (Player) sender;
            String mapId = (args.length >= 2) ? args[1] : "test";
            manager.addPlayer(player, mapId, false);

        } else if (args.length == 1 && "unset".equalsIgnoreCase(args[0])) {
            Player player = (Player) sender;
            manager.removePlayer(player, false);
        } else if (args.length == 1 && "item".equalsIgnoreCase(args[0])) {
            Player player = (Player) sender;
            ItemStack itemStack = ItemStack.of(Material.LEVER);
            itemStack.editMeta(meta -> {
                meta.displayName(Component.text("Switch"));
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(new NamespacedKey(this, "i"), PersistentDataType.STRING, "switch");
            });

            PlayerInventory inv = player.getInventory();
            inv.addItem(inv.getItemInMainHand());
            inv.setItemInMainHand(itemStack);

            itemStack = ItemStack.of(Material.NAME_TAG);
            itemStack.editMeta(meta -> {
                meta.displayName(Component.text("Entity Tag"));
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(new NamespacedKey(this, "i"), PersistentDataType.STRING, "entity");
            });
            inv.addItem(itemStack);

        } else if (args.length == 2 && "menu-left".equalsIgnoreCase(args[0])) {
            Menus.playMenuScroll(this, (Player) sender, args[1], 0);
        } else if (args.length == 2 && "menu-right".equalsIgnoreCase(args[0])) {
            Menus.playMenuScroll(this, (Player) sender, args[1], 1);
        } else if (args.length == 2 && "menu-up".equalsIgnoreCase(args[0])) {
            Menus.playMenuScroll(this, (Player) sender, args[1], 2);
        } else if (args.length == 2 && "menu-down".equalsIgnoreCase(args[0])) {
            Menus.playMenuScroll(this, (Player) sender, args[1], 3);
        }

        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || !EquipmentSlot.HAND.equals(event.getHand()))
            return;

        Optional<ItemMeta> itemMeta = Optional.ofNullable(event.getItem()).map(ItemStack::getItemMeta);
        if (itemMeta.isEmpty())
            return;

        if (!"switch".equalsIgnoreCase(itemMeta.get().getPersistentDataContainer().get(new NamespacedKey(this, "i"), PersistentDataType.STRING)))
            return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        SougenMap map = manager.getPlayerMap(player);
        if (map != null && "test0".equals(map.id())) {
//            manager.removePlayer(player, true);
            manager.addPlayer(player, "test1", true);
        } else {
            manager.addPlayer(player, "test0", true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (!EquipmentSlot.HAND.equals(event.getHand()))
            return;

        Player player = event.getPlayer();
        Optional<ItemMeta> itemMeta = Optional.of(player.getInventory().getItemInMainHand()).map(ItemStack::getItemMeta);
        if (itemMeta.isEmpty())
            return;

        if (!"entity".equalsIgnoreCase(itemMeta.get().getPersistentDataContainer().get(new NamespacedKey(this, "i"), PersistentDataType.STRING)))
            return;

        event.setCancelled(true);
        Entity targetEntity = event.getRightClicked();

        String entityMapId = manager.getSougenEntityMapId(targetEntity);
        if (entityMapId != null) {
            manager.removeSougenEntity(targetEntity.getWorld(), targetEntity);
            player.sendMessage("Tag Removed: " + entityMapId);
        } else {
            SougenMap map = manager.getPlayerMap(player);
            if (map != null) {
                String mapId = map.id();
                manager.addSougenEntity(targetEntity.getWorld(), targetEntity, mapId);
                player.sendMessage("Tag Added: " + mapId);
            } else {
                player.sendMessage("Not on the map");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroy(BlockBreakEvent event) {
        if (!manager.containsBlock(event.getPlayer(), BlockPosition.of(event.getBlock().getLocation())))
            return;

        event.setCancelled(true);
    }

}
