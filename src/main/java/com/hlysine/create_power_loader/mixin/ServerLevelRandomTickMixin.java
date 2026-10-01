package com.hlysine.create_power_loader.mixin;

import com.hlysine.create_power_loader.content.RandomTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records which chunks were ticked by vanilla this tick, so that {@link RandomTickManager}
 * only supplements random ticks where vanilla did not run them (no double ticking).
 * <p>
 * {@code require = 0}: if another mod makes this injection impossible, the game still starts;
 * RandomTickManager then logs a warning and falls back to ticking all loaded chunks itself.
 */
@Mixin(ServerLevel.class)
public class ServerLevelRandomTickMixin {
    @Inject(method = "tickChunk", at = @At("HEAD"), require = 0)
    private void cpl$recordVanillaChunkTick(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        RandomTickManager.onVanillaChunkTick((ServerLevel) (Object) this, chunk);
    }
}
