package com.hlysine.create_power_loader.content;

import com.hlysine.create_power_loader.config.CLoader;
import com.hlysine.create_power_loader.config.CPLConfigs;
import com.hlysine.create_power_loader.content.ChunkLoadManager.LoadedChunkPos;
import com.hlysine.create_power_loader.content.trains.CarriageChunkLoader;
import com.hlysine.create_power_loader.content.trains.TrainChunkLoader;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Guarantees that chunks loaded by chunk loaders receive random ticks (crop / sapling / sugar cane growth etc.).
 * <p>
 * Chunk loaders request "ticking" tickets from Porting Lib, whose mixin into {@code ServerChunkCache#tickChunks}
 * is supposed to tick those chunks even without nearby players. That mixin relies on local variable indices
 * and can silently stop working when other mods touch the same method. This manager therefore:
 * <ol>
 *     <li>records every chunk vanilla actually ticked this game tick ({@link #onVanillaChunkTick}),</li>
 *     <li>at the end of the tick, performs vanilla-rate random ticks (randomTickSpeed game rule) in loaded chunks
 *     that vanilla skipped ({@code ensureRandomTicks}), so growth speed is exactly vanilla, never doubled,</li>
 *     <li>optionally adds extra random ticks in all loaded chunks ({@code extraRandomTickSpeed}, default 0).</li>
 * </ol>
 * All work happens on the server thread, never loads chunks, and only touches chunks that are already
 * in block-ticking range.
 */
public class RandomTickManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int HOOK_WARNING_TICKS = 200;

    /** Chunks ticked by vanilla in the current game tick, per level. */
    private static final Map<ServerLevel, LongSet> vanillaTicked = new IdentityHashMap<>();
    private static boolean hookFired = false;
    private static int ticksWithoutHook = 0;
    private static boolean hookWarningLogged = false;

    /** Called from {@code ServerLevel#tickChunk} via mixin. */
    public static void onVanillaChunkTick(ServerLevel level, LevelChunk chunk) {
        hookFired = true;
        vanillaTicked.computeIfAbsent(level, $ -> new LongOpenHashSet()).add(chunk.getPos().toLong());
    }

    /** Fabric SERVER_STOPPED: drop level references so they can be garbage collected. */
    public static void onServerStopped() {
        vanillaTicked.clear();
        hookFired = false;
        ticksWithoutHook = 0;
        hookWarningLogged = false;
    }

    /** Fabric START_WORLD_TICK */
    public static void onWorldTickStart(ServerLevel level) {
        LongSet set = vanillaTicked.get(level);
        if (set != null)
            set.clear();
    }

    /** Fabric END_WORLD_TICK */
    public static void onWorldTickEnd(ServerLevel level) {
        if (level.isDebug())
            return;
        if (CPLConfigs.server() == null)
            return;

        checkHook(level);

        Long2IntOpenHashMap ensure = new Long2IntOpenHashMap(); // value: 1 = ensure vanilla ticks
        Long2IntOpenHashMap extra = new Long2IntOpenHashMap();  // value: extra random tick speed
        collectTargetChunks(level, ensure, extra);
        if (ensure.isEmpty() && extra.isEmpty())
            return;

        int vanillaSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        LongSet ticked = vanillaTicked.get(level);

        // Merge into a single per-chunk tick count
        Long2IntOpenHashMap total = new Long2IntOpenHashMap(extra);
        if (vanillaSpeed > 0) {
            for (long key : ensure.keySet()) {
                if (ticked != null && ticked.contains(key))
                    continue; // vanilla already random-ticked this chunk
                total.put(key, total.getOrDefault(key, 0) + vanillaSpeed);
            }
        }
        if (total.isEmpty())
            return;

        ProfilerFiller profiler = level.getProfiler();
        profiler.push("cpl_random_tick");
        // Snapshot keys first: random ticks may change blocks / loaders, never iterate live collections
        for (long chunkKey : total.keySet().toLongArray()) {
            int count = total.get(chunkKey);
            if (count <= 0)
                continue;
            if (!level.shouldTickBlocksAt(chunkKey))
                continue;
            // getChunkNow never loads or generates chunks
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null)
                continue;
            randomTickChunk(level, chunk, count);
        }
        profiler.pop();
    }

    private static void checkHook(ServerLevel level) {
        if (hookFired || hookWarningLogged || level.players().isEmpty())
            return;
        if (++ticksWithoutHook >= HOOK_WARNING_TICKS) {
            hookWarningLogged = true;
            LOGGER.warn("CPL: ServerLevel#tickChunk hook never fired; another mod may have replaced it. " +
                    "Chunk loader random ticks will run in every loaded chunk, which may speed up growth if vanilla also ticks them.");
        }
    }

    private static void collectTargetChunks(ServerLevel level, Long2IntOpenHashMap ensure, Long2IntOpenHashMap extra) {
        ResourceLocation dimension = level.dimension().location();
        for (Map.Entry<LoaderMode, WeakCollection<ChunkLoader>> modeEntry : ChunkLoadManager.allLoaders.entrySet()) {
            // Copy to avoid concurrent modification of the weak collection
            for (ChunkLoader loader : new ArrayList<>(modeEntry.getValue())) {
                if (loader == null)
                    continue;
                if (loader instanceof TrainChunkLoader train) {
                    // Resolve per carriage so that andesite and brass carriages use their own config
                    for (CarriageChunkLoader carriage : new ArrayList<>(train.carriageLoaders)) {
                        addTargets(ensure, extra, dimension, carriage);
                    }
                } else {
                    addTargets(ensure, extra, dimension, loader);
                }
            }
        }
    }

    private static void addTargets(Long2IntOpenHashMap ensure, Long2IntOpenHashMap extra, ResourceLocation dimension, ChunkLoader loader) {
        if (loader.getForcedChunks().isEmpty())
            return;
        CLoader config = CPLConfigs.server().getFor(loader.getLoaderType());
        boolean ensureTicks = config.ensureRandomTicks.get();
        int extraSpeed = config.extraRandomTickSpeed.get();
        if (!ensureTicks && extraSpeed <= 0)
            return;
        for (LoadedChunkPos pos : loader.getForcedChunks()) {
            if (!dimension.equals(pos.dimension()))
                continue;
            long key = pos.chunkPos().toLong();
            if (ensureTicks)
                ensure.put(key, 1);
            // Overlapping loaders do not stack, the highest value wins
            if (extraSpeed > extra.getOrDefault(key, 0))
                extra.put(key, extraSpeed);
        }
    }

    /**
     * Mirrors the random tick part of {@link ServerLevel#tickChunk}.
     */
    private static void randomTickChunk(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.isRandomlyTicking())
                continue;
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int n = 0; n < randomTickSpeed; n++) {
                BlockPos pos = level.getBlockRandomPos(minX, minY, minZ, 15);
                BlockState state = section.getBlockState(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ);
                if (state.isRandomlyTicking()) {
                    state.randomTick(level, pos, level.random);
                }
                FluidState fluidState = state.getFluidState();
                if (fluidState.isRandomlyTicking()) {
                    fluidState.randomTick(level, pos, level.random);
                }
            }
        }
    }
}
