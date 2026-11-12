package dev.opalcompat.mixin;

import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import dev.opalcompat.dh.LodTransfer;
import dev.opalcompat.dh.LodUploads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH's render thread tasks. Its upload of a section's buffers runs on the thread that queues it where VulkanMod's transfer
 * queue can take it ({@code LodTransfer}), instead of waiting for the next frame. What still runs here has a budget of its
 * own a frame, and its uploads go out together: {@link LodUploads}.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.RenderThreadTaskHandler", remap = false)
public abstract class DhRenderTasksMixin {

	@Shadow
	private void runRenderThreadTasks(long nanoMaxRunTime) {
		throw new AssertionError();
	}

	@Inject(method = "queueRunningOnRenderThread", at = @At("HEAD"), cancellable = true)
	private void opalcompat$whereQueued(String name, Runnable task, CallbackInfo ci) {
		if (LodTransfer.takes(name)) {
			// DH's own wrapper: what a task throws is logged as it would be on the render thread.
			new RenderThreadTaskHandler.QueuedRunnable(name, task, null).run();
			ci.cancel();
		}
	}

	@Inject(method = "runRenderThreadTasks()V", at = @At("HEAD"), cancellable = true)
	private void opalcompat$budget(CallbackInfo ci) {
		ci.cancel();
		if (!LodUploads.tasksRunHere()) return;
		LodUploads.begin();
		try {
			this.runRenderThreadTasks(LodUploads.budgetNanos());
		} finally {
			LodUploads.end();
		}
	}
}
