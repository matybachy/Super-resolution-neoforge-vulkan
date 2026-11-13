package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.matybachy.srvmr.render.TextureBias;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import net.vulkanmod.vulkan.texture.SamplerManager;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The texture mip bias for DLSS ({@link TextureBias}) in the samplers VulkanMod Reforged makes for textures. Not required:
 * if VulkanMod Reforged's code differs, textures keep its own bias and the log has no "texture mip bias" line.
 */
@Mixin(value = SamplerManager.class, remap = false)
public abstract class SamplerManagerMixin {

	@Shadow
	@Final
	static Short2LongMap SAMPLERS;

	@Inject(method = "getTextureSampler(BB)J", at = @At("HEAD"), require = 0)
	private static void srvmr$refreshBias(byte maxLod, byte flags, CallbackInfoReturnable<Long> cir) {
		TextureBias.refresh(SAMPLERS);
	}

	@WrapOperation(method = "createTextureSampler(BB)J", require = 0, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
	private static VkSamplerCreateInfo srvmr$bias(VkSamplerCreateInfo info, float bias, Operation<VkSamplerCreateInfo> original) {
		return original.call(info, bias + TextureBias.current());
	}
}
