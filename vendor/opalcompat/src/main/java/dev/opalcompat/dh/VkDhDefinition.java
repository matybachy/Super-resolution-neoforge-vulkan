package dev.opalcompat.dh;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.StubDebugWireframeRenderer;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhAntiAliasRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFarFadeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFogRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhMetaRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhSsaoRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTestTriangleRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one renderer of DH's that does anything here is the terrain. Its passes of colour (fog, ambient occlusion,
 * the fades, anti-aliasing) are a pack's own business once a pack draws the terrain; with no pack the terrain's program
 * works out the fog and the far fade itself, and ambient occlusion, anti-aliasing and the fade of the near chunks stay
 * undone. The rest of what DH draws, its clouds and beacons, stays undrawn: a pack's {@code dh_generic} has no road
 * here yet.
 * <p>
 * Depth runs forward in the volume GL would use, which is what DH's own frustum culling reads its matrix as. The
 * renderer builds its 0..1 row from the planes of that matrix.
 */
final class VkDhDefinition extends AbstractDhRenderApiDefinition {

	private static final IDhMetaRenderer META = new IDhMetaRenderer() {
		@Override public void runRenderPassSetup(RenderParams params) { }
		@Override public void runRenderPassCleanup(RenderParams params) { }
		@Override public void copyToMcTexture(RenderParams params) { }
		@Override public void clearDhDepthAndColorTextures(RenderParams params) { }
	};

	private final AbstractDebugWireframeRenderer wireframes = new StubDebugWireframeRenderer();
	private final IDhTerrainRenderer terrain = VkDhDefinition::drawTerrain;
	private final IDhSsaoRenderer ssao = params -> { };
	private final IDhFogRenderer fog = (params, settings) -> { };
	private final IDhFarFadeRenderer farFade = params -> { };
	private final IDhAntiAliasRenderer antiAlias = params -> { };
	private final IDhVanillaFadeRenderer vanillaFade = params -> { };
	private final IDhTestTriangleRenderer testTriangle = params -> { };

	@Override public String getEngineName() { return "OpalCompat"; }
	@Override public EDhApiDepthDirection getDepthDirection() { return EDhApiDepthDirection.FORWARD_Z; }
	@Override public EDhApiDepthRange getDepthRange() { return EDhApiDepthRange.NEG_ONE_TO_POS_ONE; }
	// Nothing runs on GL in this definition. In DH only its public API reads these, for mods that ask it what is drawing.
	// DH's GL proxy would object to VULKAN, but nothing here ever creates one.
	@Override public EDhApiRenderingApi getRenderApi() { return EDhApiRenderingApi.VULKAN; }
	@Override public EDhApiRenderingEngine getRenderingEngine() { return EDhApiRenderingEngine.BLAZE_3D; }
	@Override public boolean isNativeRenderer() { return false; }

	@Override public IDhMetaRenderer getMetaRenderer() { return META; }
	@Override public IDhTerrainRenderer getTerrainRenderer() { return this.terrain; }
	@Override public IDhSsaoRenderer getSsaoRenderer() { return this.ssao; }
	@Override public IDhFogRenderer getFogRenderer() { return this.fog; }
	@Override public IDhFarFadeRenderer getFarFadeRenderer() { return this.farFade; }
	@Override public IDhAntiAliasRenderer getAntiAliasRenderer() { return this.antiAlias; }
	@Override public AbstractDebugWireframeRenderer getDebugWireframeRenderer() { return this.wireframes; }
	@Override public IDhVanillaFadeRenderer getVanillaFadeRenderer() { return this.vanillaFade; }
	@Override public IDhTestTriangleRenderer getTestTriangleRenderer() { return this.testTriangle; }

	@Override public IDhGenericRenderer createGenericRenderer() { return new NoGenerics(); }
	@Override public IVertexBufferWrapper createVboWrapper(String name) { return new Vbo(); }
	@Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return new NoUniforms(); }
	@Override public IDhGenericObjectVertexBufferContainer createGenericVboContainer() { return new NoGenericVertices(); }

	private static void drawTerrain(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> containers,
			IProfilerWrapper profiler) {
		// What setClipPlanes wrote for a forward matrix: (f + n) / (n - f) and 2fn / (n - f).
		float m22 = params.dhProjectionMatrix.m22, m23 = params.dhProjectionMatrix.m23;
		float near = m23 / (m22 - 1.0F), far = m23 / (m22 + 1.0F);
		int blocks = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * 16;
		boolean light = VkDhBridge.shadowPass();
		if (!light) VkDhBridge.drew(near, far, blocks);
		if (!light && !opaque && !VkDhBridge.takeWater()) return;

		int count = containers.size();
		float[] corners = new float[count * 3];
		LodBuffer[][] sections = new LodBuffer[count][];
		int kept = 0;
		int notUploaded = 0;
		var camera = params.exactCameraPosition;
		for (int i = 0; i < count; i++) {
			LodBufferContainer container = containers.get(i);
			// DH's quad tree closes a section from its own thread, and that only sets this flag: the buffers stay
			// until its close task runs on this thread, ahead of the next list. Skipping it here left a hole for a
			// frame, its replacement not being in this list yet. A container reaches the list only once uploaded.
			if (!container.buffersUploaded) notUploaded++;
			IVertexBufferWrapper[] wrappers = opaque ? container.vboOpaqueWrappers : container.vboTransparentWrappers;
			if (wrappers.length == 0) continue;
			LodBuffer[] buffers = new LodBuffer[wrappers.length];
			for (int b = 0; b < wrappers.length; b++) buffers[b] = (Vbo) wrappers[b];
			corners[kept * 3] = (float) (container.minCornerBlockPos.getX() - camera.x);
			corners[kept * 3 + 1] = (float) (container.minCornerBlockPos.getY() - camera.y);
			corners[kept * 3 + 2] = (float) (container.minCornerBlockPos.getZ() - camera.z);
			sections[kept++] = buffers;
			if (opaque && !light) dev.opalcompat.render.BlinkProbe.farSection(container.pos);
		}
		boolean drawn = VkDhBridge.draw(params, opaque, light, near, far, corners, sections, kept);
		if (opaque && !light) LodWatch.drew(kept, notUploaded, drawn);
	}

	private static final class Vbo extends LodBuffer implements IVertexBufferWrapper {
		@Override public void uploadVertexBuffer(ByteBuffer buffer, int vertexCount) { upload(buffer, vertexCount); }
		@Override public void uploadIndexBuffer(ByteBuffer buffer, int indexCount) { }
		@Override public int getVertexCount() { return vertices(); }
		@Override public void close() { free(); }
	}

	private static final class NoUniforms implements ILodContainerUniformBufferWrapper {
		@Override public void tryUpload(LodBufferContainer container) { }
		@Override public void close() { }
	}

	private static final class NoGenerics implements IDhGenericRenderer {
		private final Map<Long, IDhApiRenderableBoxGroup> groups = new HashMap<>();

		@Override public void add(IDhApiRenderableBoxGroup group) { this.groups.put(group.getId(), group); }
		@Override public IDhApiRenderableBoxGroup remove(long id) { return this.groups.remove(id); }
		@Override public void render(RenderParams params, IProfilerWrapper profiler, boolean withSsao) { }
		@Override public String getVboRenderDebugMenuString() { return ""; }
		@Override public void close() { this.groups.clear(); }
	}

	private static final class NoGenericVertices implements IDhGenericObjectVertexBufferContainer {
		private EState state = EState.NEW;

		@Override public void uploadDataToGpu() { }
		@Override public void updateVertexData(List<DhApiRenderableBox> boxes) { }
		@Override public EState getState() { return this.state; }
		@Override public void setState(EState state) { this.state = state; }
		@Override public void close() { }
	}
}
