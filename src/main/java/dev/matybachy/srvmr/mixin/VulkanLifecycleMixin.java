package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.matybachy.srvmr.ngx.Ngx;
import dev.matybachy.srvmr.render.DlssResolve;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two ends of VulkanMod Reforged's Vulkan: the instance extensions it creates the instance with, to which DLSS's are
 * added, and its teardown, before which DLSS lets go of the device.
 */
@Mixin(value = Vulkan.class, remap = false)
public abstract class VulkanLifecycleMixin {

	@ModifyReturnValue(method = "getRequiredInstanceExtensions", at = @At("RETURN"))
	private static PointerBuffer srvmr$dlssInstanceExtensions(PointerBuffer extensions, MemoryStack stack) {
		return Ngx.withDlssInstanceExtensions(extensions, stack);
	}

	@Inject(method = "cleanUp", at = @At("HEAD"))
	private static void srvmr$releaseDlss(CallbackInfo ci) {
		DlssResolve.shutdown();
	}
}
