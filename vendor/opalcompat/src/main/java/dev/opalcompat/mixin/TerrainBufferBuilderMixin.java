package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.opalcompat.terrain.ExtendedVertexBuilder;
import dev.opalcompat.terrain.TerrainFormat;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes the extended terrain vertex, and tells it which block the next quads belong to. */
@Mixin(value = TerrainBufferBuilder.class, remap = false)
public abstract class TerrainBufferBuilderMixin {

	@Shadow protected VertexBuilder vertexBuilder;

	@Inject(method = "<init>(ILcom/mojang/blaze3d/vertex/VertexFormat;)V", at = @At("RETURN"))
	private void opalcompat$extendedBuilder(int size, VertexFormat format, CallbackInfo ci) {
		if (TerrainFormat.EXTENDED && format == CustomVertexFormat.COMPRESSED_TERRAIN) {
			this.vertexBuilder = new ExtendedVertexBuilder();
		}
	}

	@Inject(method = "setBlockAttributes", at = @At("HEAD"))
	private void opalcompat$block(BlockState state, CallbackInfo ci) {
		if (this.vertexBuilder instanceof ExtendedVertexBuilder extended) {
			extended.block(state);
		}
	}
}
