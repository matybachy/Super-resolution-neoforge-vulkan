package dev.opalcompat.mixin;

import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = DrawBuffers.class, remap = false)
public interface DrawBuffersAccessor {

	@Accessor("origin")
	Vector3i opalcompat$origin();
}
