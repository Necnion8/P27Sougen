package com.gmail.necnionch.myplugin.p27sougen.bukkit;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.config.SougenConfig;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.Holograms;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.Portals;
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

import java.util.Optional;

public final class SougenPlugin extends JavaPlugin implements Listener {

    private final SougenConfig config = new SougenConfig(this);
    private final SougenManager manager = new SougenManager(this, getLogger(), config);

    @Override
    public void onEnable() {
        if (Holograms.available()) {
            getLogger().info("Hologram API Hooked!");
        }
        if (Portals.register(this)) {
            getLogger().info("Portal API Hooked!");
        }

        config.load();
        manager.loadMaps();
        manager.setup();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        manager.shutdown();
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
        if (manager.containsPlayer(player)) {
            manager.removePlayer(player, true);
        } else {
            manager.addPlayer(player, "test", true);
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

        if (manager.getSougenEntityMapId(targetEntity) != null) {
            manager.removeSougenEntity(targetEntity.getWorld(), targetEntity);
            player.sendMessage("Tag Removed");
        } else {
            manager.addSougenEntity(targetEntity.getWorld(), targetEntity, "test");
            player.sendMessage("Tag Added");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroy(BlockBreakEvent event) {
        if (!manager.containsBlock(event.getPlayer(), BlockPosition.of(event.getBlock().getLocation())))
            return;

        event.setCancelled(true);
    }

}
