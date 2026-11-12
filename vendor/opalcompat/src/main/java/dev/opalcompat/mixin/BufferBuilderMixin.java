package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.opalcompat.render.DrawIds;
import dev.opalcompat.render.MeshIds;
import dev.opalcompat.render.PackRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner of each vertex, as it is written: a batch mixes owners and is drawn long after they are gone.
 * Every way to write a vertex goes through {@code beginVertex}, VulkanMod's {@code BufferBuilderM} too;
 * only the copies a line makes of its vertices do not, which is what the filling below is for.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {
	@Shadow private int vertices;
	@Unique private List<DrawIds> opalcompat$ids;

	// A return value handler and not a callback: this runs for every vertex of the game, GUI included,
	// and a callback would box the pointer into a new object each time.
	@ModifyReturnValue(method = "beginVertex", at = @At("RETURN"))
	private long opalcompat$captureVertex(long pointer) {
		if (this.opalcompat$ids == null) {
			if (!PackRenderer.capturesDrawIds()) return pointer;
			this.opalcompat$ids = new ArrayList<>();
		}
		while (this.opalcompat$ids.size() < this.vertices - 1) {
			int size = this.opalcompat$ids.size();
			this.opalcompat$ids.add(size == 0 ? DrawIds.NONE : this.opalcompat$ids.get(size - 1));
		}
		this.opalcompat$ids.add(PackRenderer.currentDrawIds());
		return pointer;
	}

	@Inject(method = "build", at = @At("RETURN"))
	private void opalcompat$finish(CallbackInfoReturnable<MeshData> cir) {
		MeshData mesh = cir.getReturnValue();
		if (mesh == null || this.opalcompat$ids == null) return;
		while (this.opalcompat$ids.size() < mesh.drawState().vertexCount()) {
			int size = this.opalcompat$ids.size();
			this.opalcompat$ids.add(size == 0 ? DrawIds.NONE : this.opalcompat$ids.get(size - 1));
		}
		((MeshIds) (Object) mesh).opalcompat$setIds(this.opalcompat$ids.toArray(DrawIds[]::new));
		this.opalcompat$ids = null;
	}
}
