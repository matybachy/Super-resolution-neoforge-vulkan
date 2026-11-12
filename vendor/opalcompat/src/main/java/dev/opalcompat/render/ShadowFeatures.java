package dev.opalcompat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.render.PackValues;
import dev.opalcompat.mixin.LevelRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.render.chunk.RenderSection;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Submits visible entities and block entities into the light's mesh pass. The walk follows Iris 1.21.1
 * ({@code shadows/ShadowRenderer.renderEntities}, {@code renderBlockEntities}). LGPL-3.0, see NOTICE.
 */
final class ShadowFeatures {
	private ShadowFeatures() {
	}

	static void render(PackValues values, Camera camera, Collection<RenderSection> sections, ShadowCasters casters) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !casters.anyFeature()) return;
		DeltaTracker timer = mc.getTimer();
		var position = camera.getPosition();
		Matrix4f view = new Matrix4f(), projection = new Matrix4f();
		values.drawnShadowPair(view, projection);
		Frustum frustum = new Frustum(view, projection);
		frustum.prepare(position.x, position.y, position.z);
		int chunks = mc.options.getEffectiveRenderDistance();
		float limit = values.entityShadowDistance(chunks, chunks);
		double limitSquared = limit < 0.0F ? Double.MAX_VALUE : (double) limit * limit;
		Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		PoseStack poses = new PoseStack();
		PackRenderer.pushPhase(Families.Phase.SHADOW);
		modelView.pushMatrix();
		try {
			modelView.set(view);
			RenderSystem.applyModelViewMatrix();
			mc.gameRenderer.resetProjectionMatrix(projection);
			MultiBufferSource.BufferSource buffers = PassBuffers.source();
			if (casters.entities() || casters.player()) {
				List<Entity> entities = new ArrayList<>();
				for (Entity entity : casters.entities() ? mc.level.entitiesForRendering() : withRiders(mc.player)) {
					if (entity.isSpectator() || entity.distanceToSqr(position.x, position.y, position.z) > limitSquared
							|| !mc.getEntityRenderDispatcher().shouldRender(entity, frustum, position.x, position.y, position.z)) continue;
					entities.add(entity);
				}
				// One buffer per render type: entities of a type together draw far fewer batches.
				entities.sort(Comparator.comparingInt(entity -> entity.getType().hashCode()));
				for (Entity entity : entities) {
					float tick = timer.getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(entity));
					((LevelRendererAccessor) mc.levelRenderer).opalcompat$renderEntity(entity, position.x, position.y,
							position.z, tick, poses, buffers);
				}
			}
			if (casters.anyBlockEntity()) {
				buffers.endBatch();
				PackRenderer.pushPhase(Families.Phase.SHADOW_BLOCK_ENTITIES);
				Set<BlockEntity> blocks = new LinkedHashSet<>();
				for (RenderSection section : sections) blocks.addAll(section.getCompiledSection().getBlockEntities());
				mc.levelRenderer.iterateVisibleBlockEntities(blocks::add);
				float tick = timer.getGameTimeDeltaPartialTick(false);
				for (BlockEntity block : blocks) {
					if (block.isRemoved()) continue;
					var pos = block.getBlockPos();
					if (casters.emittersOnly() && block.getBlockState().getLightEmission(mc.level, pos) == 0) continue;
					if (pos.distToCenterSqr(position.x, position.y, position.z) > limitSquared) continue;
					poses.pushPose();
					try {
						poses.translate(pos.getX() - position.x, pos.getY() - position.y, pos.getZ() - position.z);
						mc.getBlockEntityRenderDispatcher().render(block, tick, poses, buffers);
					} finally {
						poses.popPose();
					}
				}
				buffers.endBatch();
				PackRenderer.popPhase();
			}
			buffers.endBatch();
		} finally {
			modelView.popMatrix();
			RenderSystem.applyModelViewMatrix();
			mc.gameRenderer.resetProjectionMatrix(oldProjection);
			PackRenderer.popPhase();
		}
	}

	/** {@code shadowPlayer} without {@code shadowEntities}: the player and what rides with it. */
	private static List<Entity> withRiders(Entity player) {
		List<Entity> entities = new ArrayList<>(player.getPassengers());
		if (player.getVehicle() != null) entities.add(player.getVehicle());
		entities.add(player);
		return entities;
	}
}
