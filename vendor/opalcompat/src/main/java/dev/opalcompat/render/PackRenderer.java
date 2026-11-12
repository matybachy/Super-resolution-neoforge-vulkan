package dev.opalcompat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.LegacyGlsl;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.BlendMode;
import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.model.TargetSize;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.pack.source.ShadowCullState;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.PackValues;
import dev.vitrail.render.ShadowAmortisation;
import dev.vitrail.render.ShadowCullPlan;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.NoiseTexture;
import dev.vitrail.uniform.UniformBlock;
import dev.vitrail.uniform.UniformCatalog;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.compile.SpirvCompiler;
import dev.opalcompat.compile.SpirvModule;
import dev.opalcompat.dh.DhSupport;
import dev.opalcompat.dh.LodBuffer;
import dev.opalcompat.dh.LodIndices;
import dev.opalcompat.mixin.DrawBuffersAccessor;
import dev.opalcompat.mixin.DrawParametersAccessor;
import dev.opalcompat.mixin.RendererAccessor;
import dev.opalcompat.mixin.VboAccessor;
import dev.opalcompat.mixin.WorldRendererAccessor;
import dev.opalcompat.pack.ShaderPacks;
import dev.opalcompat.terrain.TerrainFormat;
import dev.opalcompat.vk.HostBuffer;
import dev.opalcompat.vk.StorageBuffer;
import dev.opalcompat.vk.PackImage;
import dev.opalcompat.vk.Std140Writer;
import dev.opalcompat.vk.Vk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.VBO;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Draws the selected pack over VulkanMod Reforged, driven by the plans Vitrail's loader makes
 * ({@code loadChain}, {@code loadTerrain}).
 * <p>
 * A frame: the level's matrices are captured when it starts ({@link #beginLevel}). Just before the sky
 * ({@link #beforeSky}) the frame opens: the targets and the depth are cleared, the begin passes run, the
 * map is drawn from the light and the prepare passes run. The chunk layers are drawn by the pack's
 * terrain programs into its own targets and depth ({@link #drawTerrain}, instead of VulkanMod's shader),
 * everything else the level draws by the program its family names ({@link #drawImmediate},
 * {@link #drawStored}). Before the first translucent layer the opaque hand is drawn, the depth is copied
 * for {@code depthtex1} and the deferred passes run. After the level ({@link #afterLevel}) the remaining
 * passes and {@code final} run, the last into the swapchain image. Each time, VulkanMod's own render
 * pass is suspended and resumed around the pack's work.
 */
public final class PackRenderer implements AutoCloseable {

	private static final int UBO_PAGE_BYTES = 16 << 20;
	private static final int MESH_PAGE_BYTES = 4 << 20;
	private static final int MESH_ALIGNMENT = 16;
	private static final int UBO_ALIGNMENT = 256;
	private static final int SETS_PER_FRAME = 4096;
	private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
	private static final Set<String> ATLAS = Set.of("gtexture", "tex", "texture", "ofTexture");
	private static final String LIGHTMAP = "lightmap";
	private static final Matrix4fc IDENTITY = new Matrix4f();
	private static final Vector4fc WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);

	private static PackRenderer active;
	private static String activeKey;
	private static String failedKey;
	private static boolean failedDuringFrame;
	private static boolean drawingLevel;
	private static boolean solidHandIssued;
	private static boolean handsHandled;
	private static boolean screenEffectPostponed;
	private static boolean remeshOnLoad;
	private static String dumpPrefix;
	private static TargetDump dumping;
	private static String lastError;
	private static final Families.SkyOrder skyOrder = new Families.SkyOrder();
	private static final java.util.Deque<Families.Phase> phases = new java.util.ArrayDeque<>();
	private static final java.util.Deque<DrawIds> drawIds = new java.util.ArrayDeque<>();
	private static long frameSerial;
	private static boolean resizeHooked;
	private static SpirvCompiler compiler;
	private static final Set<List<String>> UNANSWERED_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final String packName;
	private final OpenedPack opened;
	private final PackValues values;
	private final PackProgram.Chain chain;
	private final PackProgram.Terrain terrain;
	private final Map<String, PackPipeline> pipelines = new LinkedHashMap<>();
	private final Map<String, UniformBlock> blocks = new HashMap<>();
	private final Map<String, PackProgram.Loaded> shadowComposites = new LinkedHashMap<>();
	private final Map<String, PackPipeline> shadowCompositePipelines = new LinkedHashMap<>();
	private final Map<String, UniformBlock> shadowCompositeBlocks = new HashMap<>();
	private final Map<String, Map<Integer, Boolean>> shadowFlips = new HashMap<>();
	private final Map<String, String> viewportScales = new HashMap<>();
	private final List<String> shadowCompositeOrder = new ArrayList<>();
	private PackComputes computes;
	private DistantPrograms distant;
	private VulkanImage distantDepth;
	private VulkanImage distantDepthNoWater;
	private FrameArena.Slice sectionCorners;
	private TextureImages textureImages;
	private PackStorage storage;
	private final Map<TerrainPass, PackPipeline> terrainPipelines = new EnumMap<>(TerrainPass.class);
	private final Map<TerrainPass, UniformBlock> terrainBlocks = new EnumMap<>(TerrainPass.class);
	private final RenderPasses renderPasses = new RenderPasses();
	private final Map<Integer, List<Long>> descriptorPools = new HashMap<>();
	private final Set<String> warned = new HashSet<>();
	private HostBuffer quad;
	private final FrameArena meshArena = new FrameArena(MESH_PAGE_BYTES,
			VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, MESH_ALIGNMENT);
	private final FrameArena ubo = new FrameArena(UBO_PAGE_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, UBO_ALIGNMENT);
	private MeshPrograms meshPrograms;
	private final Std140Writer std140 = new Std140Writer();
	private VulkanImage noise;
	private VulkanImage flatNormal;
	private VulkanImage noSpecular;
	private VulkanImage noOverlay;
	private PackTargets targets;
	private VulkanImage depth;
	private VulkanImage depthNoTranslucent;
	private VulkanImage depthNoHand;
	private ShadowMaps shadow;

	// Per frame
	private boolean frameOpen;
	private boolean shadowDone;
	private boolean deferredDone;
	private boolean preHandDepthCopied;
	private boolean targetsFresh;
	private boolean shadowFresh = true;
	private int nextPass;
	private int nextAlone;
	/** Where a compute being dispatched stands in the schedule, for what it reads and stores; null means the main halves. */
	private TargetSchedule.Bound dispatchStep;
	private long slotSerial = -1;
	private List<Long> framePools;
	private int framePool;
	private final Map<GlobalsKey, FrameArena.Slice> drawGlobals = new HashMap<>();
	private final Map<DrawKey, FrameArena.Slice> drawTransforms = new HashMap<>();
	private boolean announced;

	private PackRenderer(Path packPath, String dimension) throws Exception {
		long start = System.nanoTime();
		UNANSWERED_SAID.clear();
		if (!resizeHooked) {
			resizeHooked = true;
			// The swap chain's image views die with it, whatever its size: no framebuffer over one may be met again.
			Renderer.getInstance().addOnResizeCallback(() -> {
				if (active != null) active.renderPasses.forgetFramebuffers();
			});
		}
		boolean terrainMesh = TerrainFormat.EXTENDED;
		Machine.install();
		this.opened = OpenedPack.open(packPath, ShaderPacks.options(), ShaderPacks.profile());
		boolean built = false;
		try {
			this.packName = this.opened.source().packName();
			this.values = PackValues.read(this.opened, dimension);
			this.viewportScales.putAll(this.opened.properties().viewportScales(this.opened.settings().globalDefines(this.opened.options())));
			this.values.convention(ClipSpace.FORWARD);
			ChainPlan.Families families = new ChainPlan.Families(terrainMesh, true, true, false);
			this.chain = PackProgram.loadChain(this.opened, dimension, ChainFilter.ALL, families);
			this.terrain = terrainMesh
					? PackProgram.loadTerrain(this.opened, this.chain.place(), VertexInputs.TERRAIN)
					: null;
			if (compiler == null) compiler = new SpirvCompiler();
			this.meshPrograms = new MeshPrograms(this.opened, this.chain, this.values, compiler);
			for (Map.Entry<String, PackProgram.Loaded> entry : this.chain.programs().entrySet()) {
				this.pipelines.put(entry.getKey(), new PackPipeline(entry.getKey(), entry.getValue().program(), compiler,
						PackPipeline.Kind.QUAD));
				this.blocks.put(entry.getKey(), block(entry.getKey(), entry.getValue(), this.values.catalog()));
			}
			this.computes = PackComputes.load(this.opened, this.chain, this.values, compiler);
			if (DhSupport.ACTIVE) this.distant = DistantPrograms.load(this.opened, this.chain, this.values, compiler);
			this.shadowComposites.putAll(PackProgram.loadShadowComposites(this.opened, this.chain.place()));
			for (var iterator = this.shadowComposites.entrySet().iterator(); iterator.hasNext(); ) {
				Map.Entry<String, PackProgram.Loaded> entry = iterator.next();
				String name = entry.getKey();
				try {
					this.shadowCompositePipelines.put(name, new PackPipeline(name, entry.getValue().program(), compiler,
							PackPipeline.Kind.QUAD));
					this.shadowCompositeBlocks.put(name, block(name, entry.getValue(), this.values.catalog()));
				} catch (PackPipeline.CompileException e) {
					// The map is still drawn, and the world with it; only what this pass would add to it is missing.
					OpalCompat.LOGGER.error("[pack] {} left out", name, e);
					iterator.remove();
				}
			}
			Set<String> shadowOrder = new java.util.TreeSet<>(ProgramNames.frameOrder());
			shadowOrder.addAll(this.shadowComposites.keySet());
			shadowOrder.addAll(this.computes.shadowCompositePasses());
			this.shadowCompositeOrder.addAll(shadowOrder);
			for (ShaderProperties.FlipDirective flip : this.opened.properties().flips(this.opened.settings().globalDefines(this.opened.options()))) {
				if (!flip.program().startsWith("shadowcomp")) continue;
				TargetName.index(flip.buffer()).ifPresent(index -> {
					if (index < this.chain.targets().shadowCeiling()) {
						this.shadowFlips.computeIfAbsent(flip.program(), unused -> new HashMap<>()).put(index, flip.value());
					}
				});
			}
			if (this.terrain != null) {
				for (Map.Entry<TerrainPass, PackProgram.Loaded> entry : this.terrain.programs().entrySet()) {
					String name = entry.getKey().name().toLowerCase(Locale.ROOT) + ":" + entry.getValue().path();
					this.terrainPipelines.put(entry.getKey(), new PackPipeline(name, entry.getValue().program(), compiler,
							PackPipeline.Kind.TERRAIN));
					// A pass drawn from the light reads the light's matrices under gl_ModelViewMatrix.
					this.terrainBlocks.put(entry.getKey(), block(name, entry.getValue(),
							entry.getKey().shadow() ? this.values.shadowGeometryCatalog() : this.values.geometryCatalog()));
				}
			}
			this.quad = new HostBuffer(6 * 20, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 4);
			ByteBuffer vertices = this.quad.view(this.quad.allocate(6 * 20), 6 * 20).order(ByteOrder.LITTLE_ENDIAN);
			float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 0}, {1, 1}, {0, 1}};
			for (float[] corner : corners) {
				vertices.putFloat(corner[0]).putFloat(corner[1]).putFloat(0.0F).putFloat(corner[0]).putFloat(corner[1]);
			}
			NoiseTexture.Image image = this.values.noiseImage();
			int resolution = this.values.noiseResolution();
			this.noise = image != null
					? uploaded(image.width(), image.height(), image.rgba(), false)
					: uploaded(resolution, resolution, NoiseTexture.rgba(resolution), false);
			// What Iris binds when the resource pack ships no normal or specular map: a flat normal, nothing.
			this.flatNormal = uploaded(1, 1, new byte[] {(byte) 127, (byte) 127, (byte) 255, (byte) 255}, true);
			this.noSpecular = uploaded(1, 1, new byte[] {0, 0, 0, 0}, true);
			// A draw whose render type leaves the overlay unit empty still fetches a texel of it by UV1, so
			// the stand-in has the real image's size. All of it means "no overlay", as (0, 10) there does.
			byte[] noOverlayTexels = new byte[16 * 16 * 4];
			java.util.Arrays.fill(noOverlayTexels, (byte) 255);
			this.noOverlay = uploaded(16, 16, noOverlayTexels, true);
			this.textureImages = new TextureImages(this.opened);
			this.storage = new PackStorage(this.opened);
			Set<Integer> shadowMipmapped = new HashSet<>();
			for (String name : this.shadowComposites.keySet()) shadowMipmapped.addAll(shadowMips(name));
			this.shadow = new ShadowMaps(this.values.shadowResolution(), shadowNamed(), this.values.shadowColours(),
					this.values.shadowDepths(), shadowMipmapped);
			for (PackProgram.Loaded loaded : this.shadowComposites.values()) {
				for (int index : shadowWrites(loaded)) this.shadow.alternateColour(index);
			}
			for (Map<Integer, Boolean> flips : this.shadowFlips.values()) {
				for (int index : flips.keySet()) { this.shadow.colour(index); this.shadow.alternateColour(index); }
			}
			logPlan(System.nanoTime() - start);
			built = true;
		} finally {
			if (!built) closePartly();
		}
	}

	private void closePartly() {
		close();
	}

	private void closeOpened() {
		try {
			this.opened.close();
		} catch (java.io.IOException e) {
			OpalCompat.LOGGER.warn("Closing {} failed", this.packName, e);
		}
	}

	private static VulkanImage uploaded(int width, int height, byte[] rgba, boolean clamp) {
		VulkanImage image = VulkanImage.builder(width, height)
				.setFormat(VK_FORMAT_R8G8B8A8_UNORM)
				.setLinearFiltering(true)
				.setClamp(clamp)
				.createVulkanImage();
		ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
		try {
			pixels.put(rgba).flip();
			image.uploadSubTextureAsync(0, width, height, 0, 0, 0, 0, width, pixels);
			image.readOnlyLayout();
		} finally {
			MemoryUtil.memFree(pixels);
		}
		return image;
	}

	/** Every shadow colour the pack samples or a shadow program writes. */
	private Set<Integer> shadowNamed() {
		Set<Integer> named = new HashSet<>(this.chain.targets().shadowAllocated());
		named.addAll(this.computes.shadowColours());
		for (Map.Entry<String, PackProgram.Loaded> entry : this.shadowComposites.entrySet()) {
			named.addAll(shadowWrites(entry.getValue()));
			named.addAll(shadowMips(entry.getKey()));
			for (SamplerPlan.Binding binding : entry.getValue().samplers().bindings()) {
				if (binding.kind() == SamplerPlan.Kind.SHADOW_COLOUR) named.add(binding.index());
			}
		}
		if (this.terrain != null) {
			for (Map.Entry<TerrainPass, PackProgram.Loaded> entry : this.terrain.programs().entrySet()) {
				if (entry.getKey().shadow()) named.addAll(shadowWrites(entry.getValue()));
			}
		}
		return named;
	}

	/** Which shadow colours a shadow program's draw buffers name, in the order of its outputs. */
	private List<Integer> shadowWrites(PackProgram.Loaded loaded) {
		var fragment = loaded.program().stages().get(ProgramStage.FRAGMENT);
		List<Integer> declared = fragment == null ? List.of() : fragment.drawBuffers();
		return dev.vitrail.pack.target.DrawBuffers.shadowColours(declared, this.chain.targets().shadowCeiling());
	}

	static UniformBlock block(String name, PackProgram.Loaded loaded, UniformCatalog catalog) {
		UniformBlock block = new UniformBlock(loaded.program().uniforms(), catalog);
		// The same few in nearly every program of a pack: said once, with the first program that asks.
		if (!block.unanswered().isEmpty() && UNANSWERED_SAID.add(block.unanswered())) {
			OpalCompat.LOGGER.info("[pack] uniforms nothing answers yet (read as zero), first in {}: {}", name, block.unanswered());
		}
		return block;
	}

	// ----------------------------------------------------------------------------------------------
	// Hooks

	public static void pushEntity(Entity entity) {
		drawIds.push(active == null ? DrawIds.NONE : DrawIds.entity(entity));
	}

	public static void pushBlockEntity(BlockEntity entity) {
		drawIds.push(active == null ? DrawIds.NONE : DrawIds.blockEntity(entity.getBlockState()));
	}

	public static void pushItem(ItemStack item) {
		drawIds.push(active == null ? DrawIds.NONE : currentDrawIds().withItem(item));
	}

	public static void pushFlame() {
		drawIds.push(active == null ? DrawIds.NONE : DrawIds.flame());
	}

	public static void popDrawIds() {
		drawIds.pop();
	}

	public static boolean capturesDrawIds() {
		return drawingLevel && active != null && !failedDuringFrame;
	}

	/** Whether commands can be recorded for the pack now: there is one, and VulkanMod is in a frame. */
	private static boolean drawing() {
		return active != null && !failedDuringFrame && !Renderer.skipRendering && Renderer.isRecording();
	}

	public static DrawIds currentDrawIds() {
		return drawIds.isEmpty() ? DrawIds.NONE : drawIds.peek();
	}
	public static void pushPhase(Families.Phase phase) {
		if (phase == Families.Phase.SKY) skyOrder.reset();
		phases.push(phase);
	}

	public static void popPhase() {
		phases.pop();
	}

	static Families.Phase phase() {
		return phases.isEmpty() ? Families.Phase.LEVEL : phases.peek();
	}

	/** {@code LevelRenderer.renderLevel} HEAD, on the render thread. */
	public static void beginLevel(Matrix4fc modelView, Matrix4fc projection) {
		if (dumping != null) {
			dumping.close();
			dumping = null;
		}
		if (failedDuringFrame) return;
		drawingLevel = true;
		solidHandIssued = false;
		handsHandled = false;
		LevelCapture.capture(modelView, projection);
		ensureLoaded();
		DhSupport.frame(active != null);
		if (active != null) {
			active.values.advance();
			active.frameOpen = false;
		}
	}

	/** Just before {@code LevelRenderer.renderSky}: what the frame needs ahead of the world. */
	public static void beforeSky(net.minecraft.client.Camera camera) {
		if (!drawing()) return;
		try {
			active.openAndShadow(camera);
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/**
	 * VulkanMod's {@code WorldRenderer.renderSectionLayer} HEAD. Returns whether the pack drew the
	 * layer, in which case VulkanMod's own draw is skipped.
	 */
	public static boolean drawTerrain(RenderType renderType, double camX, double camY, double camZ) {
		if (!drawing() || active.terrain == null) return false;
		try {
			return active.terrainLayer(renderType, camX, camY, camZ);
		} catch (RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/**
	 * A mesh on the processor, from {@code BufferUploader}. True if it is dealt with; closing it stays
	 * with the caller.
	 *
	 * @param appliedByHand it came through {@code BufferUploader.draw}, which draws with whatever shader
	 *                      was applied by hand. Post chains are among those, so outside the level's own
	 *                      parts and without an owner such a draw is left alone.
	 */
	public static boolean drawImmediate(MeshData mesh, boolean appliedByHand) {
		if (!drawing() || !drawingLevel) return false;
		Families.Phase current = phase();
		ShaderInstance shader = RenderSystem.getShader();
		if (shader == null) return current.light();
		DrawIds[] ids = ((MeshIds) (Object) mesh).opalcompat$ids();
		MeshData.DrawState state = mesh.drawState();
		try {
			if (current != Families.Phase.LEVEL) {
				return active.immediateMesh(state, mesh.vertexBuffer(), mesh.indexBuffer(), ids, shader.getName(), current);
			}
			DrawIds.Kind kind = MixedMesh.kindOf(ids, state.vertexCount());
			if (kind == null) return active.mixedMesh(mesh, ids, shader.getName());
			if (kind == DrawIds.Kind.NONE && appliedByHand) return false;
			return active.immediateMesh(state, mesh.vertexBuffer(), mesh.indexBuffer(), ids, shader.getName(),
					kind == DrawIds.Kind.BLOCK_ENTITY ? Families.Phase.BLOCK_ENTITIES : Families.Phase.LEVEL);
		} catch (RuntimeException e) {
			fail(e);
			return current.light();
		}
	}

	/** A {@code VBO} already on the GPU: the sky's and the clouds' buffers, or one of a mod's. */
	public static boolean drawStored(VBO vbo, Matrix4f view, Matrix4f projection, ShaderInstance shader) {
		if (!drawing() || !drawingLevel || shader == null) return false;
		VboAccessor access = (VboAccessor) vbo;
		// Transient geometry lives in VulkanMod's per frame buffers, out of reach here. A mod would have
		// to upload through the uploader by itself to get one; VulkanMod draws it.
		if (access.opalcompat$transientGeometry() || !access.opalcompat$hasGeometry()) return false;
		VertexBuffer vertices = access.opalcompat$vertexBuffer();
		if (vertices == null) return false;
		try {
			return active.storedMesh(access, vertices, view, projection, shader.getName());
		} catch (RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/**
	 * One half of Distant Horizons' terrain, from its {@code LodRenderer}.
	 *
	 * @param corners  xyz of each section's corner relative to the camera
	 * @param sections the buffers of each section, near to far as DH lists them
	 * @return whether a program of the pack drew it
	 */
	public static boolean drawDistant(boolean opaque, boolean light, float near, float far, float[] corners, LodBuffer[][] sections, int count) {
		if (!drawing() || !active.frameOpen || active.distant == null || count == 0) return false;
		try {
			return active.distantHalf(opaque, light, near, far, corners, sections, count);
		} catch (RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/** Called at the start of vanilla's translucent stage, in both of its branches. */
	public static void renderSolidHand(net.minecraft.client.Camera camera, net.minecraft.client.renderer.GameRenderer game, float tick) {
		if (!drawing() || !active.frameOpen || solidHandIssued) return;
		try {
			active.copyPreHandDepth();
			HandPass.render(camera, game, tick, true);
			solidHandIssued = true;
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** Called at level return, after the translucent world and before the pack final. */
	public static void renderTranslucentHand(net.minecraft.client.Camera camera, net.minecraft.client.renderer.GameRenderer game, float tick) {
		if (!drawing() || !solidHandIssued) return;
		try {
			Renderer renderer = Renderer.getInstance();
			VkCommandBuffer cmd = Renderer.getCommandBuffer();
			try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
				renderer.endRenderPass(cmd);
				active.deferred(cmd, stack);
			}
			HandPass.render(camera, game, tick, false);
			handsHandled = !failedDuringFrame;
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	public static boolean handsHandled() {
		return handsHandled && active != null && !failedDuringFrame;
	}

	/**
	 * Fire, water and the inside of a block over the view are the game's own, composed over the finished
	 * picture: under Iris they are drawn past the final pass too. True if it is done here later.
	 */
	public static boolean postponeScreenEffect() {
		if (!drawing() || !drawingLevel) return false;
		screenEffectPostponed = true;
		return true;
	}

	/** Whether the blob under a mob gives way, as it does under Iris wherever a pack draws real shadows. */
	public static boolean drawsShadowMap() {
		return active != null && !failedDuringFrame && active.shadowWanted();
	}

	/** After {@code GameRenderer.renderLevel}, before the GUI. */
	public static void afterLevel() {
		drawingLevel = false;
		if (drawing()) {
			FrameStats.level();
			try {
				active.finishFrame();
			} catch (RuntimeException e) {
				fail(e);
			}
		}
		if (screenEffectPostponed) {
			screenEffectPostponed = false;
			ScreenEffectRenderer.renderScreenEffect(Minecraft.getInstance(), new PoseStack());
		}
	}

	/**
	 * Drops the running pack so the frame after this one reads it again, with its current selection and
	 * settings. Not at once: commands recorded for this frame may use what it holds, and
	 * {@link #frameEnded} is where those are known to be done with.
	 */
	public static void reload() {
		failedKey = null;
		lastError = null;
		remeshOnLoad = true;
		if (active != null) failedDuringFrame = true;
	}

	/** For the autotest: every target of the next finished frame, as PNG files starting with this path. */
	public static void requestDump(String prefix) {
		dumpPrefix = prefix;
	}

	/** Why the last pack is not running, for the screen; the log has the rest. */
	public static Optional<String> lastError() {
		return Optional.ofNullable(lastError);
	}

	/** Whether a pack draws the level: the frame's matrices are then the ones it expects. */
	public static boolean packDraws() {
		return active != null && !failedDuringFrame;
	}

	public static Optional<String> runningPack() {
		return active == null || failedDuringFrame ? Optional.empty() : Optional.of(active.packName);
	}

	/** Whether this pack can only load after a restart: see {@link TerrainFormat}. */
	public static boolean needsRestart(String pack) {
		return !pack.isEmpty() && !TerrainFormat.EXTENDED;
	}

	/** The pack is gone and VulkanMod Reforged draws on its own; what is left to do is to say so. */
	private static void report(String pack, Throwable e) {
		StringBuilder text = new StringBuilder(pack).append(": ");
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause != e) text.append("\n");
			text.append(cause.getMessage() != null ? cause.getMessage() : cause.toString());
		}
		lastError = text.toString();
		Minecraft mc = Minecraft.getInstance();
		mc.tell(() -> SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PACK_LOAD_FAILURE,
				Component.translatable("opalcompat.toast.failed"), Component.translatable("opalcompat.toast.detail", pack)));
	}

	private static void fail(RuntimeException e) {
		OpalCompat.LOGGER.error("Shader pack {} failed while drawing; turning it off", active.packName, e);
		report(active.packName, e);
		failedKey = activeKey;
		// Recorded commands still reference this pack until VulkanMod submits the frame.
		failedDuringFrame = true;
	}

	/** Called after VulkanMod finishes or discards the recorded frame. */
	public static void frameEnded() {
		frameSerial++;
		FrameStats.frame();
		if (failedDuringFrame) {
			shutDown();
			failedDuringFrame = false;
		}
	}

	/** Which frame VulkanMod is recording: a frame slot's memory can be reused once per value. */
	static long frameSerial() {
		return frameSerial;
	}

	private static void ensureLoaded() {
		ClientLevel level = Minecraft.getInstance().level;
		Optional<Path> pack = ShaderPacks.selectedPath();
		// Without the extended mesh (TerrainFormat) a pack would run with its world drawn by VulkanMod, which
		// looks like a broken pack. Say what it takes instead, once.
		if (level == null || pack.isEmpty() || needsRestart(ShaderPacks.selectedName())) {
			if (active != null) shutDown();
			return;
		}
		String dimension = dimensionDirectory(level);
		String key = pack.get() + "|" + dimension;
		if (key.equals(activeKey) || key.equals(failedKey)) return;
		shutDown();
		try {
			active = new PackRenderer(pack.get(), dimension);
			activeKey = key;
			lastError = null;
			// What block.properties gives a block is in the mesh; a new world's first mesh comes after this.
			if (remeshOnLoad) {
				remeshOnLoad = false;
				Minecraft mc = Minecraft.getInstance();
				mc.tell(() -> mc.levelRenderer.allChanged());
			}
		} catch (Exception | LinkageError | OutOfMemoryError e) {
			OpalCompat.LOGGER.error("Shader pack {} could not be loaded for {}; drawing without it",
					pack.get().getFileName(), dimension, e);
			report(pack.get().getFileName().toString(), e);
			failedKey = key;
		}
	}

	private static void shutDown() {
		if (active != null) {
			dev.opalcompat.vk.QueueLock.deviceWaitIdle();
			active.close();
		}
		active = null;
		activeKey = null;
	}

	@SuppressWarnings("ReferenceEquality")
	private static String dimensionDirectory(ClientLevel level) {
		if (level.dimension() == Level.NETHER) return "world-1";
		if (level.dimension() == Level.END) return "world1";
		return "world0";
	}

	// ----------------------------------------------------------------------------------------------
	// Frame

	static VulkanImage screen() {
		return Renderer.getInstance().getMainPass().getColorAttachment();
	}

	/** Once per frame, before the pack's first draw: size, descriptors, clears, begin. */
	private void openFrame(VkCommandBuffer cmd, MemoryStack stack) {
		if (this.frameOpen) return;
		this.frameOpen = true;
		this.deferredDone = false;
		this.shadowDone = false;
		this.preHandDepthCopied = false;
		this.nextPass = 0;
		this.nextAlone = 0;
		VulkanImage screen = screen();
		if (this.targets == null || !this.targets.matches(screen.width, screen.height)) {
			dev.opalcompat.vk.QueueLock.deviceWaitIdle();
			this.renderPasses.forgetFramebuffers();
			freeTargets();
			this.targets = new PackTargets(this.chain.targets(), screen.width, screen.height);
			this.targetsFresh = true;
			this.storage.resize(cmd, screen.width, screen.height);
			int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
					| VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			this.depth = VulkanImage.createDepthImage(DEPTH_FORMAT, screen.width, screen.height, usage, false, true);
			this.depthNoTranslucent = VulkanImage.createDepthImage(DEPTH_FORMAT, screen.width, screen.height, usage, false, true);
			this.depthNoHand = VulkanImage.createDepthImage(DEPTH_FORMAT, screen.width, screen.height, usage, false, true);
			if (this.distant != null) {
				this.distantDepth = VulkanImage.createDepthImage(DEPTH_FORMAT, screen.width, screen.height, usage, false, true);
				this.distantDepthNoWater = VulkanImage.createDepthImage(DEPTH_FORMAT, screen.width, screen.height, usage, false, true);
			}
		}
		// What this slot's last frame used is free again, its fence having been waited by VulkanMod. Not a
		// second time within one frame, though: a mod that draws the level twice has commands recorded that
		// still use the first round's sets and memory.
		if (this.slotSerial != frameSerial) {
			this.slotSerial = frameSerial;
			int frame = Renderer.getCurrentFrame();
			BlinkProbe.slotFree(frame);
			this.meshArena.begin(frame);
			this.ubo.begin(frame);
			this.framePools = this.descriptorPools.computeIfAbsent(frame, f -> new ArrayList<>());
			for (long pool : this.framePools) vkResetDescriptorPool(Vk.device(), pool, 0);
			this.framePool = 0;
		}
		this.drawGlobals.clear();
		this.drawTransforms.clear();

		// Iris's order: images, the light's computes, the maps, then the targets, which is what those computes
		// may read of the frame before. Unless there is none yet: the first time they are cleared ahead of them.
		boolean fresh = this.targetsFresh;
		this.storage.beginFrame(cmd, stack);
		if (fresh) clearTargets(cmd, stack);
		clearShadowMaps(cmd, stack);
		if (!fresh) clearTargets(cmd, stack);
		if (this.distantDepth != null) {
			clearDepth(cmd, stack, this.distantDepth);
			clearDepth(cmd, stack, this.distantDepthNoWater);
		}
		this.values.renderStage(RenderStage.NONE);
		// Once for every size the targets take: what setup makes may live in them.
		if (fresh) dispatch(cmd, stack, this.computes.setup(), null, 1, 1);
		runPasses(cmd, stack, this.chain.chain().beginEnd(), "begin");
	}

	/**
	 * The passes up to {@code until}, each with its computes ahead of it, and the computes of programs that
	 * draw nothing at their moment among them.
	 *
	 * @param stage the family that ends there: what stands alone in it runs even with no pass after it
	 */
	private void runPasses(VkCommandBuffer cmd, MemoryStack stack, int until, String stage) {
		List<ChainPlan.Pass> passes = this.chain.chain().passes();
		VulkanImage screen = screen();
		this.values.renderStage(RenderStage.NONE);
		for (; this.nextPass < Math.min(until, passes.size()); this.nextPass++) {
			String program = passes.get(this.nextPass).program();
			// Each pass in a frame of its own: the stack is 64 KiB, a pack may have dozens of them, and running
			// out of it is an Error that nothing here would catch.
			try (MemoryStack frame = stack.push()) {
				dispatchAlone(cmd, frame, screen, alone -> ProgramNames.before(alone.program(), program));
				dispatchBefore(cmd, frame, screen, program);
				drawFullscreen(cmd, frame, passes.get(this.nextPass), screen);
			}
		}
		int rank = ProgramNames.frameRank(stage);
		dispatchAlone(cmd, stack, screen, alone -> ProgramNames.frameRank(ProgramNames.familyOf(alone.program())) <= rank);
	}

	private void dispatchBefore(VkCommandBuffer cmd, MemoryStack stack, VulkanImage screen, String program) {
		List<PackComputes.Dispatch> attached = this.computes.before(program);
		if (attached.isEmpty()) return;
		dispatch(cmd, stack, attached, this.chain.targets().schedule().step(program).orElse(null), screen.width, screen.height);
	}

	private void dispatchAlone(VkCommandBuffer cmd, MemoryStack stack, VulkanImage screen, java.util.function.Predicate<PackComputes.Alone> due) {
		List<PackComputes.Alone> waiting = this.computes.alone();
		while (this.nextAlone < waiting.size() && due.test(waiting.get(this.nextAlone))) {
			PackComputes.Alone alone = waiting.get(this.nextAlone++);
			dispatch(cmd, stack, alone.dispatches(), alone.step(), screen.width, screen.height);
		}
	}

	/**
	 * Computes of one moment, outside any pass, on the halves {@code step} stands on, with Iris's barriers: one
	 * ahead of each dispatch ({@code ComputeProgram.dispatch}), one behind them all for what reads the results.
	 * {@link #writeDescriptors} moves what they store and read into its layout on the way.
	 */
	private void dispatch(VkCommandBuffer cmd, MemoryStack stack, List<PackComputes.Dispatch> dispatches,
			TargetSchedule.Bound step, int width, int height) {
		if (dispatches.isEmpty()) return;
		this.dispatchStep = step;
		try {
			for (PackComputes.Dispatch dispatch : dispatches) {
				if (this.computes.failed(dispatch)) continue;
				try (MemoryStack frame = stack.push()) {
					dispatchOne(cmd, frame, dispatch, width, height);
				} catch (RuntimeException e) {
					this.computes.fail(dispatch, e);
				}
			}
			PackStorage.barrier(cmd, stack);
		} finally {
			this.dispatchStep = null;
		}
	}

	private void dispatchOne(VkCommandBuffer cmd, MemoryStack stack, PackComputes.Dispatch dispatch, int width, int height) {
		PackPipeline pipeline = dispatch.pipeline();
		long[] indirect = dispatch.indirect();
		StorageBuffer counts = indirect == null ? null : this.storage.buffer((int) indirect[0]);
		if (indirect != null && counts == null) throw new IllegalStateException("indirect." + dispatch.name() + " names no buffer");
		int[] groups = indirect == null ? dispatch.compute().groupsAt(width, height) : null;
		// Nought is how a pack turns a pass off.
		if (groups != null && (groups[0] <= 0 || groups[1] <= 0 || groups[2] <= 0)) return;
		long set = allocateSet(stack, pipeline.setLayout());
		writeDescriptors(stack, set, pipeline, dispatch.compute().loaded(), dispatch.block(), List.of(), null,
				new GameImages(null, null, null), null);
		long handle = pipeline.compute();
		PackStorage.barrier(cmd, stack);
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, handle);
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout(), 0, stack.longs(set), null);
		if (groups != null) {
			vkCmdDispatch(cmd, groups[0], groups[1], groups[2]);
		} else {
			vkCmdDispatchIndirect(cmd, counts.handle(), indirect[1]);
		}
	}

	/** The opaque world is done: the depth without translucents, and the deferred passes. */
	private void deferred(VkCommandBuffer cmd, MemoryStack stack) {
		if (this.deferredDone) return;
		this.deferredDone = true;
		copyDepth(cmd, stack, this.depth, this.depthNoTranslucent);
		if (this.distantDepth != null) copyDepth(cmd, stack, this.distantDepth, this.distantDepthNoWater);
		runPasses(cmd, stack, this.chain.chain().deferredEnd(), "deferred");
	}

	/** {@code depthtex2}: everything opaque but the hand. Iris copies it at the same moment ({@code beginHand}). */
	private void copyPreHandDepth() {
		if (this.preHandDepthCopied) return;
		this.preHandDepthCopied = true;
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			copyDepth(cmd, stack, this.depth, this.depthNoHand);
		}
	}

	private boolean terrainLayer(RenderType renderType, double camX, double camY, double camZ) {
		TerrainRenderType type = TerrainRenderType.get(renderType);
		Set<TerrainRenderType> drawn = Initializer.CONFIG.uniqueOpaqueLayer
				? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
		if (!drawn.contains(type)) return true; // VulkanMod folds this layer into another one.
		TerrainPass pass = switch (type) {
			case SOLID -> TerrainPass.SOLID;
			case TRANSLUCENT -> TerrainPass.TRANSLUCENT;
			default -> TerrainPass.CUTOUT;
		};
		PackPipeline pipeline = this.terrainPipelines.get(pass);
		PackProgram.Loaded loaded = this.terrain.programs().get(pass);
		Optional<ChainPlan.Pass> plan = this.chain.chain().geometry(pass);
		if (pipeline == null || loaded == null || plan.isEmpty()) return false;

		if (pass == TerrainPass.TRANSLUCENT) DhSupport.waterBeforeWorld();
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			openFrame(cmd, stack);
			runPasses(cmd, stack, this.chain.chain().prepareEnd(), "prepare");
			if (pass == TerrainPass.TRANSLUCENT) {
				deferred(cmd, stack);
				WorldRenderer world = WorldRenderer.getInstance();
				((WorldRendererAccessor) world).opalcompat$sortTranslucentSections(camX, camY, camZ);
			}

			List<VulkanImage> outputs = new ArrayList<>();
			for (ChainPlan.Attachment attachment : plan.get().attachments()) {
				PackTargets.Target target = this.targets.target(attachment.target());
				if (target == null) return false;
				outputs.add(target.image(attachment.side()));
			}

			this.values.modelView(LevelCapture.modelView(), LevelCapture.bob());
			this.values.projection(LevelCapture.cleanProjection());
			this.values.passAlphaTest(pass == TerrainPass.CUTOUT ? 0.1F : 0.0F);
			this.values.passColour(WHITE);
			this.values.renderStage(switch (type) {
				case SOLID -> RenderStage.TERRAIN_SOLID;
				case CUTOUT_MIPPED -> RenderStage.TERRAIN_CUTOUT_MIPPED;
				case TRANSLUCENT -> RenderStage.TERRAIN_TRANSLUCENT;
				case TRIPWIRE -> RenderStage.TRIPWIRE;
				default -> RenderStage.TERRAIN_CUTOUT;
			});

			long set = allocateSet(stack, pipeline.setLayout());
			writeDescriptors(stack, set, pipeline, loaded, this.terrainBlocks.get(pass), outputs, this.depth, chunkImages(), null);

			String bareName = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
			String askedName = pass == TerrainPass.TRANSLUCENT ? "gbuffers_water" : bareName;
			List<PackPipeline.Blend> blends = blends(askedName, bareName, outputs.size(),
					pass == TerrainPass.TRANSLUCENT ? PackPipeline.Blend.TRANSLUCENT : PackPipeline.Blend.OFF);
			// Translucent culled as well, as vanilla's render type and Iris do: water emits its surface
			// a second time facing down, and drawn unculled that copy covers the upward one.
			boolean cull = Initializer.CONFIG.backFaceCulling;
			PackPipeline.Style style = new PackPipeline.Style(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, true, true,
					VK_COMPARE_OP_LESS_OR_EQUAL, cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE, 0xF, false, blends);

			long renderPass = beginGeometryPass(cmd, stack, outputs);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline(renderPass, outputs.size(), style));
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(set), null);
			drawSections(cmd, stack, pipeline, type, camX, camY, camZ);
			vkCmdEndRenderPass(cmd);
		}
		return true;
	}

	/** Begins a pass over the given targets and the world's depth; returns the render pass. */
	private long beginGeometryPass(VkCommandBuffer cmd, MemoryStack stack, List<VulkanImage> outputs) {
		return beginGeometryPass(cmd, stack, outputs, this.depth);
	}

	private long beginGeometryPass(VkCommandBuffer cmd, MemoryStack stack, List<VulkanImage> outputs, VulkanImage depthImage) {
		for (VulkanImage output : outputs) {
			output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
		}
		depthImage.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
		List<Integer> formats = outputs.stream().map(image -> image.format).toList();
		long renderPass = this.renderPasses.renderPass(formats, DEPTH_FORMAT, true);
		long[] views = new long[outputs.size() + 1];
		// A framebuffer may not be larger than any attachment; a scaled target only crops the draw.
		int width = depthImage.width;
		int height = depthImage.height;
		for (int i = 0; i < outputs.size(); i++) {
			views[i] = attachmentView(outputs.get(i));
			width = Math.min(width, outputs.get(i).width);
			height = Math.min(height, outputs.get(i).height);
		}
		views[outputs.size()] = depthImage.getImageView();
		long framebuffer = this.renderPasses.framebuffer(renderPass, views, width, height);
		beginPass(cmd, stack, renderPass, framebuffer, width, height, false);
		return renderPass;
	}

	/** Everything a draw's two uniform blocks are written from; draws that agree on it share them. */
	private record DrawKey(Matrix4f modelView, Matrix4f projection, float r, float g, float b, float a,
			Matrix4f texture, float alpha, RenderStage stage) {
	}

	private record GlobalsKey(UniformBlock block, DrawKey draw) {
	}

	/** The images the game has bound for a draw. Any of them may be null. */
	private record GameImages(VulkanImage atlas, VulkanImage lightmap, VulkanImage overlay) {
	}

	private record PlannedMesh(MeshPrograms.Program program, List<VulkanImage> outputs, PackPipeline.Style style,
			DrawKey key, VertexFormat format, float biasUnits, float biasFactor) {
	}

	/** Drawn by nothing at all, as opposed to a null plan, which hands the draw back to VulkanMod. */
	private static final PlannedMesh DROPPED = new PlannedMesh(null, List.of(), null, null, null, 0.0F, 0.0F);

	/**
	 * Which program a draw belongs to, into what, in which state: the state is the game's, read off
	 * VulkanMod's tracking of it, as the game set it up for this very draw.
	 * <p>
	 * A draw handed back lands in VulkanMod's own target, which the final pass overwrites, so it is lost
	 * without being an error: that is what happens to what no program of the pack serves.
	 */
	private PlannedMesh planMesh(String shader, VertexFormat format, VertexFormat.Mode mode, Families.Phase phase,
			Matrix4fc view, Matrix4fc projection) {
		if (!this.frameOpen || this.targets == null) return null;
		boolean light = phase.light();
		PipelineState.BlendInfo blend = PipelineState.blendInfo;
		// What a pack pipeline here has no state for. None of the game's world geometry asks for it.
		if (!light && (VRenderSystem.stencilTest || VRenderSystem.logicOp || blend.enabled
				&& (blend.blendOpRgb != VK_BLEND_OP_ADD || blend.blendOpAlpha != VK_BLEND_OP_ADD))) return null;

		String name = shader.substring(shader.indexOf(':') + 1);
		RenderStage stage = Families.stage(name, format, phase, skyOrder);
		if (dropped(stage)) return DROPPED;
		Families.Piece piece = Families.resolve(name, format, phase, blend.enabled);
		if (piece == null) return light ? DROPPED : null;
		Optional<MeshPrograms.Program> candidate = this.meshPrograms.resolve(piece, this.deferredDone);
		// Still being translated and compiled on the shader thread: left out of the picture until it is there.
		if (candidate == null) {
			FrameStats.waiting();
			return DROPPED;
		}
		if (candidate.isEmpty()) return light ? DROPPED : null;
		MeshPrograms.Program program = candidate.get();

		List<VulkanImage> outputs = new ArrayList<>();
		if (light) {
			for (int index : shadowWrites(program.loaded())) outputs.add(this.shadow.colour(index));
		} else {
			// One pass, one render area, and the depth is the size of the screen.
			if (!program.pass().size().equals(TargetSize.ofScreen())) return null;
			for (ChainPlan.Attachment attachment : program.pass().attachments()) {
				PackTargets.Target target = this.targets.target(attachment.target());
				if (target == null) return null;
				outputs.add(target.image(attachment.side()));
			}
		}

		PackPipeline.Blend fallback = !light && blend.enabled
				? new PackPipeline.Blend(true, blend.srcRgbFactor, blend.dstRgbFactor, blend.srcAlphaFactor, blend.dstAlphaFactor)
				: PackPipeline.Blend.OFF;
		List<PackPipeline.Blend> blends = blends(piece.program(), program.served(), outputs.size(), fallback);
		RendererAccessor offset = (RendererAccessor) (Object) Renderer.getInstance();
		float biasUnits = offset.opalcompat$depthBiasUnits();
		float biasFactor = offset.opalcompat$depthBiasFactor();
		PackPipeline.Style style;
		if (light) {
			style = new PackPipeline.Style(MeshDraw.topology(mode), true, true, VK_COMPARE_OP_LESS_OR_EQUAL,
					VK_CULL_MODE_NONE, 0xF, false, blends);
		} else {
			// GL's 512..519 and Vulkan's 0..7 name the comparisons in one order.
			int depthOp = VRenderSystem.depthFun - 512;
			if (depthOp < VK_COMPARE_OP_NEVER || depthOp > VK_COMPARE_OP_ALWAYS) throw new IllegalStateException("Unknown depth function");
			style = new PackPipeline.Style(MeshDraw.topology(mode), VRenderSystem.depthTest, VRenderSystem.depthMask, depthOp,
					VRenderSystem.cull ? VRenderSystem.cullFace : VK_CULL_MODE_NONE, VRenderSystem.colorMask,
					biasUnits != 0.0F || biasFactor != 0.0F, blends);
		}
		float[] colour = RenderSystem.getShaderColor();
		DrawKey key = new DrawKey(new Matrix4f(view), new Matrix4f(projection), colour[0], colour[1], colour[2], colour[3],
				new Matrix4f(RenderSystem.getTextureMatrix()), program.loaded().alphaTest().reference(), stage);
		return new PlannedMesh(program, outputs, style, key, format, biasUnits, biasFactor);
	}

	/** What {@code shaders.properties} switches off ({@code sun=false}, {@code clouds=off}, {@code weather=false}). */
	private boolean dropped(RenderStage stage) {
		ShaderProperties.SkyElements elements = this.values.skyElements();
		return switch (stage) {
			case SUN -> !elements.sun();
			case MOON -> !elements.moon();
			case STARS -> !elements.stars();
			case SKY, SUNSET, VOID -> !elements.sky();
			case CLOUDS -> elements.clouds() == ShaderProperties.CloudSetting.OFF;
			case RAIN_SNOW -> !this.values.weather().drawn();
			default -> false;
		};
	}

	private boolean immediateMesh(MeshData.DrawState state, ByteBuffer vertices, ByteBuffer indices, DrawIds[] ids,
			String shader, Families.Phase phase) {
		PlannedMesh planned = planMesh(shader, state.format(), state.mode(), phase,
				RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
		if (planned == null) return false;
		if (planned == DROPPED) return true;
		MeshDraw draw = MeshDraw.immediate(this.meshArena, state, vertices, indices, ids,
				planned.program().pipeline().readsDerived(), RenderSystem.getShaderLineWidth());
		if (draw != null) submitMesh(planned, draw);
		return true;
	}

	/** A batch holding entities and block entities, drawn run by run in its own order. */
	private boolean mixedMesh(MeshData mesh, DrawIds[] ids, String shader) {
		MeshData.DrawState state = mesh.drawState();
		List<MixedMesh.Run> runs = MixedMesh.split(mesh, ids);
		if (runs.isEmpty()) {
			return immediateMesh(state, mesh.vertexBuffer(), mesh.indexBuffer(), ids, shader, Families.Phase.LEVEL);
		}
		// Every run first, so that one VulkanMod has to draw leaves the whole batch to it.
		List<PlannedMesh> plans = new ArrayList<>(runs.size());
		boolean derive = false;
		for (MixedMesh.Run run : runs) {
			PlannedMesh planned = planMesh(shader, state.format(), state.mode(),
					run.blockEntity() ? Families.Phase.BLOCK_ENTITIES : Families.Phase.LEVEL,
					RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
			if (planned == null) return false;
			plans.add(planned);
			derive |= planned != DROPPED && planned.program().pipeline().readsDerived();
		}
		MeshDraw whole = MeshDraw.immediate(this.meshArena, state, mesh.vertexBuffer(), null, ids, derive,
				RenderSystem.getShaderLineWidth());
		if (whole == null) return true;
		for (int i = 0; i < runs.size(); i++) {
			if (plans.get(i) == DROPPED) continue;
			submitMesh(plans.get(i), whole.withIndices(this.meshArena, runs.get(i).indices(), runs.get(i).count()));
		}
		return true;
	}

	private boolean storedMesh(VboAccessor access, VertexBuffer vertices, Matrix4fc view, Matrix4fc projection, String shader) {
		VertexFormat.Mode mode = access.opalcompat$mode();
		PlannedMesh planned = planMesh(shader, access.opalcompat$format(), mode, phase(), view, projection);
		if (planned == null) return false;
		if (planned == DROPPED) return true;
		IndexBuffer indices = access.opalcompat$indices().indices();
		int count = indices != null ? access.opalcompat$indexCount()
				: access.opalcompat$nativeCountSelected() ? access.opalcompat$nativeIndexCount() : access.opalcompat$vertexCount();
		submitMesh(planned, MeshDraw.stored(this.meshArena, vertices.getId(), vertices.getOffset(), indices, count,
				RenderSystem.getShaderLineWidth()));
		return true;
	}

	private void submitMesh(PlannedMesh planned, MeshDraw draw) {
		MeshPrograms.Program program = planned.program();
		PackPipeline pipeline = program.pipeline();
		List<VulkanImage> outputs = planned.outputs();
		GameImages images = new GameImages(glImage(RenderSystem.getShaderTexture(0)), glImage(RenderSystem.getShaderTexture(2)),
				glImage(RenderSystem.getShaderTexture(1)));
		// The driver builds a pipeline it has not seen on the shader thread; this draw is left out until it has.
		List<Integer> formats = outputs.stream().map(image -> image.format).toList();
		long handle = pipeline.readyPipeline(this.renderPasses.renderPass(formats, program.shadow() ? this.shadow.depthFormat() : DEPTH_FORMAT, true),
				outputs.size(), planned.style(), planned.format(), draw.derived());
		if (handle == 0L) {
			FrameStats.waiting();
			return;
		}
		FrameStats.mesh();
		long meshStarted = System.nanoTime();
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			long set = allocateSet(stack, pipeline.setLayout());
			writeDescriptors(stack, set, pipeline, program.loaded(), program.uniforms(), outputs,
					program.shadow() ? this.shadow.depth() : this.depth, images, planned.key());
			if (program.shadow()) beginShadowPass(cmd, stack, outputs); else beginGeometryPass(cmd, stack, outputs);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(set), null);
			if (planned.style().depthBias()) vkCmdSetDepthBias(cmd, planned.biasUnits(), 0.0F, planned.biasFactor());
			draw.draw(cmd, stack);
			vkCmdEndRenderPass(cmd);
		}
		FrameStats.meshTime(System.nanoTime() - meshStarted);
	}

	/**
	 * The atlas and the light map of the chunk layers, looked up through VulkanMod's GL emulation (its
	 * own slots are only filled when its pipeline draws). Asked for directly and not through a layer's
	 * render state: under Fabulous that state binds a render target, which begins a pass of VulkanMod's
	 * right where the pack is about to begin its own.
	 */
	private static GameImages chunkImages() {
		Minecraft minecraft = Minecraft.getInstance();
		LightTexture light = minecraft.gameRenderer.lightTexture();
		light.turnOnLightLayer();
		VulkanImage lightmap = glImage(RenderSystem.getShaderTexture(2));
		light.turnOffLightLayer();
		VulkanImage atlas = glImage(minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
		return new GameImages(atlas, lightmap, null);
	}

	/** What the pack's {@code blend.<program>} directives ask for, per attachment. */
	private List<PackPipeline.Blend> blends(String asked, String served, int attachments, PackPipeline.Blend fallback) {
		List<PackPipeline.Blend> blends = new ArrayList<>(Math.max(1, attachments));
		for (int rank = 0; rank < Math.max(1, attachments); rank++) {
			int at = rank;
			Optional<BlendMode> mode = this.chain.targets().blend(asked, at)
					.or(() -> this.chain.targets().blend(served, at));
			blends.add(mode.map(PackRenderer::toBlend).orElse(fallback));
		}
		return blends;
	}

	private static PackPipeline.Blend toBlend(BlendMode mode) {
		if (mode.off()) return PackPipeline.Blend.OFF;
		return new PackPipeline.Blend(true, PackPipeline.factor(mode.srcRgb()), PackPipeline.factor(mode.dstRgb()),
				PackPipeline.factor(mode.srcAlpha()), PackPipeline.factor(mode.dstAlpha()));
	}

	static VulkanImage glImage(int glId) {
		GlTexture texture = GlTexture.getTexture(glId);
		return texture == null ? null : texture.getVulkanImage();
	}

	/** A target with a mip chain renders into its first level; a framebuffer takes a one-level view. */
	private static long attachmentView(VulkanImage image) {
		return image.mipLevels > 1 ? image.getLevelImageView(0) : image.getImageView();
	}

	/** VulkanMod's own draw loop over its chunk areas, with the pack's pipeline bound. */
	private static void drawSections(VkCommandBuffer cmd, MemoryStack stack, PackPipeline pipeline, TerrainRenderType type,
			double camX, double camY, double camZ) {
		boolean translucent = type == TerrainRenderType.TRANSLUCENT;
		if (!translucent) {
			Renderer.getDrawer().bindIndexBuffer(cmd, dev.opalcompat.terrain.TerrainIndices.buffer());
		}
		WorldRenderer world = WorldRenderer.getInstance();
		StaticQueue<ChunkArea> areas = ((WorldRendererAccessor) world).opalcompat$sectionGraph().getChunkAreaQueue().queue();
		BlinkProbe.nearSections(areas);
		int areaCount = areas.size();
		for (int a = 0; a < areaCount; a++) {
			ChunkArea area = areas.get(translucent ? areaCount - 1 - a : a);
			StaticQueue<RenderSection> sections = area.sectionQueue;
			if (sections.size() == 0 || !bindArea(cmd, stack, pipeline, area, type, camX, camY, camZ)) continue;

			int count = sections.size();
			for (int s = 0; s < count; s++) {
				drawSection(cmd, sections.get(translucent ? count - 1 - s : s), type);
			}
		}
	}

	/** Binds what one chunk area's sections are drawn out of; false if it has nothing for this layer. */
	private static boolean bindArea(VkCommandBuffer cmd, MemoryStack stack, PackPipeline pipeline, ChunkArea area,
			TerrainRenderType type, double camX, double camY, double camZ) {
		DrawBuffers buffers = area.getDrawBuffers();
		AreaBuffer vertices = buffers.getAreaBuffer(type);
		if (vertices == null) return false;
		if (type == TerrainRenderType.TRANSLUCENT) {
			AreaBuffer indices = buffers.getIndexBuffer();
			if (indices == null) return false;
			vkCmdBindIndexBuffer(cmd, indices.getId(), 0, VK_INDEX_TYPE_UINT16);
		}
		vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertices.getId()), stack.longs(0));
		Vector3i origin = ((DrawBuffersAccessor) buffers).opalcompat$origin();
		ByteBuffer offset = stack.malloc(12);
		offset.putFloat(0, (float) (origin.x + DrawBuffers.POS_OFFSET - camX));
		offset.putFloat(4, (float) (origin.y + DrawBuffers.POS_OFFSET - camY));
		offset.putFloat(8, (float) (origin.z + DrawBuffers.POS_OFFSET - camZ));
		vkCmdPushConstants(cmd, pipeline.layout(), VK_SHADER_STAGE_VERTEX_BIT, 0, offset);
		return true;
	}

	private static void drawSection(VkCommandBuffer cmd, RenderSection section, TerrainRenderType type) {
		DrawParametersAccessor draw = (DrawParametersAccessor) section.getDrawParameters(type);
		if (draw.opalcompat$indexCount() <= 0) return;
		int firstIndex = draw.opalcompat$firstIndex() == -1 ? 0 : draw.opalcompat$firstIndex();
		vkCmdDrawIndexed(cmd, draw.opalcompat$indexCount(), 1, firstIndex, draw.opalcompat$vertexOffset(),
				draw.opalcompat$baseInstance());
	}

	// ----------------------------------------------------------------------------------------------
	// Shadow

	/**
	 * The frame opens here, and the map is drawn: solid and cut-out terrain, what the mesh families
	 * cast, the copy without translucents, translucent terrain. The prepare passes read the map of this
	 * frame, so they run behind it. Iris's order ({@code shadows/ShadowRenderer.java}).
	 */
	private void openAndShadow(net.minecraft.client.Camera camera) {
		var position = camera.getPosition();
		double camX = position.x, camY = position.y, camZ = position.z;
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			openFrame(cmd, stack);
			if (this.shadowDone) return;
			this.shadowDone = true;
			ShadowCasters casters = this.values.shadowCasters();
			// No shadow program means no map, whatever the casters say: the mobs would be walked for nothing.
			boolean map = shadowWanted();
			Collection<ShadowArea> areas = map ? shadowSections(camX, camY, camZ) : List.of();
			if (!areas.isEmpty() && casters.terrain()) {
				shadowLayer(cmd, stack, areas, TerrainPass.SHADOW_SOLID, TerrainRenderType.SOLID, camX, camY, camZ);
				shadowLayer(cmd, stack, areas, TerrainPass.SHADOW_CUTOUT, TerrainRenderType.CUTOUT_MIPPED, camX, camY, camZ);
				shadowLayer(cmd, stack, areas, TerrainPass.SHADOW_CUTOUT, TerrainRenderType.CUTOUT, camX, camY, camZ);
			}
			if (map && casters.anyFeature()) {
				List<RenderSection> sections = areas.stream().flatMap(area -> area.sections().stream()).toList();
				ShadowFeatures.render(this.values, camera, sections, casters);
				// The nested draws resume VulkanMod's target; the depth copy must be outside that pass.
				renderer.endRenderPass(cmd);
			}
			boolean distantCasts = map && this.distant != null && this.values.dhShadow();
			if (distantCasts && casters.terrain()) {
				DhSupport.shadow(false);
				renderer.endRenderPass(cmd);
			}
			copyDepth(cmd, stack, this.shadow.depth(), this.shadow.depthNoTranslucent());
			if (!areas.isEmpty() && casters.translucent()) {
				shadowLayer(cmd, stack, areas, TerrainPass.SHADOW_TRANSLUCENT, TerrainRenderType.TRANSLUCENT, camX, camY, camZ);
			}
			if (distantCasts && casters.translucent()) {
				DhSupport.shadow(true);
				renderer.endRenderPass(cmd);
			}
			if (this.shadow.depth().mipLevels > 1) mipChain(cmd, stack, this.shadow.depth(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_FILTER_NEAREST);
			if (this.shadow.depthNoTranslucent().mipLevels > 1) mipChain(cmd, stack, this.shadow.depthNoTranslucent(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_FILTER_NEAREST);
			if (!areas.isEmpty()) ShadowAmortisation.drawn();
			runShadowComposites(cmd, stack);
			runPasses(cmd, stack, this.chain.chain().prepareEnd(), "prepare");
		}
	}

	private boolean distantHalf(boolean opaque, boolean light, float near, float far, float[] corners, LodBuffer[][] sections, int count) {
		DistantPrograms.Half half = DistantPrograms.Half.of(opaque, light);
		DistantPrograms.Program program = this.distant.program(half);
		if (program == null) return false;
		PackPipeline pipeline = program.pipeline();
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			LodBuffer.afterCopies(cmd, stack);
			// The water reads what the deferred stage made of the world, as the world's own water does.
			if (half == DistantPrograms.Half.WATER) deferred(cmd, stack);

			List<VulkanImage> outputs = new ArrayList<>();
			if (light) {
				for (int index : shadowWrites(program.loaded())) outputs.add(this.shadow.colour(index));
			} else {
				for (ChainPlan.Attachment attachment : program.pass().attachments()) {
					PackTargets.Target target = this.targets.target(attachment.target());
					if (target == null) return false;
					outputs.add(target.image(attachment.side()));
				}
			}

			this.values.passAlphaTest(0.0F);
			this.values.renderStage(opaque ? RenderStage.TERRAIN_SOLID : RenderStage.TERRAIN_TRANSLUCENT);
			if (!light) {
				// DH's volume: the frame's matrix with its own planes, in the OpenGL form like every matrix handed in here.
				Matrix4f projection = new Matrix4f(LevelCapture.cleanProjection());
				projection.m22((far + near) / (near - far));
				projection.m32(2.0F * far * near / (near - far));
				projection.m02(0.0F);
				projection.m12(0.0F);
				this.values.modelView(LevelCapture.modelView(), LevelCapture.bob());
				this.values.projection(projection);
				this.values.passColour(WHITE);
			}

			// Each section's corner in a slot of its own, moved to with a dynamic offset.
			FrameArena.Slice slots = this.ubo.allocate(count * UBO_ALIGNMENT);
			ByteBuffer bytes = slots.bytes().order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < count; i++) {
				bytes.putFloat(i * UBO_ALIGNMENT, corners[i * 3]);
				bytes.putFloat(i * UBO_ALIGNMENT + 4, corners[i * 3 + 1]);
				bytes.putFloat(i * UBO_ALIGNMENT + 8, corners[i * 3 + 2]);
			}
			this.sectionCorners = slots;

			VulkanImage depthImage = light ? this.shadow.depth() : this.distantDepth;
			long set = allocateSet(stack, pipeline.setLayout());
			writeDescriptors(stack, set, pipeline, program.loaded(), program.block(), outputs, depthImage, new GameImages(null, null, null), null);

			String bareName = half.program;
			List<PackPipeline.Blend> blends = blends(bareName, bareName, outputs.size(), half.water && !light ? PackPipeline.Blend.TRANSLUCENT : PackPipeline.Blend.OFF);
			// DH culls its opaque half, a pack's water stays seen from below, and nothing is culled in the map.
			int cull = opaque && !light ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE;
			PackPipeline.Style style = new PackPipeline.Style(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, true, true,
					VK_COMPARE_OP_LESS_OR_EQUAL, cull, 0xF, false, blends);
			long renderPass = light ? beginShadowPass(cmd, stack, outputs) : beginGeometryPass(cmd, stack, outputs, depthImage);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline(renderPass, outputs.size(), style));
			vkCmdBindIndexBuffer(cmd, LodIndices.handle(), 0, VK_INDEX_TYPE_UINT32);
			LongBuffer sets = stack.longs(set);
			java.nio.IntBuffer offsets = stack.mallocInt(1);
			LongBuffer vertexBuffer = stack.mallocLong(1);
			LongBuffer vertexOffset = stack.mallocLong(1);
			// Blended far to near, as DH lists them the other way.
			for (int s = 0; s < count; s++) {
				int section = half.water ? count - 1 - s : s;
				offsets.put(0, section * UBO_ALIGNMENT);
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, sets, offsets);
				for (LodBuffer buffer : sections[section]) {
					if (buffer == null || !buffer.ready()) continue;
					vertexBuffer.put(0, buffer.handle());
					vertexOffset.put(0, buffer.offset());
					vkCmdBindVertexBuffers(cmd, 0, vertexBuffer, vertexOffset);
					vkCmdDrawIndexed(cmd, buffer.indices(), 1, 0, 0, 0);
				}
			}
			vkCmdEndRenderPass(cmd);
		}
		return true;
	}

	/** Whether the pack has anything to draw the map with; the map then stays clear, as in Iris. */
	private boolean shadowWanted() {
		if (this.terrain == null) return false;
		for (TerrainPass pass : this.terrainPipelines.keySet()) {
			if (pass.shadow()) return true;
		}
		return false;
	}

	private record ShadowArea(ChunkArea area, List<RenderSection> sections) {
	}

	/**
	 * The compiled sections the light sees, by chunk area. VulkanMod's graph walks from the camera, so
	 * a section it has never reached has no mesh and casts nothing here, however close to the frustum.
	 * Voxelising programs keep what lies outside the frustum: their volume is not limited to the map.
	 */
	private Collection<ShadowArea> shadowSections(double camX, double camY, double camZ) {
		int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
		boolean voxelised = this.terrain != null && this.terrain.programs().entrySet().stream()
				.anyMatch(entry -> entry.getKey().shadow() && entry.getValue().voxelises());
		ShadowCullPlan plan = this.values.shadowCullPlan(chunks, chunks, new Vector3f(), new Matrix4f(), voxelised);
		float bound = plan.bound();
		if (bound == 0.0F) return List.of();
		boolean frustumCulled = !voxelised && plan.state() != ShadowCullState.DISTANCE;
		FrustumIntersection frustum = new FrustumIntersection(this.values.shadowFrustum(new Matrix4f()), false);
		float boundSquared = bound * bound;
		Map<ChunkArea, ShadowArea> areas = new LinkedHashMap<>();
		SectionGrid grid = WorldRenderer.getInstance().getSectionGrid();
		if (grid == null) return List.of();
		for (RenderSection section : grid.sections) {
			if (section == null || !section.isCompiled() || section.isCompletelyEmpty()) continue;
			ChunkArea area = section.getChunkArea();
			if (area == null) continue;
			float x = (float) (section.xOffset - camX);
			float y = (float) (section.yOffset - camY);
			float z = (float) (section.zOffset - camZ);
			if (bound > 0.0F) {
				float dx = Math.max(Math.max(x, -(x + 16.0F)), 0.0F);
				float dz = Math.max(Math.max(z, -(z + 16.0F)), 0.0F);
				if (dx * dx + dz * dz > boundSquared) continue;
			}
			if (frustumCulled && !frustum.testAab(x, y, z, x + 16.0F, y + 16.0F, z + 16.0F)) continue;
			areas.computeIfAbsent(area, a -> new ShadowArea(a, new ArrayList<>())).sections().add(section);
		}
		return areas.values();
	}

	private void shadowLayer(VkCommandBuffer cmd, MemoryStack stack, Collection<ShadowArea> areas, TerrainPass pass,
			TerrainRenderType type, double camX, double camY, double camZ) {
		Set<TerrainRenderType> drawn = Initializer.CONFIG.uniqueOpaqueLayer
				? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
		if (!drawn.contains(type)) return;
		PackPipeline pipeline = this.terrainPipelines.get(pass);
		PackProgram.Loaded loaded = this.terrain.programs().get(pass);
		if (pipeline == null || loaded == null) return;

		List<VulkanImage> outputs = new ArrayList<>();
		for (int index : shadowWrites(loaded)) outputs.add(this.shadow.colour(index));

		this.values.passAlphaTest(pass == TerrainPass.SHADOW_CUTOUT ? 0.1F : 0.0F);
		long set = allocateSet(stack, pipeline.setLayout());
		writeDescriptors(stack, set, pipeline, loaded, this.terrainBlocks.get(pass), outputs, this.shadow.depth(), chunkImages(), null);

		String bareName = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		List<PackPipeline.Blend> blends = blends(pass.program(), bareName, outputs.size(), PackPipeline.Blend.OFF);
		PackPipeline.Style style = new PackPipeline.Style(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, true, true,
				VK_COMPARE_OP_LESS_OR_EQUAL, VK_CULL_MODE_NONE, 0xF, false, blends);
		long renderPass = beginShadowPass(cmd, stack, outputs);
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline(renderPass, outputs.size(), style));
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(set), null);
		if (type != TerrainRenderType.TRANSLUCENT) {
			Renderer.getDrawer().bindIndexBuffer(cmd, dev.opalcompat.terrain.TerrainIndices.buffer());
		}
		for (ShadowArea area : areas) {
			if (!bindArea(cmd, stack, pipeline, area.area(), type, camX, camY, camZ)) continue;
			for (RenderSection section : area.sections()) drawSection(cmd, section, type);
		}
		vkCmdEndRenderPass(cmd);
	}

	/** Begins a pass over the given shadow colours and {@code shadowtex0}; returns the render pass. */
	private long beginShadowPass(VkCommandBuffer cmd, MemoryStack stack, List<VulkanImage> outputs) {
		for (VulkanImage output : outputs) {
			output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
		}
		this.shadow.depth().transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
		List<Integer> formats = outputs.stream().map(image -> image.format).toList();
		long renderPass = this.renderPasses.renderPass(formats, this.shadow.depthFormat(), true);
		long[] views = new long[outputs.size() + 1];
		for (int i = 0; i < outputs.size(); i++) views[i] = attachmentView(outputs.get(i));
		views[outputs.size()] = attachmentView(this.shadow.depth());
		int size = this.shadow.resolution();
		long framebuffer = this.renderPasses.framebuffer(renderPass, views, size, size);
		beginPass(cmd, stack, renderPass, framebuffer, size, size, false);
		return renderPass;
	}

	private Set<Integer> shadowMips(String name) {
		return this.chain.targets().directives().mipmapRequests().getOrDefault(name, Set.of());
	}

	/**
	 * Shadow composites have their own flip counter, independent of the main colour chain. The order of
	 * the flips is Iris's ({@code ShadowCompositeRenderer}): the pass's outputs unless it says no, then
	 * its explicit ones.
	 */
	private void runShadowComposites(VkCommandBuffer cmd, MemoryStack stack) {
		this.shadowFlips.getOrDefault("shadowcomp_pre", Map.of()).forEach((index, flip) -> {
			if (flip) this.shadow.flip(index);
		});
		VulkanImage screen = screen();
		for (String name : this.shadowCompositeOrder) {
			// Sized off the screen, not the map (ShadowCompositeRenderer.renderAll). A slot may hold computes alone.
			try (MemoryStack frame = stack.push()) {
				dispatch(cmd, frame, this.computes.shadowComposite(name), null, screen.width, screen.height);
			}
			PackProgram.Loaded loaded = this.shadowComposites.get(name);
			if (loaded == null) continue;
			List<Integer> writes = shadowWrites(loaded);
			try (MemoryStack frame = stack.push()) {
				shadowComposite(cmd, frame, name, loaded, writes);
			}
			Map<Integer, Boolean> flips = this.shadowFlips.getOrDefault(name, Map.of());
			for (int index : writes) if (flips.get(index) != Boolean.FALSE) this.shadow.flip(index);
			// Iris applies explicit true flips after the implicit output flips, including duplicates.
			flips.forEach((index, flip) -> { if (flip) this.shadow.flip(index); });
		}
	}

	private void shadowComposite(VkCommandBuffer cmd, MemoryStack stack, String name, PackProgram.Loaded loaded, List<Integer> writes) {
		PackPipeline pipeline = this.shadowCompositePipelines.get(name);
		for (int index : shadowMips(name)) {
			VulkanImage image = this.shadow.sampledColour(index);
			PackDirectives.ShadowColour directive = this.shadow.directive(index);
			int filter = directive != null && directive.format().used().integer() ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
			if (image.mipLevels > 1) mipChain(cmd, stack, image, VK_IMAGE_ASPECT_COLOR_BIT, filter);
		}
		List<VulkanImage> outputs = writes.stream().map(this.shadow::nextColour).toList();
		long set = allocateSet(stack, pipeline.setLayout());
		writeDescriptors(stack, set, pipeline, loaded, this.shadowCompositeBlocks.get(name), outputs, null,
				new GameImages(null, null, null), null);
		for (VulkanImage output : outputs) output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
		long pass = this.renderPasses.renderPass(outputs.stream().map(image -> image.format).toList(), true);
		int size = this.shadow.resolution();
		long framebuffer = this.renderPasses.framebuffer(pass, outputs.stream().mapToLong(PackRenderer::attachmentView).toArray(), size, size);
		PackPipeline.Style style = new PackPipeline.Style(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false, false,
				VK_COMPARE_OP_ALWAYS, VK_CULL_MODE_NONE, 0xF, false, List.of(PackPipeline.Blend.OFF));
		beginPass(cmd, stack, pass, framebuffer, size, size, false);
		applyViewport(cmd, stack, name, size, size);
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline(pass, outputs.size(), style));
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(set), null);
		vkCmdBindVertexBuffers(cmd, 0, stack.longs(this.quad.handle()), stack.longs(0));
		vkCmdDraw(cmd, 6, 1, 0, 0);
		vkCmdEndRenderPass(cmd);
	}

	private void clearShadowMaps(VkCommandBuffer cmd, MemoryStack stack) {
		this.shadow.resetFlips();
		boolean fresh = this.shadowFresh;
		this.shadowFresh = false;
		clearDepth(cmd, stack, this.shadow.depth());
		clearDepth(cmd, stack, this.shadow.depthNoTranslucent());
		this.values.renderStage(RenderStage.NONE);
		dispatch(cmd, stack, this.computes.shadow(), null, this.shadow.resolution(), this.shadow.resolution());
		VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(VK_REMAINING_MIP_LEVELS).layerCount(VK_REMAINING_ARRAY_LAYERS);
		for (Map.Entry<Integer, VulkanImage> entry : this.shadow.colours().entrySet()) {
			PackDirectives.ShadowColour directive = this.shadow.directive(entry.getKey());
			if (!fresh && directive != null && !directive.clear()) continue;
			TargetDirectives.Colour colour = directive == null ? new TargetDirectives.Colour(1.0F, 1.0F, 1.0F, 1.0F) : directive.clearColour();
			VkClearColorValue value = VkClearColorValue.calloc(stack);
			if (directive != null && directive.format().used().integer()) {
				value.int32(0, (int) colour.r()).int32(1, (int) colour.g()).int32(2, (int) colour.b()).int32(3, (int) colour.a());
			} else {
				value.float32(0, colour.r()).float32(1, colour.g()).float32(2, colour.b()).float32(3, colour.a());
			}
			VulkanImage image = entry.getValue();
			image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			vkCmdClearColorImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
			VulkanImage alternate = this.shadow.alternateColours().get(entry.getKey());
			if (alternate != null) {
				alternate.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
				vkCmdClearColorImage(cmd, alternate.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
			}
		}
	}

	private void finishFrame() {
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		VulkanImage screen = screen();
		if (screen == null) return;
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			openFrame(cmd, stack);
			deferred(cmd, stack);
			if (!this.preHandDepthCopied) copyDepth(cmd, stack, this.depth, this.depthNoHand);
			seedFromGame(cmd, stack, screen);
			runPasses(cmd, stack, Integer.MAX_VALUE, "composite");
			Optional<ChainPlan.Pass> last = this.chain.chain().last();
			if (last.isPresent()) {
				dispatchBefore(cmd, stack, screen, last.get().program());
				drawFullscreen(cmd, stack, last.get(), screen);
			} else {
				present(cmd, stack, screen);
			}
			swapBack(cmd, stack);
			if (dumpPrefix != null) dumpTargets(cmd, stack);
			BlinkProbe.record(cmd, stack, screen, Renderer.getCurrentFrame());
			screen.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
		}
		if (!this.announced) {
			this.announced = true;
			OpalCompat.LOGGER.info("[pack] first frame drawn at {}x{}", screen.width, screen.height);
		}
		PipelineCache.saveWhenQuiet();
	}

	/**
	 * The next frame reads the main half of everything again, so what the frame ended on in the alternate one
	 * and is kept between frames comes back here, as in Iris's final pass. Without it a pack's history starts
	 * every frame from its clear: temporal anti-aliasing has nothing to blend with and its noise stays on screen.
	 */
	private void swapBack(VkCommandBuffer cmd, MemoryStack stack) {
		for (int index : this.chain.chain().swapBack()) {
			PackTargets.Target target = this.targets.target(index);
			if (target == null || target.alt() == null) continue;
			VulkanImage from = target.alt();
			VulkanImage to = target.main();
			from.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			to.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(from.width, from.height, 1);
			vkCmdCopyImage(cmd, from.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to.getId(),
					VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
		}
	}

	private void dumpTargets(VkCommandBuffer cmd, MemoryStack stack) {
		String prefix = dumpPrefix;
		dumpPrefix = null;
		TargetDump dump = new TargetDump();
		for (PackTargets.Target target : this.targets.all()) {
			if (target.format().integer()) continue;
			dump.colour(cmd, stack, target.main(), prefix + "_colortex" + target.index() + ".png");
			if (target.alt() != null) dump.colour(cmd, stack, target.alt(), prefix + "_colortex" + target.index() + "_alt.png");
		}
		dump.depth(cmd, stack, this.depth, prefix + "_depthtex0.png");
		dump.depth(cmd, stack, this.depthNoTranslucent, prefix + "_depthtex1.png");
		if (this.distantDepth != null) {
			dump.depth(cmd, stack, this.distantDepth, prefix + "_dhDepthTex0.png");
			dump.depth(cmd, stack, this.distantDepthNoWater, prefix + "_dhDepthTex1.png");
		}
		if (shadowWanted()) {
			dump.depth(cmd, stack, this.shadow.depth(), prefix + "_shadowtex0.png");
			for (Map.Entry<Integer, VulkanImage> entry : this.shadow.colours().entrySet()) {
				dump.colour(cmd, stack, entry.getValue(), prefix + "_shadowcolor" + entry.getKey() + ".png");
			}
		}
		dumping = dump;
	}

	/**
	 * A pack with no program for the world's terrain (a colour grade, say) leaves all of it to VulkanMod,
	 * so what is on the screen's target is the picture its passes are meant to work on. Into the half the
	 * next pass reads. Depth is not brought along: VulkanMod's may be of another format.
	 */
	private void seedFromGame(VkCommandBuffer cmd, MemoryStack stack, VulkanImage screen) {
		if (this.terrainPipelines.containsKey(TerrainPass.SOLID) || this.terrainPipelines.containsKey(TerrainPass.CUTOUT)) return;
		ChainPlan plan = this.chain.chain();
		int index = plan.seed().map(ChainPlan.Seed::target).orElse(0);
		PackTargets.Target target = this.targets.target(index);
		if (target == null || target.format().integer()) return;
		List<ChainPlan.Pass> passes = plan.passes();
		ChainPlan.Pass next = this.nextPass < passes.size() ? passes.get(this.nextPass) : plan.last().orElse(null);
		TargetSchedule.Side side = next == null ? TargetSchedule.Side.MAIN
				: this.chain.targets().schedule().step(next.program()).map(step -> step.read(index)).orElse(TargetSchedule.Side.MAIN);
		blit(cmd, stack, screen, target.image(side));
	}

	private void clearTargets(VkCommandBuffer cmd, MemoryStack stack) {
		TargetDirectives directives = this.chain.targets().directives();
		VkImageSubresourceRange.Buffer colourRange = VkImageSubresourceRange.calloc(1, stack)
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(VK_REMAINING_MIP_LEVELS).layerCount(VK_REMAINING_ARRAY_LAYERS);
		// A target that keeps its contents between frames still starts from something defined. Iris clears
		// everything the first time (ClearPassCreator, fullClear), and a history buffer read uninitialised
		// would carry what it found for as long as the pack blends it into itself.
		boolean fresh = this.targetsFresh;
		this.targetsFresh = false;
		for (PackTargets.Target target : this.targets.all()) {
			if (!fresh && !directives.clears(target.index())) continue;
			TargetDirectives.Colour colour = directives.clearColour(target.index());
			VkClearColorValue value = VkClearColorValue.calloc(stack);
			if (target.index() == 0 && !directives.declaresClearColour(0)) {
				var world = this.values.world();
				value.float32(0, world.fogR()).float32(1, world.fogG()).float32(2, world.fogB()).float32(3, 1.0F);
			} else if (target.format().integer()) {
				value.int32(0, (int) colour.r()).int32(1, (int) colour.g()).int32(2, (int) colour.b()).int32(3, (int) colour.a());
			} else {
				value.float32(0, colour.r()).float32(1, colour.g()).float32(2, colour.b()).float32(3, colour.a());
			}
			for (VulkanImage image : target.alt() == null ? List.of(target.main()) : List.of(target.main(), target.alt())) {
				image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
				vkCmdClearColorImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, colourRange);
			}
		}
		clearDepth(cmd, stack, this.depth);
	}

	static void clearDepth(VkCommandBuffer cmd, MemoryStack stack, VulkanImage image) {
		VkImageSubresourceRange.Buffer depthRange = VkImageSubresourceRange.calloc(1, stack)
				.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
		VkClearDepthStencilValue far = VkClearDepthStencilValue.calloc(stack).depth(1.0F);
		image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		vkCmdClearDepthStencilImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, far, depthRange);
	}

	private static void copyDepth(VkCommandBuffer cmd, MemoryStack stack, VulkanImage from, VulkanImage to) {
		from.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		to.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
		region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
		region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
		region.extent().set(from.width, from.height, 1);
		vkCmdCopyImage(cmd, from.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to.getId(),
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
	}

	/** No {@code final}: the plan's last written target goes to the screen. */
	private void present(VkCommandBuffer cmd, MemoryStack stack, VulkanImage screen) {
		ChainPlan.Attachment attachment = this.chain.chain().present().orElse(null);
		PackTargets.Target target = this.targets.target(attachment == null ? 0 : attachment.target());
		if (target == null) return;
		blit(cmd, stack, attachment == null ? target.main() : target.image(attachment.side()), screen);
	}

	/** A copy turned over: the swapchain's row 0 is the top, the pack's targets' the bottom. */
	private static void blit(VkCommandBuffer cmd, MemoryStack stack, VulkanImage from, VulkanImage to) {
		from.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		to.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
		region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.srcOffsets(0).set(0, 0, 0);
		region.srcOffsets(1).set(from.width, from.height, 1);
		region.dstOffsets(0).set(0, to.height, 0);
		region.dstOffsets(1).set(to.width, 0, 1);
		vkCmdBlitImage(cmd, from.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to.getId(),
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
	}

	/**
	 * Fills the mip chain of each target this pass asked for ({@code colortexNMipmapEnabled}), on the
	 * half it is about to read, as Iris does before the pass.
	 */
	private void generateMips(VkCommandBuffer cmd, MemoryStack stack, ChainPlan.Pass pass, PackProgram.Loaded loaded) {
		Set<Integer> asked = this.chain.targets().directives().mipmapRequests().get(pass.program());
		if (asked == null || asked.isEmpty()) return;
		Set<VulkanImage> done = new HashSet<>();
		for (SamplerPlan.Binding binding : loaded.samplers().bindings()) {
			if (binding.kind() != SamplerPlan.Kind.COLORTEX || !asked.contains(binding.index())) continue;
			PackTargets.Target target = this.targets.target(binding.index());
			if (target == null) continue;
			VulkanImage image = target.image(binding.side());
			if (image.mipLevels > 1 && done.add(image)) mipChain(cmd, stack, image);
		}
	}

	static void mipChain(VkCommandBuffer cmd, MemoryStack stack, VulkanImage image) {
		mipChain(cmd, stack, image, VK_IMAGE_ASPECT_COLOR_BIT, VK_FILTER_LINEAR);
	}

	private static void mipChain(VkCommandBuffer cmd, MemoryStack stack, VulkanImage image, int aspect, int filter) {
		image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(image.getId());
		barrier.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
		int width = image.width;
		int height = image.height;
		for (int level = 1; level < image.mipLevels; level++) {
			int nextWidth = Math.max(1, width / 2);
			int nextHeight = Math.max(1, height / 2);
			barrier.subresourceRange().baseMipLevel(level);
			barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
					.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
			VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
			region.srcSubresource().aspectMask(aspect).mipLevel(level - 1).layerCount(1);
			region.dstSubresource().aspectMask(aspect).mipLevel(level).layerCount(1);
			region.srcOffsets(1).set(width, height, 1);
			region.dstOffsets(1).set(nextWidth, nextHeight, 1);
			vkCmdBlitImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image.getId(),
					VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, filter);
			barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
					.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
			width = nextWidth;
			height = nextHeight;
		}
	}

	private void drawFullscreen(VkCommandBuffer cmd, MemoryStack stack, ChainPlan.Pass pass, VulkanImage screen) {
		PackPipeline pipeline = this.pipelines.get(pass.program());
		PackProgram.Loaded loaded = this.chain.programs().get(pass.program());
		if (pipeline == null || loaded == null) return;
		boolean toScreen = pass.attachments().isEmpty();

		generateMips(cmd, stack, pass, loaded);

		List<VulkanImage> outputs = new ArrayList<>();
		if (toScreen) {
			outputs.add(screen);
		} else {
			for (ChainPlan.Attachment attachment : pass.attachments()) {
				PackTargets.Target target = this.targets.target(attachment.target());
				if (target == null) return;
				outputs.add(target.image(attachment.side()));
			}
		}

		long set = allocateSet(stack, pipeline.setLayout());
		writeDescriptors(stack, set, pipeline, loaded, this.blocks.get(pass.program()), outputs, null,
				new GameImages(null, null, null), null);

		for (VulkanImage output : outputs) {
			output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
		}
		List<Integer> formats = outputs.stream().map(image -> image.format).toList();
		long renderPass = this.renderPasses.renderPass(formats, !toScreen);
		long[] views = outputs.stream().mapToLong(PackRenderer::attachmentView).toArray();
		int width = outputs.stream().mapToInt(image -> image.width).min().orElseThrow();
		int height = outputs.stream().mapToInt(image -> image.height).min().orElseThrow();
		long framebuffer = this.renderPasses.framebuffer(renderPass, views, width, height);

		List<PackPipeline.Blend> blends = blends(pass.program(), pass.program(), outputs.size(), PackPipeline.Blend.OFF);
		PackPipeline.Style style = new PackPipeline.Style(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false, false,
				VK_COMPARE_OP_ALWAYS, VK_CULL_MODE_NONE, 0xF, false, blends);
		beginPass(cmd, stack, renderPass, framebuffer, width, height, toScreen);
		if (!toScreen) applyViewport(cmd, stack, pass.program(), width, height);
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline(renderPass, outputs.size(), style));
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(set), null);
		vkCmdBindVertexBuffers(cmd, 0, stack.longs(this.quad.handle()), stack.longs(0));
		vkCmdDraw(cmd, 6, 1, 0, 0);
		vkCmdEndRenderPass(cmd);
	}

	/** {@code scale.<program> = scale [x y]}, as Iris reads it ({@code ViewportData}). */
	private void applyViewport(VkCommandBuffer cmd, MemoryStack stack, String name, int width, int height) {
		String declared = this.viewportScales.get(name);
		if (declared == null) return;
		try {
			String[] parts = declared.split("\\s+");
			float scale = Float.parseFloat(parts[0]);
			float x = parts.length > 1 ? Float.parseFloat(parts[1]) : 0;
			float y = parts.length > 1 ? Float.parseFloat(parts[2]) : 0;
			if (!Float.isFinite(scale) || !Float.isFinite(x) || !Float.isFinite(y)
					|| scale <= 0 || x < 0 || y < 0 || x + scale > 1 || y + scale > 1) throw new IllegalArgumentException();
			vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x((int) (width * x)).y((int) (height * y))
					.width(Math.max(1, (int) (width * scale))).height(Math.max(1, (int) (height * scale))).minDepth(0).maxDepth(1));
		} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
			if (this.warned.add("scale:" + name)) OpalCompat.LOGGER.warn("[pack] invalid scale.{}={}", name, declared);
		}
	}

	/** The swapchain is shown row 0 first; the pack's targets keep row 0 at the bottom. */
	static void beginPass(VkCommandBuffer cmd, MemoryStack stack, long renderPass, long framebuffer,
			int width, int height, boolean toScreen) {
		VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default()
				.renderPass(renderPass).framebuffer(framebuffer);
		begin.renderArea().extent().set(width, height);
		vkCmdBeginRenderPass(cmd, begin, VK_SUBPASS_CONTENTS_INLINE);
		VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
				.x(0).y(toScreen ? height : 0).width(width).height(toScreen ? -height : height)
				.minDepth(0.0F).maxDepth(1.0F);
		vkCmdSetViewport(cmd, 0, viewport);
		VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
		scissor.extent().set(width, height);
		vkCmdSetScissor(cmd, 0, scissor);
	}

	/**
	 * @param attachedDepth the depth image the draw that binds this set has attached, or null
	 * @param draw          for a mesh draw, what its blocks are written from; null where the caller has set
	 *                      the pass's values itself
	 */
	private void writeDescriptors(MemoryStack stack, long set, PackPipeline pipeline, PackProgram.Loaded loaded,
			UniformBlock block, List<VulkanImage> outputs, VulkanImage attachedDepth, GameImages game, DrawKey draw) {
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(pipeline.slots.size(), stack);
		// Only for a program that touches storage: this stops the whole GPU, and it would be once per draw.
		if (pipeline.slots.stream().anyMatch(slot -> slot.type() == SpirvModule.STORAGE_IMAGE || slot.type() == SpirvModule.STORAGE_BUFFER)) {
			PackStorage.barrier(cmd, stack);
		}
		Set<VulkanImage> stored = new HashSet<>();
		for (PackPipeline.Slot slot : pipeline.slots) {
			if (slot.type() != SpirvModule.STORAGE_IMAGE) continue;
			VulkanImage image = storageImage(slot.name(), loaded);
			if (image == null) throw new IllegalStateException("Unbound storage image " + slot.name() + " in " + loaded.path());
			if (outputs.contains(image)) throw new IllegalStateException("Storage image aliases framebuffer in " + loaded.path());
			stored.add(image);
			image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
		}
		int count = 0;
		for (PackPipeline.Slot slot : pipeline.slots) {
			VkWriteDescriptorSet write = writes.get(count).sType$Default().dstSet(set).dstBinding(slot.binding())
					.descriptorType(slot.type()).descriptorCount(slot.count());
			switch (slot.type()) {
				case SpirvModule.UNIFORM_BUFFER -> {
					FrameArena.Slice slice;
					if ("OfGlobals".equals(slot.name())) {
						slice = globals(block, draw);
					} else if (LegacyGlsl.GAME_TRANSFORMS.equals(slot.name())) {
						slice = draw == null ? writeTransforms(null) : this.drawTransforms.computeIfAbsent(draw, this::writeTransforms);
					} else {
						slice = this.ubo.allocate(256);
						for (int i = 0; i < 256; i++) slice.bytes().put(i, (byte) 0);
					}
					write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(slice.buffer()).offset(slice.offset())
							.range(slice.bytes().capacity()));
				}
				case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC -> write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
						.buffer(this.sectionCorners.buffer()).offset(this.sectionCorners.offset()).range(16));
				case SpirvModule.COMBINED_IMAGE_SAMPLER -> {
					VulkanImage image = sampled(slot, loaded, outputs, attachedDepth, game);
					int layout = stored.contains(image) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
					image.transitionImageLayout(stack, cmd, layout);
					long sampler = slot.depth() ? this.shadow.compare(image) : image.getSampler();
					long view = this.storage.image(slot.name()) == image ? this.storage.view(slot.name()) : image.getImageView();
					VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(slot.count(), stack);
					for (int i = 0; i < slot.count(); i++) {
						info.get(i).imageView(view).sampler(sampler).imageLayout(layout);
					}
					write.pImageInfo(info);
				}
				case SpirvModule.STORAGE_IMAGE -> {
					VulkanImage image = storageImage(slot.name(), loaded);
					long view = this.storage.image(slot.name()) == image ? this.storage.view(slot.name()) : attachmentView(image);
					VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(slot.count(), stack);
					for (int i = 0; i < slot.count(); i++) info.get(i).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
					write.pImageInfo(info);
				}
				case SpirvModule.STORAGE_BUFFER -> {
					var buffer = this.storage.buffer(slot.name());
					if (buffer == null) throw new IllegalStateException("Unbound SSBO " + slot.name() + " in " + loaded.path());
					VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(slot.count(), stack);
					for (int i = 0; i < slot.count(); i++) info.get(i).buffer(buffer.handle()).offset(0).range(buffer.size());
					write.pBufferInfo(info);
				}
				default -> throw new IllegalStateException("Unsupported descriptor " + slot.name() + " in " + loaded.path());
			}
			count++;
		}
		writes.limit(count);
		vkUpdateDescriptorSets(Vk.device(), writes, null);
	}

	/** The pack's block: written once for all the draws of a frame that agree on what it reads per draw. */
	private FrameArena.Slice globals(UniformBlock block, DrawKey draw) {
		if (draw == null) return writeGlobals(block);
		return this.drawGlobals.computeIfAbsent(new GlobalsKey(block, draw), key -> {
			// What is drawn under the level's own projection has the bob split out of it like the level, and only that:
			// the hand and the light have projections of their own.
			boolean level = draw.projection().equals(LevelCapture.projection());
			this.values.modelView(draw.modelView(), level ? LevelCapture.bob() : IDENTITY);
			this.values.projection(level ? LevelCapture.cleanProjection() : draw.projection());
			this.values.passColour(new Vector4f(draw.r(), draw.g(), draw.b(), draw.a()));
			this.values.passAlphaTest(draw.alpha());
			this.values.renderStage(draw.stage());
			return writeGlobals(block);
		});
	}

	private FrameArena.Slice writeGlobals(UniformBlock block) {
		FrameArena.Slice slice = this.ubo.allocate(Math.max(16, block.size()));
		block.write(this.std140.into(slice.bytes()), this.values.world());
		return slice;
	}

	/** {@code GameTransforms.BLOCK}: the model view, the modulator, a model offset of nought, the texture matrix. */
	private FrameArena.Slice writeTransforms(DrawKey draw) {
		FrameArena.Slice slice = this.ubo.allocate(160);
		Matrix4fc modelView = draw == null ? RenderSystem.getModelViewMatrix() : draw.modelView();
		Matrix4fc texture = draw == null ? RenderSystem.getTextureMatrix() : draw.texture();
		float[] colour = draw == null ? RenderSystem.getShaderColor() : new float[] {draw.r(), draw.g(), draw.b(), draw.a()};
		this.std140.into(slice.bytes()).putMat4(modelView).putVec4(colour[0], colour[1], colour[2], colour[3])
				.putVec3(0.0F, 0.0F, 0.0F).putMat4(texture);
		return slice;
	}

	private VulkanImage storageImage(String name, PackProgram.Loaded loaded) {
		VulkanImage image = this.storage.image(name);
		if (image != null) return image;
		SamplerPlan.Binding binding = loaded.samplers().binding(name);
		if (binding != null && binding.kind() == SamplerPlan.Kind.COLOUR_IMAGE) {
			PackTargets.Target target = this.targets.target(binding.index());
			return target == null ? null : target.image(side(binding));
		}
		int shadowColour = PackComputes.shadowColourImage(name);
		return shadowColour < 0 ? null : this.shadow.sampledColour(shadowColour);
	}

	/**
	 * The half a name reads. A compute's own name is in no step of the schedule, so its plan answers main for
	 * everything, and Iris binds it on the halves of the pass it hangs off.
	 */
	private TargetSchedule.Side side(SamplerPlan.Binding binding) {
		return this.dispatchStep != null ? this.dispatchStep.read(binding.index()) : binding.side();
	}

	private VulkanImage sampled(PackPipeline.Slot slot, PackProgram.Loaded loaded, List<VulkanImage> outputs,
			VulkanImage attachedDepth, GameImages game) {
		VulkanImage image = sampledByName(slot.name(), loaded, outputs, attachedDepth, game);
		// A lookup compiled to a depth-reference sample needs a depth map to compare against.
		if (slot.depth() && image != this.shadow.depth() && image != this.shadow.depthNoTranslucent()) {
			image = this.shadow.depth() == attachedDepth ? this.shadow.depthNoTranslucent() : this.shadow.depth();
		}
		// A view of another shape is undefined, and what stands in for a name not served is 2D.
		int wanted = slot.dim() == 0 ? 1 : slot.dim() == 2 ? 3 : 2;
		int shape = image instanceof PackImage pack ? pack.dimensions() : 2;
		return wanted == shape ? image : this.textureImages.blank(wanted);
	}

	private VulkanImage sampledByName(String sampler, PackProgram.Loaded loaded, List<VulkanImage> outputs,
			VulkanImage attachedDepth, GameImages game) {
		String program = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		VulkanImage custom = this.textureImages.resolve(program, sampler);
		if (custom != null) return custom;
		custom = this.storage.image(sampler);
		if (custom != null) return custom;
		if (game.atlas() != null && ATLAS.contains(sampler)) return game.atlas();
		if (game.lightmap() != null && LIGHTMAP.equals(sampler)) return game.lightmap();
		if (LegacyGlsl.OVERLAY_SAMPLER.equals(sampler)) return game.overlay() != null ? game.overlay() : this.noOverlay;
		if ("normals".equals(sampler)) return this.flatNormal;
		if ("specular".equals(sampler)) return this.noSpecular;
		SamplerPlan.Binding binding = loaded.samplers().binding(sampler);
		if (binding != null) {
			switch (binding.kind()) {
				case COLORTEX -> {
					PackTargets.Target target = this.targets.target(binding.index());
					if (target != null) {
						VulkanImage image = target.image(side(binding));
						if (!outputs.contains(image)) return image;
						if (this.warned.add("feedback:" + loaded.path() + ":" + sampler)) {
							// Packs declare every colortex in a shared file, so this is mostly a name nothing reads.
							OpalCompat.LOGGER.debug("[pack] {} reads {} from the half it writes", loaded.path(), sampler);
						}
					}
				}
				case DEPTH, CENTER_DEPTH -> {
					VulkanImage image = switch (sampler) {
						case "depthtex1" -> this.depthNoTranslucent;
						case "depthtex2" -> this.depthNoHand;
						default -> this.depth;
					};
					// GL lets a draw read the depth it is writing; here that is a layout the image is not in.
					return image == attachedDepth ? this.depthNoTranslucent : image;
				}
				case DISTANT_DEPTH -> {
					VulkanImage image = SamplerPlan.distantWithoutWater(sampler) ? this.distantDepthNoWater : this.distantDepth;
					// One drawing into it reads the other; with no far terrain at all the name reads the far plane.
					if (image != null) return image == attachedDepth ? this.distantDepthNoWater : image;
				}
				case NOISE -> {
					return this.noise;
				}
				case SHADOW_DEPTH -> {
					VulkanImage image = loaded.samplers().withoutTranslucents(sampler) ? this.shadow.depthNoTranslucent() : this.shadow.depth();
					return image == attachedDepth ? this.shadow.depthNoTranslucent() : image;
				}
				case SHADOW_COLOUR -> {
					VulkanImage image = this.shadow.sampledColour(binding.index());
					if (!outputs.contains(image)) return image;
				}
				default -> { }
			}
		}
		return VTextureSelector.getWhiteTexture();
	}

	private static long createPool() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(9, stack);
			for (int type = 0; type <= VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC; type++) {
				sizes.get(type).type(type).descriptorCount(type == SpirvModule.COMBINED_IMAGE_SAMPLER ? 32768 : 8192);
			}
			VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(SETS_PER_FRAME).pPoolSizes(sizes);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateDescriptorPool(Vk.device(), info, null, handle), "vkCreateDescriptorPool");
			return handle.get(0);
		}
	}

	/** From this frame's pools, adding one when they run out: how many draws a level makes is not known. */
	private long allocateSet(MemoryStack stack, long layout) {
		LongBuffer handle = stack.mallocLong(1);
		while (true) {
			boolean fresh = this.framePool == this.framePools.size();
			if (fresh) this.framePools.add(createPool());
			VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(this.framePools.get(this.framePool)).pSetLayouts(stack.longs(layout));
			int result = vkAllocateDescriptorSets(Vk.device(), info, handle);
			if (result == VK_SUCCESS) return handle.get(0);
			if (fresh || (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL)) {
				Vk.check(result, "vkAllocateDescriptorSets");
			}
			this.framePool++;
		}
	}

	private void logPlan(long nanos) {
		OpalCompat.LOGGER.info("[pack] {} loaded in {} ms: {} chain programs, terrain {}, targets {} (doubled {})",
				this.packName, nanos / 1_000_000L, this.pipelines.size(),
				this.terrain == null ? "drawn by VulkanMod (mesh not extended)" : this.terrain.programs().keySet(),
				this.chain.targets().allocated(), this.chain.targets().schedule().doubled());
		if (this.terrain != null) {
			this.terrain.programs().forEach((pass, loaded) -> OpalCompat.LOGGER.info("[pack]   terrain {} <- {} -> {}",
					pass, loaded.path(), this.chain.chain().geometry(pass).map(ChainPlan.Pass::attachments).orElse(List.of())));
		}
		this.chain.chain().passes().forEach(this::logPass);
		this.chain.chain().last().ifPresent(this::logPass);
		OpalCompat.LOGGER.info("[pack]   stages: begin<{} prepare<{} deferred<{} of {}", this.chain.chain().beginEnd(),
				this.chain.chain().prepareEnd(), this.chain.chain().deferredEnd(), this.chain.chain().passes().size());
		this.chain.removed().forEach((program, refusal) -> OpalCompat.LOGGER.warn("[pack]   {} removed: {}",
				program, refusal.reason()));
		this.chain.chain().notes().forEach(note -> OpalCompat.LOGGER.info("[pack]   note: {}", note));
	}

	private void logPass(ChainPlan.Pass pass) {
		PackProgram.Loaded loaded = this.chain.programs().get(pass.program());
		String samplers = loaded == null ? "" : loaded.samplers().bindings().stream()
				.map(b -> b.sampler() + "=" + b.kind() + (b.index() >= 0 ? b.index() + "/" + b.side() : ""))
				.toList().toString();
		OpalCompat.LOGGER.info("[pack]   {} -> {} reads {}", pass.program(),
				pass.attachments().isEmpty() ? "screen" : pass.attachments(), samplers);
	}

	private void freeTargets() {
		if (this.targets != null) this.targets.close();
		this.targets = null;
		for (VulkanImage image : new VulkanImage[] {this.depth, this.depthNoTranslucent, this.depthNoHand, this.distantDepth, this.distantDepthNoWater}) {
			if (image != null) image.free();
		}
		this.depth = null;
		this.depthNoTranslucent = null;
		this.depthNoHand = null;
		this.distantDepth = null;
		this.distantDepthNoWater = null;
	}

	/** Everything this pack made, whichever part of it the constructor got to. */
	@Override
	public void close() {
		if (this.storage != null) this.storage.close();
		if (this.textureImages != null) this.textureImages.close();
		if (this.computes != null) this.computes.close();
		if (this.distant != null) this.distant.close();
		this.pipelines.values().forEach(PackPipeline::close);
		this.pipelines.clear();
		this.shadowCompositePipelines.values().forEach(PackPipeline::close);
		this.shadowCompositePipelines.clear();
		this.terrainPipelines.values().forEach(PackPipeline::close);
		this.terrainPipelines.clear();
		if (this.meshPrograms != null) this.meshPrograms.close();
		this.renderPasses.close();
		this.descriptorPools.values().forEach(pools -> pools.forEach(pool -> vkDestroyDescriptorPool(Vk.device(), pool, null)));
		this.descriptorPools.clear();
		this.meshArena.close();
		this.ubo.close();
		if (this.quad != null) this.quad.close();
		for (VulkanImage image : new VulkanImage[] {this.noise, this.flatNormal, this.noSpecular, this.noOverlay}) {
			if (image != null) image.free();
		}
		if (this.shadow != null) this.shadow.close();
		freeTargets();
		closeOpened();
	}
}
