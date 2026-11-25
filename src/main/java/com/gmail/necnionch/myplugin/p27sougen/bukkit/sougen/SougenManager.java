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
    private static final Set<Material> HEAD_MATERIALS = Arrays.stream(Material.values()).filter(m -> m.name().endsWith("_HEAD")).collect(Collectors.toSet());
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

}