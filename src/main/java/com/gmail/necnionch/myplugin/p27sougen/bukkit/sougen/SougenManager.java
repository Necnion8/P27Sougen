package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.config.SougenConfig;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.event.PlayerAddSougenEvent;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.event.PlayerEnterSougenEvent;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.event.PlayerLeaveSougenEvent;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.event.PlayerRemoveSougenEvent;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.Holograms;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.Portals;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SougenManager implements Listener {

    private static final float DEFAULT_THICKNESS = 2.0f;
    private static final BlockData STONE_BLOCK_DATA = Bukkit.createBlockData(Material.STONE);
    private static final BlockData AIR_BLOCK_DATA = Bukkit.createBlockData(Material.AIR);

    public static NamespacedKey PDC_ENTITY_MAP_ID;
    public static NamespacedKey PDC_WORLD_ENTITY_LIST_ID;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final SougenConfig config;
    private final Map<String, SougenMap> maps = new HashMap<>();
    private final Map<Player, String> players = new HashMap<>();
    private final ListMultimap<Player, Integer> playerAnimationTasks = ArrayListMultimap.create();
    private @Nullable PacketListener packetListener;

    public SougenManager(JavaPlugin plugin, Logger logger, SougenConfig config) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;
        PDC_ENTITY_MAP_ID = new NamespacedKey(plugin, "map_id");
        PDC_WORLD_ENTITY_LIST_ID = new NamespacedKey(plugin, "entities");
    }

    public Logger getLogger() {
        return logger;
    }

    public Map<String, SougenMap> getMaps() {
        return Collections.unmodifiableMap(maps);
    }

    public void addMap(String id, SougenSchematic schematic, UUID destWorldId, BlockPosition destPosition, SougenMap.Config config) {
        List<BlockPosition> positionList = schematic.positionList().stream()
                .map(bp -> new BlockPosition(destPosition.x() + bp.x(), destPosition.y() + bp.y(), destPosition.z() + bp.z()))
                .toList();
        SougenSchematic fixed = new SougenSchematic(positionList, schematic.blockDataList(), schematic.packetBlockDataList());
        SougenMap map = new SougenMap(id, fixed, destWorldId, destPosition, config);
        maps.put(id, map);
    }

    public void addPlayer(Player player, String mapId, boolean animate) {
        players.put(player, mapId);
        SougenMap map = maps.get(mapId);

        plugin.getServer().getPluginManager().callEvent(new PlayerAddSougenEvent(player, mapId, map));
        if (map != null) {
            plugin.getServer().getPluginManager().callEvent(new PlayerEnterSougenEvent(player, map));
        }

        if (!animate) {
            sendMap(player);
        } else if (map != null) {
            playMapApplyAnimation(player, map, DEFAULT_THICKNESS);
        }

        updateHologramsAndPortals(player);
    }

    public void sendMap(Player player) {
        sendMap(player, map -> IntStream.range(0, map.schematic().positionList().size())
                .boxed()
                .collect(Collectors.toMap(i -> map.schematic().positionList().get(i), i -> map.schematic().blockDataList().get(i))));
    }

    public void sendMap(Player player, SougenMap.ChunkKey chunk) {
        sendMap(player, map -> map.chunkCache().get(chunk).stream()
                .collect(Collectors.toMap(bp -> bp, bp -> map.schematic().blockDataList().get(map.schematic().positionList().indexOf(bp)))));
    }

    private void sendMap(Player player, Function<SougenMap, Map<BlockPosition, BlockData>> blocks) {
        if (!player.isOnline()) return;

        String mapId = players.get(player);
        if (mapId == null) return;

        SougenMap map = maps.get(mapId);
        if (map == null) return;

        World world = player.getWorld();
        if (!world.getUID().equals(player.getWorld().getUID())) return;

        //noinspection UnstableApiUsage
        player.sendMultiBlockChange(BlockPosition.mapToPaper(blocks.apply(map)));
    }

    public void removePlayer(Player player, boolean animate) {
        String mapId = players.remove(player);
        if (mapId == null) return;

        SougenMap map = maps.get(mapId);
        plugin.getServer().getPluginManager().callEvent(new PlayerRemoveSougenEvent(player, mapId, map));
        if (map == null) return;

        plugin.getServer().getPluginManager().callEvent(new PlayerLeaveSougenEvent(player, map));
        if (!player.isOnline()) return;

        if (!animate) {
            World mapWorld = plugin.getServer().getWorld(map.destWorldId());
            if (mapWorld != null) {
                List<Entity> taggedEntities = loadTaggedEntitiesIdFrom(mapWorld).stream()
                        .map(mapWorld::getEntity)
                        .filter(Objects::nonNull)
                        .toList();
                updateEntityVisibility(player, mapId, taggedEntities, false);
            }
        }
        updateHologramsAndPortals(player);

        World world = player.getWorld();
        if (!world.getUID().equals(map.destWorldId())) return;

        if (animate) {
            playMapRevertAnimation(player, map, DEFAULT_THICKNESS);
        } else {
            cancelAllAnimations(player);
            player.sendBlockChanges(map.schematic().positionList().stream()
                    .map(bp -> world.getBlockState(bp.x(), bp.y(), bp.z()))
                    .toList());
        }
    }

    public SougenMap getPlayerMap(Player player) {
        String mapId = players.get(player);
        return mapId != null ? maps.get(mapId) : null;
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

    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
        }
        clearAll();
    }

    private void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String mapId = players.get(player);
            if (mapId != null) {
                SougenMap map = maps.get(mapId);
                plugin.getServer().getPluginManager().callEvent(new PlayerRemoveSougenEvent(player, mapId, map));
                if (map != null) {
                    plugin.getServer().getPluginManager().callEvent(new PlayerLeaveSougenEvent(player, map));
                }
            }

            cancelAllAnimations(player);
            clearHologramsAndPortals(player);
        }

        for (SougenMap map : maps.values()) {
            World world = plugin.getServer().getWorld(map.destWorldId());
            if (world == null) continue;
            if (world.getPlayers().isEmpty()) continue;

            for (Player player : world.getPlayers()) {
                player.sendBlockChanges(map.schematic().positionList().stream()
                        .map(bp -> world.getBlockState(bp.x(), bp.y(), bp.z()))
                        .toList());
            }
            loadTaggedEntitiesFrom(world).forEach(entity -> world.getPlayers().forEach(p -> p.showEntity(plugin, entity)));
        }

        players.clear();
        maps.clear();
    }

    public void loadMaps() {
        clearAll();

        World world = Optional.ofNullable(config.getWorld())
                .map(name -> plugin.getServer().getWorld(name))
                .orElse(null);
        if (world == null) {
            getLogger().severe("World " + config.getWorld() + " not loaded!");
            return;
        }

        for (Map.Entry<String, SougenMap.Config> e : config.getMaps().entrySet()) {
            String mapId = e.getKey();
            SougenMap.Config mapConfig = e.getValue();

            if (!Files.isRegularFile(mapConfig.filePath())) {
                getLogger().warning("Schematic file not exists: " + mapConfig.filePath());
                continue;
            }
            SougenSchematic schematic;
            try {
                schematic = SougenSchematic.fromSchematicFile(mapConfig.filePath());
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "Failed to load schematic: " + mapConfig.filePath(), ex);
                continue;
            }

            addMap(mapId, schematic, world.getUID(), config.getCenter(), mapConfig);
        }

        List<Entity> taggedEntities = loadTaggedEntitiesFrom(world);
        for (Player player : world.getPlayers()) {
            updateEntityVisibility(player, players.get(player), taggedEntities, true);
            updateHologramsAndPortals(player);
        }
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
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String mapId = pdc.get(PDC_ENTITY_MAP_ID, PersistentDataType.STRING);

        if (mapId != null) {
            players.put(player, mapId);
        }

        sendMap(player);
        updateHologramsAndPortals(player);
        if (mapId == null) {
            // sougenなし
            List<Entity> taggedEntities = loadTaggedEntitiesIdFrom(world).stream()
                    .map(world::getEntity)
                    .filter(Objects::nonNull)
                    .toList();
            updateEntityVisibility(player, null, taggedEntities, true);
            return;
        }

        SougenMap map = maps.get(mapId);
        if (map == null) return;
        if (!map.destWorldId().equals(player.getWorld().getUID())) return;

        List<Entity> taggedEntities = loadTaggedEntitiesFrom(world);
        updateEntityVisibility(player, mapId, taggedEntities, true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        String mapId = players.get(player);
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (mapId != null) {
            pdc.set(PDC_ENTITY_MAP_ID, PersistentDataType.STRING, mapId);
        } else {
            pdc.remove(PDC_ENTITY_MAP_ID);
        }

        cancelAllAnimations(player);
        removePlayer(player, false);
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        cancelAllAnimations(event.getPlayer());
        removePlayer(event.getPlayer(), false);
    }

    @EventHandler
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        SougenMap map = maps.get(players.get(player));
        if (map == null) return;

        if (!map.destWorldId().equals(player.getWorld().getUID())) return;

        Chunk chunk = event.getChunk();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> sendMap(player, new SougenMap.ChunkKey(chunk.getX(), chunk.getZ())), 1L);
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

        List<AnimationWave<BlockData>> waves = new ArrayList<>(sortBlockAnimation(
                world, centerLoc, posList,
                BlockResolver.fromBlockDataList(map.schematic().blockDataList()),
                thickness
        ));

        startPipelinedAnimation(player, waves, (wave, isFinal, tick) -> {
            if (!isFinal) {
                //noinspection UnstableApiUsage
                player.sendMultiBlockChange(BlockPosition.mapToPaper(createStoneMap(wave.blocks(), BlockData::getMaterial)));
            } else {
                //noinspection UnstableApiUsage
                player.sendMultiBlockChange(BlockPosition.mapToPaper(wave.blocks()));
                if (!wave.entities().isEmpty()) {
                    updateEntityVisibility(player, map.id(), wave.entities(), true);
                }
                playBlockParticle(player, map, wave.blocks(), bd -> !bd.getMaterial().isAir());
                if (tick % 3 == 0) {
                    playBlockSound(player, wave, BlockData::getSoundGroup);
                }
            }
        });
    }

    public void playMapRevertAnimation(Player player, SougenMap map, double thickness) {
        if (!player.isOnline()) return;

        World world = player.getWorld();
        Location centerLoc = player.getLocation().toBlockLocation();
        List<BlockPosition> posList = map.schematic().positionList();

        List<AnimationWave<BlockState>> waves = new ArrayList<>(sortBlockAnimation(
                world, centerLoc, posList,
                BlockResolver.fromWorld(world),
                thickness
        ));

        startPipelinedAnimation(player, waves, (wave, isFinal, tick) -> {
            if (!isFinal) {
                //noinspection UnstableApiUsage
                player.sendMultiBlockChange(BlockPosition.mapToPaper(createStoneMap(wave.blocks(), BlockState::getType)));
            } else {
                Map<BlockPosition, BlockData> originalMap = wave.blocks().entrySet().stream()
                        .collect(Collectors.toMap(java.util.Map.Entry::getKey, entry -> entry.getValue().getBlockData()));
                //noinspection UnstableApiUsage
                player.sendMultiBlockChange(BlockPosition.mapToPaper(originalMap));
                if (!wave.entities().isEmpty()) {
                    updateEntityVisibility(player, map.id(), wave.entities(), false);
                }
                playBlockParticle(player, map, wave.blocks(), e -> !e.getType().isAir());
                if (tick % 3 == 0) {
                    playBlockSound(player, wave, bs -> bs.getBlockData().getSoundGroup());
                }
            }
        });
    }

    private class AnimationTask<B> extends BukkitRunnable {

        private static final int STONE_LAG = 6;
        private final Player player;
        private final List<AnimationWave<B>> waves;
        private final Executor<B> executor;
        private int tick = 0;
        private final int totalWaves;

        interface Executor<B> {
            void execute(AnimationWave<B> wave, boolean isFinal, int tick);
        }

        public AnimationTask(Player player, List<AnimationWave<B>> waves, Executor<B> executor) {
            this.player = player;
            this.waves = waves;
            this.executor = executor;
            this.totalWaves = waves.size();
        }

        @Override
        public void run() {
            // すべてのウェーブが石化を終え、かつ遅延分の処理も完了したら終了
            if (!player.isOnline() || (tick >= totalWaves + STONE_LAG)) {
                cancel();
                playerAnimationTasks.remove(player, getTaskId());
                return;
            }

            // 1. ラグ（遅延）分遅れて、本番ブロックにする（完了フェーズ）
            int finalIndex = tick - STONE_LAG;
            if (finalIndex >= 0 && finalIndex < totalWaves) {
                executor.execute(waves.get(finalIndex), true, tick);
            }

            // 2. 現在のウェーブを「石」にする（先行フェーズ）
            if (tick < totalWaves) {
                executor.execute(waves.get(tick), false, tick);
            }

            if (tick == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, .75f, 2f);
            }

            tick++;
        }
    }

    private <B> void startPipelinedAnimation(Player player, List<AnimationWave<B>> waves, AnimationTask.Executor<B> executor) {
        BukkitTask task = new AnimationTask<>(player, waves, executor).runTaskTimer(plugin, 0, 1);
        playerAnimationTasks.put(player, task.getTaskId());
    }

    private void updateEntityVisibility(Player player, @Nullable String mapId, List<Entity> entities, boolean apply) {
        for (Entity entity : entities) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            String entityMapId = pdc.getOrDefault(PDC_ENTITY_MAP_ID, PersistentDataType.STRING, "");
            boolean matchesMap = Optional.ofNullable(mapId).orElse("").equals(entityMapId);

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

    private <B> void playBlockSound(Player player, AnimationWave<B> wave, Function<B, SoundGroup> soundGroupFunction) {
        if (wave.blocks().isEmpty()) return;

        List<B> blocks = (wave.blocks() instanceof List) ? (List<B>) wave.blocks().values() : new ArrayList<>(wave.blocks().values());
        int index = ThreadLocalRandom.current().nextInt(blocks.size());
        SoundGroup soundGroup = soundGroupFunction.apply(blocks.get(index));
        player.playSound(
                player.getLocation(),
                soundGroup.getPlaceSound(),
                (float) (soundGroup.getVolume() * Math.min(1f, 4f / wave.radius())),
                soundGroup.getPitch()
        );
    }

    private <B> void playBlockParticle(Player player, SougenMap map, Map<BlockPosition, B> blockPositions, Predicate<B> filter) {
        List<BlockPosition> blocks = blockPositions.entrySet().stream()
                .filter(e -> map.destPosition().y() - 2 <= e.getKey().y())
                .filter(e -> filter.test(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        if (blocks.isEmpty()) return;

        for (int i = 0; i < blocks.size() / 20; i++) {
            int index = ThreadLocalRandom.current().nextInt(blocks.size());
            BlockPosition blockPosition = blocks.get(index);
            player.spawnParticle(
                    i % 2 == 0 ? Particle.FIREWORK : Particle.HAPPY_VILLAGER,
                    blockPosition.x() + 0.5,
                    blockPosition.y() + 1,
                    blockPosition.z() + 0.5,
                    1, 5, .5, 5, 0.1
            );

        }
    }

    private <B> Collection<AnimationWave<B>> sortBlockAnimation(
            World world, Location centerLocation, List<BlockPosition> posList,
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
                if (entity.getType() == EntityType.PLAYER) return;

                double distSq = entity.getLocation().distanceSquared(centerLocation);
                if (distSq >= innerRadiusSq && distSq <= outerRadiusSq) {
                    matchedEntities.add(entity);
                }
            });

            waves.add(new AnimationWave<>(blocks, matchedEntities, currentRadius));
        }

        return waves;
    }

    private <B> Map<BlockPosition, BlockData> createStoneMap(Map<BlockPosition, B> blocks, Function<B, Material> type) {
        return blocks.entrySet().stream()
                .collect(Collectors.toMap(java.util.Map.Entry::getKey, e -> type.apply(e.getValue()).isCollidable() ? STONE_BLOCK_DATA : AIR_BLOCK_DATA));
    }

    private record AnimationWave<B>(Map<BlockPosition, B> blocks, List<Entity> entities, double radius) {
    }

    private void cancelAllAnimations(Player player) {
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        for (Integer taskId : playerAnimationTasks.removeAll(player)) {
            scheduler.cancelTask(taskId);
        }
    }

    // --- Entity Tag ---

    public void addSougenEntity(World world, Entity entity, String mapId) {
        List<UUID> list = loadTaggedEntitiesIdFrom(world);
        if (!list.contains(entity.getUniqueId())) {
            list.add(entity.getUniqueId());
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(PDC_ENTITY_MAP_ID, PersistentDataType.STRING, mapId);
        saveTaggedEntitiesIdTo(world, list);
    }

    public void removeSougenEntity(World world, Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.remove(PDC_ENTITY_MAP_ID);

        List<UUID> list = loadTaggedEntitiesIdFrom(world);
        while (list.contains(entity.getUniqueId())) {
            list.remove(entity.getUniqueId());
        }
        saveTaggedEntitiesIdTo(world, list);
    }

    public @Nullable String getSougenEntityMapId(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(PDC_ENTITY_MAP_ID, PersistentDataType.STRING) ? pdc.get(PDC_ENTITY_MAP_ID, PersistentDataType.STRING) : null;
    }

    public List<Entity> loadTaggedEntitiesFrom(World world) {
        return loadTaggedEntitiesIdFrom(world).stream()
                .map(world::getEntity)
                .filter(Objects::nonNull)
                .filter(e -> e.getType() != EntityType.PLAYER)
                .toList();
    }

    public List<UUID> loadTaggedEntitiesIdFrom(World world) {
        PersistentDataContainer pdc = world.getPersistentDataContainer();
        List<UUID> uuidList = new ArrayList<>();

        long[] longArray = pdc.get(PDC_WORLD_ENTITY_LIST_ID, PersistentDataType.LONG_ARRAY);
        if (longArray == null) {
            return uuidList;
        }

        for (int i = 0; i < longArray.length; i += 2) {
            if (i + 1 < longArray.length) {
                long most = longArray[i];
                long least = longArray[i + 1];
                uuidList.add(new UUID(most, least));
            }
        }

        return uuidList;
    }

    public void saveTaggedEntitiesIdTo(World world, List<UUID> uuidList) {
        PersistentDataContainer pdc = world.getPersistentDataContainer();

        if (uuidList == null || uuidList.isEmpty()) {
            pdc.remove(PDC_WORLD_ENTITY_LIST_ID);
            return;
        }

        long[] longArray = new long[uuidList.size() * 2];

        for (int i = 0; i < uuidList.size(); i++) {
            UUID uuid = uuidList.get(i);
            longArray[i * 2] = uuid.getMostSignificantBits();
            longArray[i * 2 + 1] = uuid.getLeastSignificantBits();
        }

        pdc.set(PDC_WORLD_ENTITY_LIST_ID, PersistentDataType.LONG_ARRAY, longArray);
    }

    // --- Hologram & Portal ---

    /**
     * 指定されたプレイヤーの map 設定状態に基づいて、ホログラムとポータルの有効性を変更します
     */
    private void updateHologramsAndPortals(Player player) {
        SougenMap map = Optional.ofNullable(players.get(player)).map(maps::get).orElse(null);

        if (Holograms.available()) {
            for (String hologramId : config.getAllHologramIds()) {
                if (map != null && map.config().hologramIds().contains(hologramId)) {
                    Holograms.showHologram(player, hologramId);
                } else {
                    Holograms.hideHologram(player, hologramId);
                }
            }
        }

        if (Portals.available()) {
            Set<String> excludePortals = new HashSet<>(config.getAllPortalIds());
            if (map != null) {
                map.config().portalIds().forEach(excludePortals::remove);
            }
            Portals.blockedNames().replaceValues(player, excludePortals);
        }
    }

    /**
     * 指定されたプレイヤーに基づく、ホログラムとポータルの有効性変更をリセットします
     */
    private void clearHologramsAndPortals(Player player) {
        if (Holograms.available()) {
            config.getAllHologramIds().forEach(hologramId -> Holograms.showHologram(player, hologramId));
        }

        if (Portals.available()) {
            Portals.blockedNames().removeAll(player);
        }
    }

}