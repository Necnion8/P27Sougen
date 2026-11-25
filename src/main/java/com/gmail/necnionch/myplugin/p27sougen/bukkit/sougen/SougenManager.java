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
import org.bukkit.block.TileState;
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
    private static final Set<Material> HEAD_MATERIALS = Arrays.stream(Material.values()).filter(m -> m.name().endsWith("_HEAD") || m.name().endsWith("_SKULL")).collect(Collectors.toSet());
    private static final Predicate<Material> IS_BLOCK_PREVIEW = type ->
            type.isCollidable() && Material.LILY_PAD != type && !HEAD_MATERIALS.contains(type);

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
        String prevMapId = players.put(player, mapId);
        SougenMap prevMap = (prevMapId != null && !prevMapId.equals(mapId)) ? maps.get(prevMapId) : null;
        SougenMap map = maps.get(mapId);

        plugin.getServer().getPluginManager().callEvent(new PlayerAddSougenEvent(player, mapId, map));
        if (map != null) {
            plugin.getServer().getPluginManager().callEvent(new PlayerEnterSougenEvent(player, map));
        }

        if (!animate) {
            if (prevMap != null) {
                revertExclusiveBlocks(player, prevMap, map);
            }
            sendMap(player);
            if (map != null) {
                World mapWorld = plugin.getServer().getWorld(map.destWorldId());
                if (mapWorld != null) {
                    List<Entity> taggedEntities = loadTaggedEntitiesIdFrom(mapWorld).stream()
                            .map(mapWorld::getEntity)
                            .filter(Objects::nonNull)
                            .toList();
                    updateEntityVisibility(player, mapId, taggedEntities, true);
                }
            }
        } else if (map != null) {
            playMapApplyAnimation(player, map, prevMap, DEFAULT_THICKNESS);
        }

        updateHologramsAndPortals(player);
    }

    private void revertExclusiveBlocks(Player player, SougenMap prevMap, @Nullable SougenMap nextMap) {
        if (!player.isOnline()) return;

        World world = player.getWorld();
        if (!world.getUID().equals(prevMap.destWorldId())) return;

        Set<BlockPosition> nextPositions = (nextMap != null) ? new HashSet<>(nextMap.schematic().positionList()) : Collections.emptySet();

        Map<BlockPosition, SougenBlockEntry> entriesToRevert = new LinkedHashMap<>();
        for (BlockPosition bp : prevMap.schematic().positionList()) {
            if (!nextPositions.contains(bp)) {
                entriesToRevert.put(bp, SougenBlockEntry.fromWorld(world, bp, true, false));
            }
        }

        if (entriesToRevert.isEmpty()) return;

        Map<BlockPosition, BlockData> blocksToRevert = entriesToRevert.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().blockData()));

        //noinspection UnstableApiUsage
        player.sendMultiBlockChange(BlockPosition.mapToPaper(blocksToRevert));

        for (Map.Entry<BlockPosition, SougenBlockEntry> e : entriesToRevert.entrySet()) {
            TileState tileState = e.getValue().tileState();
            if (tileState != null) {
                BlockPosition pos = e.getKey();
                //noinspection UnstableApiUsage
                player.sendBlockUpdate(new Location(world, pos.x(), pos.y(), pos.z()), tileState);
            }
        }
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
        if (!world.getUID().equals(map.destWorldId())) return;

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
                updateEntityVisibility(player, null, taggedEntities, true);
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

    public @Nullable SougenMap getPlayerMap(Player player) {
        String mapId = players.get(player);
        return mapId != null ? maps.get(mapId) : null;
    }

    public boolean containsPlayer(Player player) {
        return players.containsKey(player);
    }

    public boolean containsPlayerInMap(Player player) {
        SougenMap map = getPlayerMap(player);
        if (map == null) return false;
        return player.getWorld().getUID().equals(map.destWorldId());
    }

    public boolean containsPlayerInMapWorld(Player player) {
        return player.getWorld().getName().equals(config.getWorld());
    }

    public boolean containsBlock(Player player, BlockPosition blockPosition) {
        String mapId = players.get(player);
        if (mapId == null) return false;

        SougenMap map = maps.get(mapId);
        if (map == null) return false;

        return map.schematic().positionList().contains(blockPosition);
    }

    public @Nullable BlockData getBlockDataInMap(Player player, BlockPosition blockPosition) {
        String mapId = players.get(player);
        if (mapId == null) return null;

        SougenMap map = maps.get(mapId);
        if (map == null) return null;

        int index = map.schematic().positionList().indexOf(blockPosition);
        if (index == -1) return null;
        return map.schematic().blockDataList().get(index);
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
        startPortalProximityTask();
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
        }
        clearAll();
    }

    private void clearAll() {
        stopPortalProximityTasks();
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
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String mapId = pdc.get(PDC_ENTITY_MAP_ID, PersistentDataType.STRING);

        if (mapId != null) {
            players.put(player, mapId);
        }

        handlePlayerChangedWorld(player);
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
        handlePlayerChangedWorld(event.getPlayer());
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

    private void handlePlayerChangedWorld(Player player) {
        cancelAllAnimations(player);
        World world = player.getWorld();

        String mapId = players.get(player);
        if (mapId == null) {
            mapId = config.getAutoMapId();
            if (mapId != null) {
                players.put(player, mapId);
            }
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

    public record SougenBlockEntry(
            BlockData blockData,
            @Nullable TileState tileState,
            boolean revertFromWorld,
            boolean playEffects
    ) {
        public BlockData previewBlockData() {
            return IS_BLOCK_PREVIEW.test(blockData.getMaterial()) ? STONE_BLOCK_DATA : AIR_BLOCK_DATA;
        }

        public static SougenBlockEntry ofSchematic(BlockData blockData, boolean playEffects) {
            return new SougenBlockEntry(blockData, null, false, playEffects);
        }

        public static SougenBlockEntry fromWorld(World world, BlockPosition pos, boolean revertFromWorld, boolean playEffects) {
            BlockState state = world.getBlockState(pos.x(), pos.y(), pos.z());
            TileState tile = (state instanceof TileState ts) ? ts : null;
            return new SougenBlockEntry(state.getBlockData(), tile, revertFromWorld, playEffects);
        }
    }

    @FunctionalInterface
    private interface BlockEntryResolver {
        SougenBlockEntry resolve(int index, BlockPosition pos);
    }

    // --- Animation & Entity Processing ---

    public void playMapApplyAnimation(Player player, SougenMap map, @Nullable SougenMap prevMap, double thickness) {
        if (!player.isOnline()) return;

        World world = player.getWorld();
        List<BlockPosition> nextPosList = map.schematic().positionList();
        List<BlockData> nextDataList = map.schematic().blockDataList();
        Set<BlockPosition> nextPosSet = new HashSet<>(nextPosList);

        List<BlockPosition> positions = new ArrayList<>(nextPosList);
        if (prevMap != null && world.getUID().equals(prevMap.destWorldId())) {
            for (BlockPosition bp : prevMap.schematic().positionList()) {
                if (!nextPosSet.contains(bp)) {
                    positions.add(bp);
                }
            }
        }

        int nextSize = nextPosList.size();
        BlockEntryResolver resolver = (index, bp) -> {
            if (index < nextSize) {
                return SougenBlockEntry.ofSchematic(nextDataList.get(index), true);
            }
            return SougenBlockEntry.fromWorld(world, bp, true, false);
        };

        playBlockAnimation(player, world, map, positions, resolver, thickness, true);
    }

    public void playMapRevertAnimation(Player player, SougenMap map, double thickness) {
        if (!player.isOnline()) return;

        World world = player.getWorld();
        List<BlockPosition> positions = new ArrayList<>(map.schematic().positionList());
        BlockEntryResolver resolver = (_, bp) -> SougenBlockEntry.fromWorld(world, bp, true, true);

        playBlockAnimation(player, world, map, positions, resolver, thickness, false);
    }

    private class AnimationTask extends BukkitRunnable {

        private static final int STONE_LAG = 6;
        private final Player player;
        private final List<AnimationWave> waves;
        private final PreviewExecutor onPreview;
        private final CommitExecutor onCommit;
        private final int totalWaves;
        private int tick = 0;

        @FunctionalInterface
        interface PreviewExecutor {
            void execute(AnimationWave wave);
        }

        @FunctionalInterface
        interface CommitExecutor {
            void execute(AnimationWave wave, int tick);
        }

        public AnimationTask(Player player, List<AnimationWave> waves, PreviewExecutor onPreview, CommitExecutor onCommit) {
            this.player = player;
            this.waves = waves;
            this.onPreview = onPreview;
            this.onCommit = onCommit;
            this.totalWaves = waves.size();
        }

        @Override
        public void run() {
            if (!player.isOnline() || tick >= totalWaves + STONE_LAG) {
                cancel();
                playerAnimationTasks.remove(player, getTaskId());
                return;
            }

            int finalIndex = tick - STONE_LAG;
            if (finalIndex >= 0 && finalIndex < totalWaves) {
                onCommit.execute(waves.get(finalIndex), tick);
            }

            if (tick < totalWaves) {
                onPreview.execute(waves.get(tick));
            }

            if (tick == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, .75f, 2f);
            }

            tick++;
        }
    }

    private void playBlockAnimation(
            Player player,
            World world,
            SougenMap map,
            List<BlockPosition> positions,
            BlockEntryResolver resolver,
            double thickness,
            boolean showEntitiesOnApply
    ) {
        Location centerLoc = player.getLocation().toBlockLocation();
        List<AnimationWave> waves = buildAnimationWaves(world, centerLoc, positions, resolver, thickness);

        AnimationTask task = new AnimationTask(
                player,
                waves,
                wave -> {
                    if (!wave.previewBlocks().isEmpty()) {
                        //noinspection UnstableApiUsage
                        player.sendMultiBlockChange(BlockPosition.mapToPaper(wave.previewBlocks()));
                    }
                },
                (wave, tick) -> {
                    if (!wave.commitBlocks().isEmpty()) {
                        //noinspection UnstableApiUsage
                        player.sendMultiBlockChange(BlockPosition.mapToPaper(wave.commitBlocks()));
                    }

                    // sendMultiBlockChange の直後に TileState を同期
                    for (Map.Entry<BlockPosition, SougenBlockEntry> e : wave.entries().entrySet()) {
                        TileState tileState = e.getValue().tileState();
                        if (tileState != null) {
                            BlockPosition pos = e.getKey();
                            //noinspection UnstableApiUsage
                            player.sendBlockUpdate(new Location(world, pos.x(), pos.y(), pos.z()), tileState);
                        }
                    }

                    if (!wave.entities().isEmpty()) {
                        updateEntityVisibility(player, map.id(), wave.entities(), showEntitiesOnApply);
                    }

                    Map<BlockPosition, BlockData> effectBlocks = wave.entries().entrySet().stream()
                            .filter(e -> e.getValue().playEffects())
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().blockData()));

                    if (!effectBlocks.isEmpty()) {
                        playBlockParticle(player, map, effectBlocks);
                        if (tick % 3 == 0) {
                            playBlockSound(player, effectBlocks, wave.radius());
                        }
                    }
                }
        );

        BukkitTask scheduled = task.runTaskTimer(plugin, 0L, 1L);
        playerAnimationTasks.put(player, scheduled.getTaskId());
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

    private void playBlockSound(Player player, Map<BlockPosition, BlockData> blocks, double radius) {
        if (blocks.isEmpty()) return;

        List<BlockData> values = new ArrayList<>(blocks.values());
        SoundGroup soundGroup = values.get(ThreadLocalRandom.current().nextInt(values.size())).getSoundGroup();
        player.playSound(
                player.getLocation(),
                soundGroup.getPlaceSound(),
                (float) (soundGroup.getVolume() * Math.min(1.0, 4.0 / Math.max(0.1, radius))),
                soundGroup.getPitch()
        );
    }

    private void playBlockParticle(Player player, SougenMap map, Map<BlockPosition, BlockData> blocks) {
        List<BlockPosition> nonAirBlocks = blocks.entrySet().stream()
                .filter(e -> map.destPosition().y() - 2 <= e.getKey().y())
                .filter(e -> !e.getValue().getMaterial().isAir())
                .map(java.util.Map.Entry::getKey)
                .toList();
        if (nonAirBlocks.isEmpty()) return;

        for (int i = 0; i < nonAirBlocks.size() / 20; i++) {
            int index = ThreadLocalRandom.current().nextInt(nonAirBlocks.size());
            BlockPosition blockPosition = nonAirBlocks.get(index);
            player.spawnParticle(
                    i % 2 == 0 ? Particle.FIREWORK : Particle.HAPPY_VILLAGER,
                    blockPosition.x() + 0.5,
                    blockPosition.y() + 1,
                    blockPosition.z() + 0.5,
                    1, 5, .5, 5, 0.1
            );

        }
    }

    private List<AnimationWave> buildAnimationWaves(
            World world,
            Location centerLocation,
            List<BlockPosition> posList,
            BlockEntryResolver resolver,
            double thickness
    ) {
        Map<Double, Map<BlockPosition, SougenBlockEntry>> distanceGroups = new TreeMap<>();

        for (int i = 0; i < posList.size(); i++) {
            BlockPosition bp = posList.get(i);
            double distanceSq = Math.pow(bp.x() - centerLocation.x(), 2) +
                    Math.pow(bp.y() - centerLocation.y(), 2) +
                    Math.pow(bp.z() - centerLocation.z(), 2);

            double distance = Math.sqrt(distanceSq);
            double roundedDistance = Math.round(distance / thickness) * thickness;

            distanceGroups.computeIfAbsent(roundedDistance, _ -> new HashMap<>())
                    .put(bp, resolver.resolve(i, bp));
        }

        List<AnimationWave> waves = new ArrayList<>(distanceGroups.size());

        for (Map.Entry<Double, Map<BlockPosition, SougenBlockEntry>> entry : distanceGroups.entrySet()) {
            double currentRadius = entry.getKey();
            Map<BlockPosition, SougenBlockEntry> waveEntries = entry.getValue();

            double outerRadius = currentRadius + thickness;
            double innerRadius = Math.max(0.0, currentRadius - thickness);
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

            waves.add(AnimationWave.of(waveEntries, matchedEntities, currentRadius));
        }

        return waves;
    }

    private record AnimationWave(
            Map<BlockPosition, SougenBlockEntry> entries,
            Map<BlockPosition, BlockData> previewBlocks,
            Map<BlockPosition, BlockData> commitBlocks,
            List<Entity> entities,
            double radius
    ) {
        static AnimationWave of(Map<BlockPosition, SougenBlockEntry> entries, List<Entity> entities, double radius) {
            Map<BlockPosition, BlockData> preview = new HashMap<>(entries.size());
            Map<BlockPosition, BlockData> commit = new HashMap<>(entries.size());

            for (Map.Entry<BlockPosition, SougenBlockEntry> e : entries.entrySet()) {
                BlockPosition pos = e.getKey();
                SougenBlockEntry entry = e.getValue();
                preview.put(pos, entry.previewBlockData());
                commit.put(pos, entry.blockData());
            }

            return new AnimationWave(entries, preview, commit, entities, radius);
        }
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

    // --- Portal Proximity Reveal via Active Player ---

    private static final String TEST_PORTAL_ID = "test_portal";

    // ポータル本体範囲（y=62 から開始）
    private static final BlockPosition PORTAL_MIN = new BlockPosition(118, 62, 50);
    private static final BlockPosition PORTAL_MAX = new BlockPosition(122, 67, 50);

    // 地面の高さと、距離に応じて広がる最大半径
    private static final int GROUND_Y = 62;
    private static final double GROUND_MAX_RADIUS = 4.0;

    private static final double PORTAL_REVEAL_MAX_DIST = 2.0;
    private static final double PORTAL_REVEAL_MIN_DIST = 1.0;
    private static final double Y_DISTANCE_WEIGHT = 1.0 / 2.0;

    // 徐々に離れた場合の遅延（tick）
    private static final long PORTAL_REVERT_DELAY_TICKS = 10L;
    private static final long PORTAL_REVERT_TOTAL_TICKS = 10L;

    // 1 tick あたりの移動量がこの値以上なら「一気に離れた（ワープ等）」とみなす
    private static final double ABRUPT_MOVE_DISTANCE_THRESHOLD = 3.5;

    // 一気に離れた場合の遅延（tick）
    private static final long ABRUPT_REVERT_DELAY_TICKS = 20 * 2L;
    private static final long ABRUPT_REVERT_TOTAL_TICKS = 10L;

    private @Nullable BukkitTask portalRevealTask;
    private final Map<Player, Map<BlockPosition, BlockData>> revealedPortalBlocksByViewer = new HashMap<>();
    private final Map<Player, Map<BlockPosition, BukkitTask>> pendingRevertTasksByViewer = new HashMap<>();

    private final Map<UUID, Location> lastActorLocations = new HashMap<>();
    private final Map<UUID, Double> lastActorProgress = new HashMap<>();
    private final Set<UUID> previousVisibleActorIds = new HashSet<>();

    private record ApproachEvaluation(double maxProgress, boolean departedAbruptly) {
    }

    public void startPortalProximityTask() {
        stopPortalProximityTasks();

        portalRevealTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = Optional.ofNullable(config.getWorld())
                    .map(name -> plugin.getServer().getWorld(name))
                    .orElse(null);
            if (world == null) return;

            List<Player> worldPlayers = world.getPlayers().stream()
                    .filter(Player::isOnline)
                    .toList();
            if (worldPlayers.isEmpty()) return;

            @Nullable SougenMap portalSourceMap = findPortalSourceMap(world, TEST_PORTAL_ID);
            if (portalSourceMap == null) return;

            List<BlockPosition> portalBlocks = collectBlockPositions(PORTAL_MIN, PORTAL_MAX);

            double portalCenterX = (PORTAL_MIN.x() + PORTAL_MAX.x() + 1) / 2.0;
            double portalCenterY = (PORTAL_MIN.y() + PORTAL_MAX.y() + 1) / 2.0;
            double portalCenterZ = (PORTAL_MIN.z() + PORTAL_MAX.z() + 1) / 2.0;
            Location portalCenter = new Location(world, portalCenterX, portalCenterY, portalCenterZ);

            // 1. Aマップ側の接近状況（最大進行度と、一気に離れたか）を評価
            ApproachEvaluation approach = evaluateActorApproach(world, worldPlayers, portalBlocks);
            double progress = approach.maxProgress();
            boolean departedAbruptly = approach.departedAbruptly();

            // 2. ポータル本体 + 距離に応じた地面（y = GROUND_Y）の BlockData マップを構築
            Map<BlockPosition, BlockData> targetBlockDataMap = buildTargetBlockDataMap(
                    portalSourceMap,
                    world,
                    portalCenterX,
                    portalCenterZ,
                    progress
            );

            List<BlockPosition> sortedTargets = getPositionsSortedByCenter(targetBlockDataMap.keySet(), portalCenter);

            // 3. ポータルが見えていない側（Bマップなど）に反映
            for (Player viewer : worldPlayers) {
                if (isPortalVisibleToPlayer(viewer, TEST_PORTAL_ID)) {
                    resetPortalPreview(viewer, world, false, departedAbruptly);
                    continue;
                }
                updatePortalPreviewForViewer(
                        viewer,
                        world,
                        sortedTargets,
                        portalCenter,
                        targetBlockDataMap,
                        progress,
                        departedAbruptly
                );
            }
        }, 3L, 3L);
    }

    private void stopPortalProximityTasks() {
        if (portalRevealTask != null) {
            portalRevealTask.cancel();
            portalRevealTask = null;
        }

        for (Player viewer : new ArrayList<>(revealedPortalBlocksByViewer.keySet())) {
            resetPortalPreview(viewer, viewer.getWorld(), true, true);
        }
        revealedPortalBlocksByViewer.clear();
        cancelAllPendingReverts();
        lastActorLocations.clear();
        lastActorProgress.clear();
        previousVisibleActorIds.clear();
    }

    private ApproachEvaluation evaluateActorApproach(
            World world,
            List<Player> worldPlayers,
            List<BlockPosition> portalBlocks
    ) {
        double maxProgress = 0.0;
        boolean departedAbruptly = false;
        Set<UUID> currentVisibleActorIds = new HashSet<>();

        for (Player actor : worldPlayers) {
            if (!isPortalVisibleToPlayer(actor, TEST_PORTAL_ID)) continue;

            UUID uuid = actor.getUniqueId();
            currentVisibleActorIds.add(uuid);

            Location currLoc = actor.getLocation();
            double dist = calculateNearestPortalDistance(currLoc, portalBlocks);
            double currProgress = calculateRevealProgress(dist);
            maxProgress = Math.max(maxProgress, currProgress);

            Location prevLoc = lastActorLocations.get(uuid);
            double prevProgress = lastActorProgress.getOrDefault(uuid, 0.0);

            if (prevLoc != null && prevProgress > 0.15 && currProgress < prevProgress) {
                if (prevLoc.getWorld() == null || !prevLoc.getWorld().getUID().equals(world.getUID())) {
                    departedAbruptly = true;
                } else if (prevLoc.distance(currLoc) >= ABRUPT_MOVE_DISTANCE_THRESHOLD) {
                    departedAbruptly = true;
                }
            }

            lastActorLocations.put(uuid, currLoc.clone());
            lastActorProgress.put(uuid, currProgress);
        }

        for (UUID prevId : previousVisibleActorIds) {
            if (!currentVisibleActorIds.contains(prevId)) {
                double prevProgress = lastActorProgress.getOrDefault(prevId, 0.0);
                if (prevProgress > 0.15) {
                    departedAbruptly = true;
                }
            }
        }

        previousVisibleActorIds.clear();
        previousVisibleActorIds.addAll(currentVisibleActorIds);
        lastActorLocations.keySet().retainAll(currentVisibleActorIds);
        lastActorProgress.keySet().retainAll(currentVisibleActorIds);

        return new ApproachEvaluation(maxProgress, departedAbruptly);
    }

    private void updatePortalPreviewForViewer(
            Player viewer,
            World world,
            List<BlockPosition> sortedTargetsFromCenter,
            Location portalCenter,
            Map<BlockPosition, BlockData> portalBlockDataMap,
            double progress,
            boolean departedAbruptly
    ) {
        int targetCount = (int) Math.round(sortedTargetsFromCenter.size() * progress);
        Set<BlockPosition> desiredPositions = new HashSet<>(
                sortedTargetsFromCenter.subList(0, Math.min(targetCount, sortedTargetsFromCenter.size()))
        );
        Map<BlockPosition, BlockData> previousRevealed = revealedPortalBlocksByViewer.computeIfAbsent(viewer, _ -> new HashMap<>());

        Map<BlockPosition, BlockData> toShow = new HashMap<>();
        for (BlockPosition bp : desiredPositions) {
            cancelPendingRevert(viewer, bp);

            if (!previousRevealed.containsKey(bp)) {
                BlockData data = portalBlockDataMap.get(bp);
                if (data != null) {
                    toShow.put(bp, data);
                    previousRevealed.put(bp, data);
                }
            }
        }

        if (!toShow.isEmpty()) {
            //noinspection UnstableApiUsage
            viewer.sendMultiBlockChange(BlockPosition.mapToPaper(toShow));
        }

        List<BlockPosition> removedPositions = previousRevealed.keySet().stream()
                .filter(bp -> !desiredPositions.contains(bp))
                .sorted(Comparator
                        .comparingInt(BlockPosition::y).reversed()
                        .thenComparing(Comparator.comparingDouble((BlockPosition bp) -> distanceSqToCenter(bp, portalCenter)).reversed()))
                .toList();

        long baseDelay = departedAbruptly ? ABRUPT_REVERT_DELAY_TICKS : PORTAL_REVERT_DELAY_TICKS;
        long totalTicks = departedAbruptly ? ABRUPT_REVERT_TOTAL_TICKS : PORTAL_REVERT_TOTAL_TICKS;

        int count = removedPositions.size();
        for (int i = 0; i < count; i++) {
            BlockPosition pos = removedPositions.get(i);
            long spreadTicks = (count <= 1)
                    ? 0L
                    : Math.round((totalTicks * i) / (double) (count - 1));
            BlockData shownData = previousRevealed.get(pos);
            scheduleRevertBlock(viewer, world, pos, shownData, baseDelay + spreadTicks);
        }
    }

    private void scheduleRevertBlock(
            Player viewer,
            World world,
            BlockPosition pos,
            @Nullable BlockData previousShownData,
            long delayTicks
    ) {
        Map<BlockPosition, BukkitTask> tasks = pendingRevertTasksByViewer.computeIfAbsent(viewer, _ -> new HashMap<>());
        if (tasks.containsKey(pos)) {
            return;
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Map<BlockPosition, BukkitTask> viewerTasks = pendingRevertTasksByViewer.get(viewer);
            if (viewerTasks != null) {
                viewerTasks.remove(pos);
                if (viewerTasks.isEmpty()) {
                    pendingRevertTasksByViewer.remove(viewer);
                }
            }

            Map<BlockPosition, BlockData> currentRevealed = revealedPortalBlocksByViewer.get(viewer);
            if (currentRevealed != null) {
                currentRevealed.remove(pos);
                if (currentRevealed.isEmpty()) {
                    revealedPortalBlocksByViewer.remove(viewer);
                }
            }

            if (!viewer.isOnline() || !viewer.getWorld().getUID().equals(world.getUID())) {
                return;
            }

            BlockData viewerMapData = getViewerMapBlockData(viewer, world, pos);
            BlockData revertedToData;
            if (viewerMapData != null) {
                //noinspection UnstableApiUsage
                viewer.sendMultiBlockChange(BlockPosition.mapToPaper(Map.of(pos, viewerMapData)));
                revertedToData = viewerMapData;
            } else {
                BlockState state = world.getBlockState(pos.x(), pos.y(), pos.z());
                viewer.sendBlockChanges(List.of(state));
                revertedToData = state.getBlockData();
            }

            if (revertedToData.getMaterial().isAir()
                    && previousShownData != null
                    && !previousShownData.getMaterial().isAir()) {
                viewer.spawnParticle(
                        Particle.BLOCK,
                        pos.x() + 0.5,
                        pos.y() + 0.5,
                        pos.z() + 0.5,
                        12,
                        0.25,
                        0.25,
                        0.25,
                        0.05,
                        previousShownData
                );
            }
        }, Math.max(0L, delayTicks));

        tasks.put(pos, task);
    }

    private void resetPortalPreview(Player viewer, World world, boolean immediate, boolean departedAbruptly) {
        cancelAllPendingRevertsForViewer(viewer);

        Map<BlockPosition, BlockData> previousRevealed = revealedPortalBlocksByViewer.remove(viewer);
        if (previousRevealed == null || previousRevealed.isEmpty()) return;
        if (!viewer.isOnline() || !viewer.getWorld().getUID().equals(world.getUID())) return;

        if (immediate) {
            Map<BlockPosition, BlockData> mapBlocksToRestore = new HashMap<>();
            List<BlockState> worldBlocksToRestore = new ArrayList<>();

            for (BlockPosition bp : previousRevealed.keySet()) {
                BlockData viewerMapData = getViewerMapBlockData(viewer, world, bp);
                if (viewerMapData != null) {
                    mapBlocksToRestore.put(bp, viewerMapData);
                } else {
                    worldBlocksToRestore.add(world.getBlockState(bp.x(), bp.y(), bp.z()));
                }
            }

            if (!mapBlocksToRestore.isEmpty()) {
                //noinspection UnstableApiUsage
                viewer.sendMultiBlockChange(BlockPosition.mapToPaper(mapBlocksToRestore));
            }
            if (!worldBlocksToRestore.isEmpty()) {
                viewer.sendBlockChanges(worldBlocksToRestore);
            }
        } else {
            long baseDelay = departedAbruptly ? ABRUPT_REVERT_DELAY_TICKS : PORTAL_REVERT_DELAY_TICKS;
            for (Map.Entry<BlockPosition, BlockData> entry : previousRevealed.entrySet()) {
                scheduleRevertBlock(viewer, world, entry.getKey(), entry.getValue(), baseDelay);
            }
        }
    }

    private void cancelAllPendingReverts() {
        for (Map<BlockPosition, BukkitTask> tasks : pendingRevertTasksByViewer.values()) {
            for (BukkitTask task : tasks.values()) {
                if (task != null) {
                    task.cancel();
                }
            }
        }
        pendingRevertTasksByViewer.clear();
    }

    private void cancelPendingRevert(Player viewer, BlockPosition pos) {
        Map<BlockPosition, BukkitTask> tasks = pendingRevertTasksByViewer.get(viewer);
        if (tasks == null) return;
        BukkitTask task = tasks.remove(pos);
        if (task != null) {
            task.cancel();
        }
        if (tasks.isEmpty()) {
            pendingRevertTasksByViewer.remove(viewer);
        }
    }

    private void cancelAllPendingRevertsForViewer(Player viewer) {
        Map<BlockPosition, BukkitTask> tasks = pendingRevertTasksByViewer.remove(viewer);
        if (tasks == null) return;
        for (BukkitTask task : tasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
    }

    private Map<BlockPosition, BlockData> buildTargetBlockDataMap(
            SougenMap sourceMap,
            World world,
            double portalCenterX,
            double portalCenterZ,
            double progress
    ) {
        Map<BlockPosition, BlockData> schematicIndexMap = buildSchematicBlockMap(sourceMap.schematic());

        Set<BlockPosition> candidatePositions = new LinkedHashSet<>(collectBlockPositions(PORTAL_MIN, PORTAL_MAX));

        double currentGroundRadius = GROUND_MAX_RADIUS * progress;
        if (currentGroundRadius > 0.0) {
            int ceilR = (int) Math.ceil(currentGroundRadius);
            int baseX = (int) Math.floor(portalCenterX);
            int baseZ = (int) Math.floor(portalCenterZ);
            double radiusSq = currentGroundRadius * currentGroundRadius;

            for (int x = baseX - ceilR; x <= baseX + ceilR; x++) {
                for (int z = baseZ - ceilR; z <= baseZ + ceilR; z++) {
                    double dx = (x + 0.5) - portalCenterX;
                    double dz = (z + 0.5) - portalCenterZ;
                    if (dx * dx + dz * dz <= radiusSq) {
                        candidatePositions.add(new BlockPosition(x, GROUND_Y, z));
                    }
                }
            }
        }

        Map<BlockPosition, BlockData> result = new LinkedHashMap<>();
        for (BlockPosition bp : candidatePositions) {
            BlockData data = schematicIndexMap.get(bp);
            if (data != null && !data.getMaterial().isAir()) {
                result.put(bp, data);
            } else {
                BlockData worldData = world.getBlockData(bp.x(), bp.y(), bp.z());
                if (!worldData.getMaterial().isAir()) {
                    result.put(bp, worldData);
                }
            }
        }
        return result;
    }

    private Map<BlockPosition, BlockData> buildSchematicBlockMap(SougenSchematic schematic) {
        List<BlockPosition> posList = schematic.positionList();
        List<BlockData> dataList = schematic.blockDataList();
        Map<BlockPosition, BlockData> indexMap = new HashMap<>();
        int size = Math.min(posList.size(), dataList.size());
        for (int i = 0; i < size; i++) {
            indexMap.put(posList.get(i), dataList.get(i));
        }
        return indexMap;
    }

    private @Nullable BlockData getViewerMapBlockData(Player viewer, World world, BlockPosition pos) {
        SougenMap viewerMap = Optional.ofNullable(players.get(viewer)).map(maps::get).orElse(null);
        if (viewerMap == null || !viewerMap.destWorldId().equals(world.getUID())) {
            return null;
        }
        Map<BlockPosition, BlockData> viewerMapBlocks = buildSchematicBlockMap(viewerMap.schematic());
        return viewerMapBlocks.get(pos);
    }

    private double calculateNearestPortalDistance(Location playerLoc, List<BlockPosition> portalBlocks) {
        double minDistSq = Double.MAX_VALUE;
        double px = playerLoc.getX();
        double py = playerLoc.getY();
        double pz = playerLoc.getZ();

        for (BlockPosition bp : portalBlocks) {
            double dx = px - (bp.x() + 0.5);
            double dy = (py - (bp.y() + 0.5)) * Y_DISTANCE_WEIGHT;
            double dz = pz - (bp.z() + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
            }
        }
        return minDistSq == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(minDistSq);
    }

    private List<BlockPosition> collectBlockPositions(BlockPosition min, BlockPosition max) {
        int minX = Math.min(min.x(), max.x());
        int maxX = Math.max(min.x(), max.x());
        int minY = Math.min(min.y(), max.y());
        int maxY = Math.max(min.y(), max.y());
        int minZ = Math.min(min.z(), max.z());
        int maxZ = Math.max(min.z(), max.z());

        List<BlockPosition> list = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    list.add(new BlockPosition(x, y, z));
                }
            }
        }
        return list;
    }

    private @Nullable SougenMap findPortalSourceMap(World world, String portalId) {
        for (SougenMap map : maps.values()) {
            if (!map.destWorldId().equals(world.getUID())) continue;
            if (map.config().portalIds().contains(portalId)) {
                return map;
            }
        }
        return null;
    }

    private boolean isPortalVisibleToPlayer(Player player, String portalId) {
        SougenMap currentMap = Optional.ofNullable(players.get(player)).map(maps::get).orElse(null);
        return currentMap != null && currentMap.config().portalIds().contains(portalId);
    }

    private double calculateRevealProgress(double distance) {
        if (distance >= PORTAL_REVEAL_MAX_DIST) return 0.0;
        if (distance <= PORTAL_REVEAL_MIN_DIST) return 1.0;
        return (PORTAL_REVEAL_MAX_DIST - distance) / (PORTAL_REVEAL_MAX_DIST - PORTAL_REVEAL_MIN_DIST);
    }

    private double distanceSqToCenter(BlockPosition bp, Location center) {
        return Math.pow(bp.x() + 0.5 - center.getX(), 2)
                + Math.pow(bp.y() + 0.5 - center.getY(), 2)
                + Math.pow(bp.z() + 0.5 - center.getZ(), 2);
    }

    private List<BlockPosition> getPositionsSortedByCenter(Set<BlockPosition> candidates, Location portalCenter) {
        List<BlockPosition> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(bp -> distanceSqToCenter(bp, portalCenter)));
        return list;
    }



}