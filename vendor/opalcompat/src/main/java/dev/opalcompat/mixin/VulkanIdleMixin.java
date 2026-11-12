package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.opalcompat.vk.QueueLock;
import net.vulkanmod.vulkan.Vulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod's waits for the whole device, against the copies of far terrain from other threads: {@link QueueLock}. */
@Mixin(value = Vulkan.class, remap = false)
public abstract class VulkanIdleMixin {

	@WrapMethod(method = "waitIdle")
	private static void opalcompat$locked(Operation<Void> original) {
		QueueLock.LOCK.lock();
		try {
			original.call();
		} finally {
			QueueLock.LOCK.unlock();
		}
	}

	@Inject(method = "cleanUp", at = @At("HEAD"))
	private static void opalcompat$stop(CallbackInfo ci) {
		QueueLock.stop();
	}
}
