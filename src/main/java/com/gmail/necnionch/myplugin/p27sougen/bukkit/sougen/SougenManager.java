package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SougenManager implements Listener {

    public static NamespacedKey PDC_MAP_ID;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, SougenMap> maps = new HashMap<>();
    private final Map<Player, String> players = new HashMap<>();
    private final ListMultimap<Player, Integer> playerAnimationTasks = ArrayListMultimap.create();
    private PacketListener packetListener;

    public SougenManager(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        PDC_MAP_ID = new NamespacedKey(plugin, "map_id");
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

    public void addPlayer(Player player, String mapId, boolean animate) {
        players.put(player, mapId);
        if (animate) {
            SougenMap map = maps.get(mapId);
            if (map != null) {
                playMapApplyAnimation(player, map, 0.75);
            }
        } else {
            refreshPlayer(player);
        }
    }

    public void refreshPlayer(Player player) {
        if (!player.isOnline()) return;

        String mapId = players.get(player);
        if (mapId == null) return;

        SougenMap map = maps.get(mapId);
        if (map == null) return;

        World world = player.getWorld();
        if (!world.getUID().equals(player.getWorld().getUID())) return;

        //noinspection UnstableApiUsage
        player.sendMultiBlockChange(IntStream.range(0, map.schematic().positionList().size())
                .boxed()
                .collect(Collectors.toMap(i -> map.schematic().positionList().get(i).toPaper(), i -> map.schematic().blockDataList().get(i))));
    }

    public void removePlayer(Player player, boolean animate) {
        String mapId = players.remove(player);
        if (mapId == null) return;

        if (!player.isOnline()) return;

        SougenMap map = maps.get(mapId);
        if (map == null) return;

        World world = player.getWorld();
        if (!world.getUID().equals(map.destWorldId())) return;

        if (animate) {
            playMapRevertAnimation(player, map, 0.75);
        } else {
            cancelAllAnimations(player);
            player.sendBlockChanges(map.schematic().positionList().stream()
                    .map(bp -> world.getBlockState(bp.x(), bp.y(), bp.z()))
                    .toList());
        }
    }

    public boolean containsPlayer(Player player) {
        return players.containsKey(player);
    }

    public boolean containsBlock(Player player, BlockPosition blockPosition) {
        String mapId = players.get(player);
        if (mapId == null) return false;

        SougenMap map = maps.get(mapId);
        if (map == null) return false;

        return map.schematic().positionList().contains(blockPosition);
    }

    // --- Lifecycle / Events ---

    public void setup() {
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
        new ArrayList<>(players.keySet()).forEach(player -> removePlayer(player, false));
        players.clear();
        maps.clear();
    }

    private void onBlockChangeSending(PacketEvent event) {
        Player player = event.getPlayer();
        SougenMap map = maps.get(players.get(player));
        if (map == null) return;

        if (!map.destWorldId().equals(player.getWorld().getUID())) return;

        PacketContainer packet = event.getPacket();
        BlockPosition bp = BlockPosition.of(packet.getBlockPositionModifier().read(0));
        SougenSchematic schematic = map.schematic();

        int idx = schematic.positionList().indexOf(bp);
        if (idx == -1) return;

        WrappedBlockData blockData = schematic.packetBlockDataList().get(idx);
        packet.getBlockData().write(0, blockData);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelAllAnimations(event.getPlayer());
        removePlayer(event.getPlayer(), false);
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        cancelAllAnimations(event.getPlayer());
        removePlayer(event.getPlayer(), false);
    }

    // --- Animation & Entity Processing ---

    private interface BlockResolver<B> {
        B resolve(int index, BlockPosition blockPosition);

        static BlockResolver<BlockData> fromBlockDataList(List<BlockData> blockDataList) {
            return (index, _) -> blockDataList.get(index);
        }

        static BlockResolver<BlockState> fromWorld(World world) {
            return (_, bp) -> world.getBlockState(bp.x(), bp.y(), bp.z());
        }
    }

    public void playMapApplyAnimation(Player player, SougenMap map, double thickness) {
        if (!player.isOnline()) return;

        World world = player.getWorld();
        Location centerLoc = player.getLocation().toBlockLocation();
        List<BlockPosition> posList = map.schematic().positionList();

        Collection<AnimationWave<BlockData>> waves = sortBlockAnimation(
                world, centerLoc, player, posList,
                BlockResolver.fromBlockDataList(map.schematic().blockDataList()),
                thickness
        );

        startAnimation(player, waves, wave -> {
            //noinspection UnstableApiUsage
            player.sendMultiBlockChange(BlockPosition.mapToPaper(wave.blocks()));
            updateEntityVisibility(player, map.id(), wave.entities(), true);
        });
    }

    public void playMapRevertAnimation(Player player, SougenMap map, double thickness) {
        if (!player.isOnline()) return;

        World world = player.getWorld();
        Location centerLoc = player.getLocation().toBlockLocation();
        List<BlockPosition> posList = map.schematic().positionList();

        Collection<AnimationWave<BlockState>> waves = sortBlockAnimation(
                world, centerLoc, player, posList,
                BlockResolver.fromWorld(world),
                thickness
        );

        startAnimation(player, waves, wave -> {
            player.sendBlockChanges(wave.blocks().values());
            updateEntityVisibility(player, map.id(), wave.entities(), false);
        });
    }

    private void updateEntityVisibility(Player player, String mapId, List<Entity> entities, boolean apply) {
        for (Entity entity : entities) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            String entityMapId = pdc.getOrDefault(PDC_MAP_ID, PersistentDataType.STRING, "");
            boolean matchesMap = mapId.equals(entityMapId);

            if (apply) {
                if (entityMapId.isEmpty() || matchesMap) {
                    player.showEntity(plugin, entity);
                } else {
                    player.hideEntity(plugin, entity);
                }
            } else {
                if (matchesMap) {
                    player.hideEntity(plugin, entity);
                } else {
                    player.showEntity(plugin, entity);
                }
            }
        }
    }

    private <B> Collection<AnimationWave<B>> sortBlockAnimation(
            World world, Location centerLocation, Player player, List<BlockPosition> posList,
            BlockResolver<B> blockResolver, double thickness) {

        Map<Double, Map<BlockPosition, B>> distanceGroups = new TreeMap<>();

        for (int i = 0; i < posList.size(); i++) {
            BlockPosition bp = posList.get(i);
            double distanceSq = Math.pow(bp.x() - centerLocation.x(), 2) +
                    Math.pow(bp.y() - centerLocation.y(), 2) +
                    Math.pow(bp.z() - centerLocation.z(), 2);

            double distance = Math.sqrt(distanceSq);
            double roundedDistance = Math.round(distance / thickness) * thickness;

            distanceGroups.computeIfAbsent(roundedDistance, _ -> new HashMap<>())
                    .put(bp, blockResolver.resolve(i, bp));
        }

        List<AnimationWave<B>> waves = new ArrayList<>();

        for (Map.Entry<Double, Map<BlockPosition, B>> entry : distanceGroups.entrySet()) {
            double currentRadius = entry.getKey();
            Map<BlockPosition, B> blocks = entry.getValue();

            double outerRadius = currentRadius + thickness;
            double innerRadius = Math.max(0, currentRadius - thickness);
            double outerRadiusSq = outerRadius * outerRadius;
            double innerRadiusSq = innerRadius * innerRadius;

            BoundingBox scanBox = new BoundingBox(
                    centerLocation.getX() - outerRadius, centerLocation.getY() - outerRadius, centerLocation.getZ() - outerRadius,
                    centerLocation.getX() + outerRadius, centerLocation.getY() + outerRadius, centerLocation.getZ() + outerRadius
            );

            List<Entity> matchedEntities = new ArrayList<>();
            world.getNearbyEntities(scanBox).forEach(entity -> {
                if (entity.equals(player)) return;

                double distSq = entity.getLocation().distanceSquared(centerLocation);
                if (distSq >= innerRadiusSq && distSq <= outerRadiusSq) {
                    matchedEntities.add(entity);
                }
            });

            waves.add(new AnimationWave<>(blocks, matchedEntities));
        }

        return waves;
    }

    private record AnimationWave<B>(Map<BlockPosition, B> blocks, List<Entity> entities) {
    }

    private <E> void startAnimation(Player player, Collection<E> collection, Consumer<E> execute) {
        BukkitTask task = new BukkitRunnable() {
            private final Iterator<E> it = collection.iterator();

            @Override
            public void run() {
                if (!it.hasNext() || !player.isOnline()) {
                    cancel();
                    playerAnimationTasks.remove(player, getTaskId());
                } else {
                    execute.accept(it.next());
                }
            }
        }.runTaskTimer(plugin, 0, 1);

        playerAnimationTasks.put(player, task.getTaskId());
    }

    private void cancelAllAnimations(Player player) {
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        for (Integer taskId : playerAnimationTasks.removeAll(player)) {
            scheduler.cancelTask(taskId);
        }
    }

}