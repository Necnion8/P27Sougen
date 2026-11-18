package com.gmail.necnionch.myplugin.p27sougen.bukkit;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenSchematic;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.Holograms;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.Portals;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;

public final class SougenPlugin extends JavaPlugin implements Listener {

    private final SougenManager manager = new SougenManager(this, getLogger());

    @Override
    public void onEnable() {
        manager.setup();
        addSougenSchematics();
        getServer().getPluginManager().registerEvents(this, this);

        if (Holograms.isHooked()) {
            getLogger().info("Hologram API Hooked!");
        }
        if (Portals.isHooked()) {
            Portals.setup(this);
            getLogger().info("Portal API Hooked!");
        }
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

    private void addSougenSchematics() {
        Path schemFilePath = Path.of("./plugins/WorldEdit/schematics/sougen.schem");
        SougenSchematic schematic;
        try {
            schematic = SougenSchematic.fromSchematicFile(schemFilePath);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to load schematic: " + schemFilePath, e);
            return;
        }

        BlockPosition position = new BlockPosition(120, 63, 23);
        World world = getServer().getWorld("lobby");
        if (world == null) {
            getLogger().warning("Not exists world: " + "lobby");
            return;
        }

        manager.addMap("test", schematic, world.getUID(), position);
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
            Holograms.showHologram(player, "test_holo");
            Portals.blockedNames().add("test_portal");
        } else {
            manager.addPlayer(player, "test", true);
            Holograms.hideHologram(player, "test_holo");
            Portals.blockedNames().remove("test_portal");
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
