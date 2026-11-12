package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import dev.opalcompat.render.DrawIds;
import dev.opalcompat.render.MeshIds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MeshData.class)
public abstract class MeshDataMixin implements MeshIds {
	@Unique private DrawIds[] opalcompat$ids;

	@Override
	public void opalcompat$setIds(DrawIds[] ids) {
		this.opalcompat$ids = ids;
	}

	@Override
	public DrawIds[] opalcompat$ids() {
		return this.opalcompat$ids;
	}
}
