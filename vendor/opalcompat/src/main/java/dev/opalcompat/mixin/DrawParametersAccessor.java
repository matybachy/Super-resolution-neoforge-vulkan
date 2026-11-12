package dev.opalcompat.mixin;

import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = DrawBuffers.DrawParameters.class, remap = false)
public interface DrawParametersAccessor {

	@Accessor("indexCount")
	int opalcompat$indexCount();

	@Accessor("firstIndex")
	int opalcompat$firstIndex();

	@Accessor("vertexOffset")
	int opalcompat$vertexOffset();

	@Accessor("baseInstance")
	int opalcompat$baseInstance();
}
