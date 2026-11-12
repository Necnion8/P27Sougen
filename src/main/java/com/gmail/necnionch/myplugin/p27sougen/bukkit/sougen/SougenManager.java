package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SougenManager implements Listener {

    private final Map<String, SougenMap> maps = new HashMap<>();
    private final Logger logger;
    private final Map<Player, String> players = new HashMap<>();
    private final Map<Player, BukkitTask> playerAnimationTasks = new HashMap<>();
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

    public void addPlayerAnimation(Player player, String mapId, JavaPlugin plugin) {
        players.put(player, mapId);
        SougenMap map = maps.get(mapId);
        if (map != null) {
            animateRefreshPlayer(player, plugin, map, true);
        }
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
        cancelAnimation(player);
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

    public void removePlayerAnimation(Player player, JavaPlugin plugin) {
        cancelAnimation(player);
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

        animateRefreshPlayer(player, plugin, map, false);
    }

    public boolean containsPlayer(Player player) {
        return players.containsKey(player);
    }

    public boolean containsBlock(Player player, BlockPosition blockPosition) {
        String mapId = players.get(player);
        if (mapId == null)
            return false;

        SougenMap map = maps.get(mapId);
        if (map == null)
            return false;

        return map.schematic().positionList().contains(blockPosition);
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
        cancelAnimation(event.getPlayer());
        removePlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        cancelAnimation(event.getPlayer());
        refreshPlayer(event.getPlayer());
    }



    // test

    private interface BlockResolver<B> {
        B resolve(int index, BlockPosition blockPosition);

        static BlockResolver<BlockData> fromBlockDataList(List<BlockData> blockDataList) {
            return (index, _) -> blockDataList.get(index);
        }

        static BlockResolver<BlockState> fromWorld(World world) {
            return (_, bp) -> world.getBlockState(bp.x(), bp.y(), bp.z());
        }

    }

    private <B> Collection<Map<BlockPosition, B>> sortBlockAnimation(Location centerLocation, List<BlockPosition> posList, BlockResolver<B> blockResolver) {
        // 1. 距離ごとにブロックをグループ化する (TreeMapを使うと距離順に自動ソートされます)
        // キー: ユークリッド距離（実距離、または平方根を取らない距離のままでもグループ化可能ですが、今回は距離そのものをキーにします）
        Map<Double, Map<BlockPosition, B>> distanceGroups = new TreeMap<>();

        for (int i = 0; i < posList.size(); i++) {
            BlockPosition bp = posList.get(i);
            double distanceSq = Math.pow(bp.x() - centerLocation.x(), 2) +
                    Math.pow(bp.y() - centerLocation.y(), 2) +
                    Math.pow(bp.z() - centerLocation.z(), 2);

            // 実距離 (小数点以下を丸めてグループの解像度を調整することも可能。例: Math.floor(Math.sqrt(distanceSq)))
            double distance = Math.sqrt(distanceSq);

            // 距離が同じ（あるいは非常に近い）グループへ格納
            // ※ 浮動小数点の誤差が気になる場合は Math.round(distance * 10.0) / 10.0 などで丸めると綺麗にまとまります
//            double roundedDistance = Math.round(distance * 2.0) / 2.0; // 0.5ブロック単位で波の層を作る場合
            double roundedDistance = Math.round(distance * 0.75) / 0.75;
//            double roundedDistance = Math.round(distance * 1.0) / 1.0;

            distanceGroups.computeIfAbsent(roundedDistance, k -> new HashMap<>())
                    .put(bp, blockResolver.resolve(i, bp));
        }

        // グループ（距離のリスト）を返す
        return distanceGroups.values();
    }

    public void animateRefreshPlayer(Player player, JavaPlugin plugin, SougenMap map, boolean sougen) {
        if (!player.isOnline())
            return;

        if (sougen) {
            Collection<Map<BlockPosition, BlockData>> animateBlocks = sortBlockAnimation(
                    player.getLocation().toBlockLocation(),
                    map.schematic().positionList(),
                    BlockResolver.fromBlockDataList(map.schematic().blockDataList())
            );
            //noinspection UnstableApiUsage
            startAnimation(player, plugin, animateBlocks, m -> player.sendMultiBlockChange(BlockPosition.mapToPaper(m)));

        } else {
            Collection<Map<BlockPosition, BlockState>> animateBlocks = sortBlockAnimation(
                    player.getLocation(),
                    map.schematic().positionList(),
                    BlockResolver.fromWorld(player.getWorld())
            );
            startAnimation(player, plugin, animateBlocks, m -> player.sendBlockChanges(m.values()));
        }
    }

    private <E> void startAnimation(Player player, JavaPlugin plugin, Collection<E> collection, Consumer<E> execute) {
//        cancelAnimation(player);
        playerAnimationTasks.put(player, new BukkitRunnable() {
            private final Iterator<E> it = collection.iterator();
            @Override
            public void run() {
                if (!it.hasNext() || !player.isOnline()) {
                    cancel();
                } else {
                    execute.accept(it.next());
                }
            }
        }.runTaskTimer(plugin, 0, 1));
    }

    private void cancelAnimation(Player player) {
        BukkitTask task = playerAnimationTasks.remove(player);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

}
