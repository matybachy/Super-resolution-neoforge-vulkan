package dev.opalcompat.mixin;

import dev.opalcompat.texture.AnimatedSprites;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.vulkanmod.render.chunk.build.BlockRenderer;
import net.vulkanmod.render.model.quad.QuadView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** VulkanMod Reforged's mesher reads a quad's vertices where they lie, past every getter: {@link AnimatedSprites}. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererUseMixin {

	@ModifyVariable(method = "putQuadData(Lnet/vulkanmod/render/vertex/TerrainBufferBuilder;Lorg/joml/Vector3f;Lnet/vulkanmod/render/model/quad/QuadView;Lnet/vulkanmod/render/chunk/build/light/data/QuadLightData;FFF)V",
			at = @At("HEAD"), argsOnly = true)
	private static QuadView opalcompat$used(QuadView quad) {
		if (quad instanceof BakedQuad baked) AnimatedSprites.use(baked.getSprite());
		return quad;
	}
}
