package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.opalcompat.terrain.ChunkFlow;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.TaskDispatcher;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Which sections are urgent is VulkanMod Reforged's own call, made while the section is still marked dirty: changed by the
 * player, or near the camera. It goes with the task that is made for the section: {@link ChunkFlow}. While hundreds of sections
 * wait, as after joining a world, more of them are scheduled a frame than VulkanMod Reforged's budget allows: {@link ChunkFlow#rebuilds}.
 */
@Mixin(value = SectionGraph.class, remap = false)
public abstract class SectionGraphUrgencyMixin {

	@Shadow
	private boolean isUrgentRebuild(RenderSection section) {
		throw new AssertionError();
	}

	@WrapOperation(method = "schedulePendingRebuilds", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/chunk/RenderSection;rebuildChunkAsync(Lnet/vulkanmod/render/chunk/build/TaskDispatcher;Lnet/vulkanmod/render/chunk/build/RenderRegionBuilder;)Z"))
	private boolean opalcompat$urgent(RenderSection section, TaskDispatcher dispatcher, RenderRegionBuilder regions, Operation<Boolean> original) {
		ChunkFlow.markNext(ChunkFlow.ENABLED && this.isUrgentRebuild(section));
		try {
			return original.call(section, dispatcher, regions);
		} finally {
			ChunkFlow.markNext(false);
		}
	}

	@WrapOperation(method = "schedulePendingRebuilds", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/optimization/AdaptiveChunkUploadBudget;chooseRebuildScheduleBudget(I)I"))
	private int opalcompat$adaptiveCount(int waiting, Operation<Integer> original) {
		return ChunkFlow.rebuilds(original.call(waiting), waiting);
	}

	@WrapOperation(method = "schedulePendingRebuilds", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/optimization/ChunkSchedulingPolicy;fixedRebuildScheduleBudget(I)I"))
	private int opalcompat$fixedCount(int waiting, Operation<Integer> original) {
		return ChunkFlow.rebuilds(original.call(waiting), waiting);
	}

	@WrapOperation(method = "schedulePendingRebuilds", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/optimization/AdaptiveChunkUploadBudget;chooseRebuildScheduleTimeBudgetNanos(I)J"))
	private long opalcompat$adaptiveTime(int waiting, Operation<Long> original) {
		return ChunkFlow.rebuildNanos(original.call(waiting), waiting);
	}

	@WrapOperation(method = "schedulePendingRebuilds", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/optimization/ChunkSchedulingPolicy;fixedRebuildTimeBudgetNanos(I)J"))
	private long opalcompat$fixedTime(int waiting, Operation<Long> original) {
		return ChunkFlow.rebuildNanos(original.call(waiting), waiting);
	}
}
