package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SougenManager implements Listener {

    private final Map<String, SougenMap> maps = new HashMap<>();
    private final Logger logger;
    private final Map<Player, String> players = new HashMap<>();
    private PacketListener packetListener;

    public SougenManager(Logger logger) {
        this.logger = logger;
    }

    public Logger getLogger() {
        return logger;
    }

    public void addMap(String id, SougenSchematic schematic, UUID destWorldId, BlockPosition destPosition) {
        List<BlockPosition> positionList = schematic.positionList().stream()
                .map(bp -> new BlockPosition(destPosition.x() + bp.x(), destPosition.y() + bp.y(), destPosition.z() + bp.z()))
                .toList();
        SougenSchematic fixed = new SougenSchematic(positionList, schematic.blockDataList(), schematic.packetBlockDataList());
        SougenMap map = new SougenMap(id, fixed, destWorldId, destPosition);
        maps.put(id, map);
    }

    public void addPlayer(Player player, String mapId) {
        players.put(player, mapId);
        refreshPlayer(player);
    }

    public void refreshPlayer(Player player) {
        if (!player.isOnline())
            return;

        String mapId = players.get(player);
        if (mapId == null)
            return;

        SougenMap map = maps.get(mapId);
        if (map == null)
            return;

        World world = player.getWorld();
        if (!world.getUID().equals(player.getWorld().getUID()))
            return;

        //noinspection UnstableApiUsage
        player.sendMultiBlockChange(IntStream.range(0, map.schematic().positionList().size())
                .boxed()
                .collect(Collectors.toMap(i -> map.schematic().positionList().get(i).toPaper(), i -> map.schematic().blockDataList().get(i))));
    }

    public void removePlayer(Player player) {
        String mapId = players.remove(player);
        if (mapId == null)
            return;

        if (!player.isOnline())
            return;

        SougenMap map = maps.get(mapId);
        if (map == null)
            return;

        World world = player.getWorld();
        if (!world.getUID().equals(map.destWorldId()))
            return;

        player.sendBlockChanges(map.schematic().positionList().stream()
                .map(bp -> world.getBlockState(bp.x(), bp.y(), bp.z()))
                .toList());
    }

    // init

    public void setup(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        if (packetListener == null) {
            packetListener = new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.BLOCK_CHANGE) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    SougenManager.this.onBlockChangeSending(event);
                }
            };
        }
        ProtocolLibrary.getProtocolManager().addPacketListener(packetListener);
    }

    public void cleanup() {
        HandlerList.unregisterAll(this);
        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
        }
        new ArrayList<>(players.keySet()).forEach(this::removePlayer);
        players.clear();
        maps.clear();
    }

    // implementation

    private void onBlockChangeSending(PacketEvent event) {
        Player player = event.getPlayer();
        SougenMap map = maps.get(players.get(player));
        if (map == null)
            return;

        if (!map.destWorldId().equals(player.getWorld().getUID()))
            return;

        PacketContainer packet = event.getPacket();
        BlockPosition bp = BlockPosition.of(packet.getBlockPositionModifier().read(0));
        SougenSchematic schematic = map.schematic();

        int idx = schematic.positionList().indexOf(bp);
        if (idx == -1)
            return;

        WrappedBlockData blockData = schematic.packetBlockDataList().get(idx);
        packet.getBlockData().write(0, blockData);
    }

    // events

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

}
