package dev.opalcompat.mixin;

import dev.opalcompat.terrain.ChunkFlow;
import dev.opalcompat.terrain.UrgentTask;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.RenderRegion;
import net.vulkanmod.render.chunk.build.task.BuildTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A build task remembers whether its section was urgent when it was scheduled: {@link ChunkFlow}. */
@Mixin(value = BuildTask.class, remap = false)
public abstract class BuildTaskUrgencyMixin implements UrgentTask {

	@Unique
	private boolean opalcompat$urgent;
	@Unique
	private long opalcompat$created;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void opalcompat$mark(RenderSection section, RenderRegion region, boolean highPriority, long revision, CallbackInfo ci) {
		this.opalcompat$urgent = ChunkFlow.takeNext();
		this.opalcompat$created = System.nanoTime();
	}

	@Override
	public boolean opalcompat$urgent() {
		return this.opalcompat$urgent;
	}

	@Override
	public long opalcompat$created() {
		return this.opalcompat$created;
	}
}
