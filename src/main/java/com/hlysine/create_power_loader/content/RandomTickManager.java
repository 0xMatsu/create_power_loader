package com.hlysine.create_power_loader.content;

import com.hlysine.create_power_loader.config.CPLConfigs;
import com.hlysine.create_power_loader.content.ChunkLoadManager.LoadedChunkPos;
import com.hlysine.create_power_loader.content.trains.CarriageChunkLoader;
import com.hlysine.create_power_loader.content.trains.TrainChunkLoader;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;

/**
 * Applies additional random ticks to chunks force-loaded by chunk loaders.
 * <p>
 * Chunks force-loaded through Porting Lib ticking tickets should already receive vanilla random ticks
 * via Porting Lib's ServerChunkCache mixin, but that mixin is fragile (it relies on local variable
 * indices and may be broken by other mods). This manager performs random ticks directly, so plants
 * keep growing regardless. The amount is configured per loader type via {@code randomTickSpeed}.
 * When a chunk is covered by several loaders, the highest configured value is used (no stacking).
 */
public class RandomTickManager {

    public static void onServerWorldTick(ServerLevel level) {
        if (level.isDebug())
            return;
        if (CPLConfigs.server() == null)
            return;

        Long2IntOpenHashMap targets = collectTargetChunks(level);
        if (targets.isEmpty())
            return;

        ProfilerFiller profiler = level.getProfiler();
        profiler.push("cpl_random_tick");
        for (Long2IntMap.Entry entry : targets.long2IntEntrySet()) {
            long chunkKey = entry.getLongKey();
            if (!level.shouldTickBlocksAt(chunkKey))
                continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null)
                continue;
            randomTickChunk(level, chunk, entry.getIntValue());
        }
        profiler.pop();
    }

    private static Long2IntOpenHashMap collectTargetChunks(ServerLevel level) {
        ResourceLocation dimension = level.dimension().location();
        Long2IntOpenHashMap targets = new Long2IntOpenHashMap();
        for (Map.Entry<LoaderMode, WeakCollection<ChunkLoader>> modeEntry : ChunkLoadManager.allLoaders.entrySet()) {
            for (ChunkLoader loader : modeEntry.getValue()) {
                if (loader instanceof TrainChunkLoader train) {
                    // Resolve per carriage so that andesite and brass carriages use their own config
                    for (CarriageChunkLoader carriage : train.carriageLoaders) {
                        addTargets(targets, dimension, carriage);
                    }
                } else {
                    addTargets(targets, dimension, loader);
                }
            }
        }
        return targets;
    }

    private static void addTargets(Long2IntOpenHashMap targets, ResourceLocation dimension, ChunkLoader loader) {
        if (loader.getForcedChunks().isEmpty())
            return;
        int speed = CPLConfigs.server().getFor(loader.getLoaderType()).randomTickSpeed.get();
        if (speed <= 0)
            return;
        for (LoadedChunkPos pos : loader.getForcedChunks()) {
            if (!dimension.equals(pos.dimension()))
                continue;
            long key = pos.chunkPos().toLong();
            if (speed > targets.getOrDefault(key, 0))
                targets.put(key, speed);
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
