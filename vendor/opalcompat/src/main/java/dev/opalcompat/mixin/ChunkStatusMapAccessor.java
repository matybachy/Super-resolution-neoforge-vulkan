package dev.opalcompat.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.vulkanmod.render.chunk.ChunkStatusMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The flags of each column, for {@code ChunkReadiness}. */
@Mixin(value = ChunkStatusMap.class, remap = false)
public interface ChunkStatusMapAccessor {

	@Accessor("map")
	Long2ByteOpenHashMap opalcompat$map();
}
