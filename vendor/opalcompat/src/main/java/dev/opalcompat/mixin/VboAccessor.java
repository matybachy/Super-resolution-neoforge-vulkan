package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.IndexUploadState;
import net.vulkanmod.render.VBO;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What a pack program needs to draw a {@code VBO} where it lies on the GPU. */
@Mixin(value = VBO.class, remap = false)
public interface VboAccessor {

	@Accessor("vertexBuffer")
	VertexBuffer opalcompat$vertexBuffer();

	@Accessor("indices")
	IndexUploadState<IndexBuffer> opalcompat$indices();

	@Accessor("indexCount")
	int opalcompat$indexCount();

	@Accessor("vertexCount")
	int opalcompat$vertexCount();

	@Accessor("nativeCountSelected")
	boolean opalcompat$nativeCountSelected();

	@Accessor("nativeIndexCount")
	int opalcompat$nativeIndexCount();

	@Accessor("mode")
	VertexFormat.Mode opalcompat$mode();

	@Accessor("format")
	VertexFormat opalcompat$format();

	@Accessor("transientGeometry")
	boolean opalcompat$transientGeometry();

	@Invoker("hasGeometry")
	boolean opalcompat$hasGeometry();
}
