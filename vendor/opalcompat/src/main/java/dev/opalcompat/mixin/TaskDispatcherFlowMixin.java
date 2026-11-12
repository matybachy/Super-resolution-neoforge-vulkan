package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.terrain.ChunkFlow;
import dev.opalcompat.terrain.UrgentTask;
import net.vulkanmod.render.chunk.build.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.CompileResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * More than one finished section uploaded a frame while they wait, and the urgent ones built and uploaded first, each beside
 * VulkanMod Reforged's own queue rather than at its end: {@link ChunkFlow}.
 */
@Mixin(value = TaskDispatcher.class, remap = false)
public abstract class TaskDispatcherFlowMixin {

	@Shadow
	@Final
	private BlockingQueue<CompileResult> compileResults;
	@Shadow
	private volatile boolean stopThreads;
	@Shadow
	@Final
	private Semaphore taskSignal;

	@Unique
	private final Queue<ChunkTask> opalcompat$urgentTasks = new ConcurrentLinkedQueue<>();
	@Unique
	private final Queue<CompileResult> opalcompat$urgentResults = new ConcurrentLinkedQueue<>();

	@ModifyReturnValue(method = "getMaxUploadsPerFrame", at = @At("RETURN"))
	private int opalcompat$drainWaiting(int budget) {
		return ChunkFlow.uploads(budget, this.compileResults.size() + this.opalcompat$urgentResults.size());
	}

	/** An urgent task goes ahead of every queued one; a builder is woken for it as for any other. */
	@Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
	private void opalcompat$urgentBuiltFirst(ChunkTask task, CallbackInfoReturnable<Boolean> cir) {
		if (task == null || this.stopThreads || !ChunkFlow.urgent(task)) return;
		this.opalcompat$urgentTasks.offer(task);
		this.taskSignal.release();
		cir.setReturnValue(true);
	}

	/** As VulkanMod Reforged does with its own: a cancelled task is let go and the builder goes back to waiting. */
	@Inject(method = "pollTask", at = @At("HEAD"), cancellable = true)
	private void opalcompat$urgentTaken(CallbackInfoReturnable<ChunkTask> cir) {
		ChunkTask task = this.opalcompat$urgentTasks.poll();
		if (task == null) return;
		if (task.isCancelled()) {
			task.discard();
			cir.setReturnValue(null);
			return;
		}
		cir.setReturnValue(task);
	}

	/** The checks VulkanMod Reforged makes before it queues a mesh, made here before this one is put aside. */
	@Inject(method = "scheduleSectionUpdate", at = @At("HEAD"), cancellable = true)
	private void opalcompat$urgentUploadedFirst(CompileResult result, ChunkTask owner, CallbackInfoReturnable<Boolean> cir) {
		if (!ChunkFlow.urgent(owner)) return;
		if (this.stopThreads || owner.isCancelled() || !result.matchesCurrentSection()) {
			result.releaseBuffers();
			cir.setReturnValue(false);
			return;
		}
		this.opalcompat$urgentResults.offer(result);
		ChunkFlow.handedOver((UrgentTask) owner);
		cir.setReturnValue(true);
	}

	@WrapOperation(method = "scheduleSectionUpdate", at = @At(value = "INVOKE",
			target = "Ljava/util/concurrent/BlockingQueue;offer(Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Z"))
	private boolean opalcompat$timeHandOver(BlockingQueue<Object> queue, Object result, long timeout, TimeUnit unit,
			Operation<Boolean> original) {
		long started = System.nanoTime();
		try {
			return original.call(queue, result, timeout, unit);
		} finally {
			ChunkFlow.waited(System.nanoTime() - started);
		}
	}

	@WrapOperation(method = "updateSections", at = @At(value = "INVOKE",
			target = "Ljava/util/concurrent/BlockingQueue;poll()Ljava/lang/Object;"))
	private Object opalcompat$urgentPolledFirst(BlockingQueue<Object> queue, Operation<Object> original) {
		CompileResult urgent = this.opalcompat$urgentResults.poll();
		return urgent != null ? urgent : original.call(queue);
	}

	@Inject(method = "clearBatchQueue", at = @At("HEAD"))
	private void opalcompat$clearUrgent(CallbackInfo ci) {
		ChunkTask task;
		while ((task = this.opalcompat$urgentTasks.poll()) != null) {
			try {
				task.discard();
			} catch (RuntimeException e) {
				OpalCompat.LOGGER.warn("[chunks] could not let go of a build task", e);
			}
		}
		CompileResult result;
		while ((result = this.opalcompat$urgentResults.poll()) != null) {
			try {
				result.releaseBuffers();
			} catch (RuntimeException e) {
				OpalCompat.LOGGER.warn("[chunks] could not let go of a built mesh", e);
			}
		}
	}

	@ModifyReturnValue(method = "isIdle", at = @At("RETURN"))
	private boolean opalcompat$idle(boolean idle) {
		return idle && this.opalcompat$urgentTasks.isEmpty() && this.opalcompat$urgentResults.isEmpty();
	}
}
