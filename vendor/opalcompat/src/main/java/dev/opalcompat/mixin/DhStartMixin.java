package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import dev.opalcompat.dh.VkDhBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DH's start asks for an Iris accessor and takes whatever it gets for Iris: with its Blaze3D engine it stops the game
 * ("Distant Horizons conditional mod config Exception"), on AUTO it forces OpenGL. What it gets here is the adapter of
 * this mod, which draws on Vulkan and needs neither, so that one reads as absent in this method alone. An accessor
 * that is not this mod's goes through, and so do the other checks of the method and every other reader of the adapter:
 * the transparency setting, the shader pack and the shadow pass.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.AbstractModInitializer_neoforge", remap = false)
public abstract class DhStartMixin {

	@ModifyExpressionValue(method = "logIncompatibilityWarnings", at = @At(value = "INVOKE",
			target = "Lcom/seibel/distanthorizons/core/dependencyInjection/ModAccessorInjector;get(Ljava/lang/Class;)Lcom/seibel/distanthorizons/coreapi/interfaces/dependencyInjection/IBindable;"))
	private static IBindable opalcompat$notIris(IBindable accessor) {
		return VkDhBridge.ownAccessor(accessor) ? null : accessor;
	}
}
