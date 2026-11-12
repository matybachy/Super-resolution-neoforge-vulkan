package dev.opalcompat.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.vitrail.glsl.DistantVertex;
import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.model.ProgramStage;
import dev.opalcompat.compile.SpirvCompiler;
import dev.opalcompat.compile.SpirvModule;
import dev.opalcompat.dh.LodBuffer;
import dev.opalcompat.terrain.TerrainFormat;
import dev.opalcompat.vk.Vk;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.lwjgl.vulkan.VK10.*;

/**
 * One translated program as Vulkan objects: its shader modules, renumbered so every stage agrees,
 * one descriptor set layout, and a {@code VkPipeline} per render pass and style it is drawn in.
 */
final class PackPipeline implements AutoCloseable {

	/** What the vertex stage reads. */
	enum Kind {
		/** The full-screen quad: {@code Position}, {@code UV0}. */
		QUAD,
		/** VulkanMod Reforged's extended chunk mesh. */
		TERRAIN,
		/** A vanilla vertex format, with what it has not got answered by {@link Constants}. */
		MESH,
		/** Distant Horizons' own LOD mesh. */
		DISTANT,
		COMPUTE
	}

	/** One descriptor of the program, at the binding every stage was renumbered to. */
	record Slot(int binding, String name, int type, int count, int stages, int dim, boolean depth) {
	}

	/** Thrown with the stage and the diagnostics when a stage does not compile. */
	static final class CompileException extends RuntimeException {
		CompileException(String message) {
			super(message);
		}
	}

	/** One attachment's blending, in Vulkan factors. */
	record Blend(boolean on, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
		static final Blend OFF = new Blend(false, 0, 0, 0, 0);
		static final Blend TRANSLUCENT = new Blend(true, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
				VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
	}

	/**
	 * How a pipeline of this program draws, besides the render pass it draws in.
	 *
	 * @param blends one per attachment, or a single one that applies to all of them
	 */
	record Style(int topology, boolean depthTest, boolean depthWrite, int depthOp, int cullMode, int colorMask,
			boolean depthBias, List<Blend> blends) {
		static final Style FULLSCREEN = new Style(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false, false,
				VK_COMPARE_OP_ALWAYS, VK_CULL_MODE_NONE, 0xF, false, List.of(Blend.OFF));

		Blend blend(int attachment) {
			return attachment < this.blends.size() ? this.blends.get(attachment) : this.blends.get(this.blends.size() - 1);
		}
	}

	private record Key(long renderPass, Style style, VertexFormat format, boolean derived) {
	}

	/**
	 * The second vertex binding of a {@link Kind#MESH} pipeline: one record per draw, advanced per
	 * instance, holding what a vertex input the bound format has not got reads.
	 */
	static final class Constants {
		static final int STRIDE = 112;
		static final int COLOR = 0;
		static final int UV0 = 16;
		static final int UV1 = 24;
		static final int UV2 = 32;
		static final int NORMAL = 40;
		static final int IDENTIFIERS = 56;
		static final int MID_TEX = 72;
		static final int TANGENT = 80;
		static final int LINE_WIDTH = 96;

		private Constants() {
		}
	}

	/**
	 * The third vertex binding of a {@link Kind#MESH} pipeline where a program reads these and the
	 * vertices are at hand to work them out from: per vertex, because a batch mixes owners.
	 */
	static final class Derived {
		static final int STRIDE = 28;
		static final int IDENTIFIERS = 0;
		static final int MID_TEX = 16;
		static final int TANGENT = 24;

		private Derived() {
		}
	}

	private static final Map<String, int[]> DERIVED_ATTRIBUTES = Map.of(
			"EntityIds", new int[] {VK_FORMAT_R32G32B32A32_UINT, Derived.IDENTIFIERS},
			"MidTexCoord", new int[] {VK_FORMAT_R32G32_SFLOAT, Derived.MID_TEX},
			"Tangent", new int[] {VK_FORMAT_R8G8B8A8_SNORM, Derived.TANGENT});

	/** Input name to {format, offset} in {@link Constants}. */
	private static final Map<String, int[]> CONSTANT_ATTRIBUTES = Map.of(
			"Color", new int[] {VK_FORMAT_R32G32B32A32_SFLOAT, Constants.COLOR},
			"UV0", new int[] {VK_FORMAT_R32G32_SFLOAT, Constants.UV0},
			"UV1", new int[] {VK_FORMAT_R32G32_SINT, Constants.UV1},
			"UV2", new int[] {VK_FORMAT_R32G32_SINT, Constants.UV2},
			"Normal", new int[] {VK_FORMAT_R32G32B32A32_SFLOAT, Constants.NORMAL},
			"EntityIds", new int[] {VK_FORMAT_R32G32B32A32_UINT, Constants.IDENTIFIERS},
			"MidTexCoord", new int[] {VK_FORMAT_R32G32_SFLOAT, Constants.MID_TEX},
			"Tangent", new int[] {VK_FORMAT_R32G32B32A32_SFLOAT, Constants.TANGENT},
			"LineWidth", new int[] {VK_FORMAT_R32_SFLOAT, Constants.LINE_WIDTH});

	/** Attribute name to {format, offset} for the extended VulkanMod terrain vertex. */
	private static final Map<String, int[]> TERRAIN_ATTRIBUTES = Map.of(
			"a_Position", new int[] {VK_FORMAT_R16G16B16A16_SINT, 0},
			"a_Color", new int[] {VK_FORMAT_R8G8B8A8_UNORM, 8},
			"a_TexCoord", new int[] {VK_FORMAT_R16G16_UINT, 12},
			"a_BlockId", new int[] {VK_FORMAT_R32_SINT, TerrainFormat.OFFSET_BLOCK_ID},
			"a_MidTexCoord", new int[] {VK_FORMAT_R16G16_UINT, TerrainFormat.OFFSET_MID_TEX},
			"a_Normal", new int[] {VK_FORMAT_R8G8B8A8_SNORM, TerrainFormat.OFFSET_NORMAL},
			"a_Tangent", new int[] {VK_FORMAT_R8G8B8A8_SNORM, TerrainFormat.OFFSET_TANGENT},
			"a_MidBlock", new int[] {VK_FORMAT_R8G8B8A8_SINT, TerrainFormat.OFFSET_MID_BLOCK});

	/**
	 * Name to {format, offset} in DH's sixteen byte vertex. The position takes the word after it along, as the
	 * meta it reads as its unused fourth component: three-component 16 bit formats are optional for vertex buffers.
	 */
	private static final Map<String, int[]> DISTANT_ATTRIBUTES = Map.of(
			DistantVertex.POSITION, new int[] {VK_FORMAT_R16G16B16A16_UINT, 0},
			DistantVertex.META, new int[] {VK_FORMAT_R16_UINT, 6},
			DistantVertex.COLOUR, new int[] {VK_FORMAT_R8G8B8A8_UNORM, 8},
			DistantVertex.MATERIAL, new int[] {VK_FORMAT_R8_UINT, 12},
			DistantVertex.NORMAL, new int[] {VK_FORMAT_R8_UINT, 13});

	private static final int QUAD_STRIDE = 20;

	final String name;
	final Kind kind;
	final ProgramTranslator.TranslatedProgram program;
	final List<Slot> slots;
	private final boolean readsDerived;
	private final Map<ProgramStage, Long> modules = new LinkedHashMap<>();
	private final Map<String, Integer> vertexInputs = new HashMap<>();
	private final long setLayout;
	private final long layout;
	/** Read by the render thread, written by it and, for a {@link #builder}, by that thread too. */
	private final Map<Key, Long> pipelines = new ConcurrentHashMap<>();
	/** Render thread only: what {@link #builder} is building. */
	private final Map<Key, Future<Long>> building = new HashMap<>();
	/** Where pipelines are built, if not on the render thread; see {@link #readyPipeline}. */
	private final ExecutorService builder;
	private long computePipeline;

	PackPipeline(String name, ProgramTranslator.TranslatedProgram program, SpirvCompiler compiler, Kind kind) {
		this(name, program, compiler, kind, null);
	}

	PackPipeline(String name, ProgramTranslator.TranslatedProgram program, SpirvCompiler compiler, Kind kind,
			ExecutorService builder) {
		this.builder = builder;
		this.name = name;
		this.kind = kind;
		this.program = program;
		boolean derived = false;

		Map<String, Slot> byName = new LinkedHashMap<>();
		if (kind == Kind.COMPUTE) {
			TranslatedUnit unit = program.stages().get(ProgramStage.COMPUTE);
			if (unit == null) throw new CompileException(name + " has no compute stage");
			SpirvModule compute = compile(compiler, name, unit);
			collect(byName, compute, VK_SHADER_STAGE_COMPUTE_BIT);
			this.slots = List.copyOf(byName.values());
			renumber(compute);
			this.modules.put(ProgramStage.COMPUTE, module(compute.code()));
		} else {
			TranslatedUnit vertexUnit = program.stages().get(ProgramStage.VERTEX);
			TranslatedUnit geometryUnit = program.stages().get(ProgramStage.GEOMETRY);
			TranslatedUnit fragmentUnit = program.stages().get(ProgramStage.FRAGMENT);
			if (vertexUnit == null || fragmentUnit == null) {
				throw new CompileException(name + " has no vertex and fragment stage pair");
			}
			SpirvModule vertex = compile(compiler, name, vertexUnit);
			SpirvModule geometry = geometryUnit == null ? null : compile(compiler, name, geometryUnit);
			SpirvModule fragment = compile(compiler, name, fragmentUnit);

			// Descriptors by name across the stages, numbered in order of first appearance.
			collect(byName, vertex, VK_SHADER_STAGE_VERTEX_BIT);
			if (geometry != null) collect(byName, geometry, VK_SHADER_STAGE_GEOMETRY_BIT);
			collect(byName, fragment, VK_SHADER_STAGE_FRAGMENT_BIT);
			// One corner per section, and one set for all of them: a dynamic offset moves between draws.
			if (kind == Kind.DISTANT) {
				byName.computeIfPresent(DistantVertex.SECTION_BLOCK, (slotName, slot) -> new Slot(slot.binding(), slot.name(),
						VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, slot.count(), slot.stages(), slot.dim(), slot.depth()));
			}
			this.slots = List.copyOf(byName.values());
			renumber(vertex);
			if (geometry != null) renumber(geometry);
			renumber(fragment);

			// Each stage's inputs onto the previous stage's outputs of the same name.
			if (geometry != null) link(vertex, geometry);
			link(geometry != null ? geometry : vertex, fragment);

			vertex.inputs().forEach((inputName, entry) -> this.vertexInputs.put(inputName, entry.location()));
			for (String input : DERIVED_ATTRIBUTES.keySet()) {
				SpirvModule.Interface entry = vertex.inputs().get(input);
				derived |= entry != null && entry.read();
			}
			this.modules.put(ProgramStage.VERTEX, module(vertex.code()));
			if (geometry != null) this.modules.put(ProgramStage.GEOMETRY, module(geometry.code()));
			this.modules.put(ProgramStage.FRAGMENT, module(fragment.code()));
		}
		this.readsDerived = derived;
		this.setLayout = createSetLayout();
		this.layout = createLayout();
	}

	/** Whether this program's vertex stage reads anything {@link Derived} holds. */
	boolean readsDerived() {
		return this.readsDerived;
	}

	private void renumber(SpirvModule module) {
		for (Slot slot : this.slots) {
			if (module.resources().containsKey(slot.name())) module.setBinding(slot.name(), slot.binding());
		}
		module.allInSetZero();
	}

	/** Anything the earlier stage does not write is put past all of it, where it reads nothing else's varying. */
	private static void link(SpirvModule from, SpirvModule to) {
		int spare = from.outputs().values().stream().mapToInt(SpirvModule.Interface::location).max().orElse(-1) + 4;
		for (SpirvModule.Interface input : to.inputs().values()) {
			SpirvModule.Interface output = from.outputs().get(input.name());
			if (output != null) {
				to.setInputLocation(input.name(), output.location());
			} else {
				to.setInputLocation(input.name(), spare);
				spare += 4;
			}
		}
	}

	private static SpirvModule compile(SpirvCompiler compiler, String name, TranslatedUnit unit) {
		String file = name + "." + unit.stage().extension();
		SpirvCompiler.Result result = compiler.compile(file, unit.text(), unit.stage());
		if (!result.ok()) {
			StringBuilder message = new StringBuilder(file).append(" does not compile:");
			result.diagnostics().forEach(d -> message.append("\n    ").append(d));
			throw new CompileException(message.toString());
		}
		return new SpirvModule(result.spirv());
	}

	private static void collect(Map<String, Slot> into, SpirvModule module, int stage) {
		for (SpirvModule.Resource resource : module.resources().values()) {
			Slot known = into.get(resource.name());
			if (known == null) {
				into.put(resource.name(), new Slot(into.size(), resource.name(), resource.type(),
						Math.max(1, resource.count()), stage, resource.dim(), resource.depth()));
			} else {
				into.put(resource.name(), new Slot(known.binding(), known.name(), known.type(), known.count(),
						known.stages() | stage, known.dim(), known.depth()));
			}
		}
	}

	private static long module(ByteBuffer code) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateShaderModule(Vk.device(), info, null, handle), "vkCreateShaderModule");
			return handle.get(0);
		}
	}

	private long createSetLayout() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(this.slots.size(), stack);
			for (int i = 0; i < this.slots.size(); i++) {
				Slot slot = this.slots.get(i);
				bindings.get(i).binding(slot.binding()).descriptorType(slot.type())
						.descriptorCount(slot.count()).stageFlags(slot.stages());
			}
			VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
					.pBindings(bindings);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateDescriptorSetLayout(Vk.device(), info, null, handle), "vkCreateDescriptorSetLayout");
			return handle.get(0);
		}
	}

	private long createLayout() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(this.setLayout));
			if (this.kind == Kind.TERRAIN) {
				// The region offset, a vec3, as VulkanMod pushes it for its own terrain shader.
				info.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
						.stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(16));
			}
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreatePipelineLayout(Vk.device(), info, null, handle), "vkCreatePipelineLayout");
			return handle.get(0);
		}
	}

	long setLayout() {
		return this.setLayout;
	}

	long layout() {
		return this.layout;
	}

	long compute() {
		if (this.computePipeline != 0L) return this.computePipeline;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
					.layout(this.layout);
			info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
					.module(this.modules.get(ProgramStage.COMPUTE)).pName(stack.UTF8("main"));
			LongBuffer handle = stack.mallocLong(1);
			long started = System.nanoTime();
			Vk.check(vkCreateComputePipelines(Vk.device(), PipelineCache.handle(), info, null, handle),
					"vkCreateComputePipelines " + this.name);
			PipelineCache.created(started);
			this.computePipeline = handle.get(0);
			return this.computePipeline;
		}
	}

	/** The pipeline for a render pass with {@code attachments} colour attachments. */
	long pipeline(long renderPass, int attachments, Style style) {
		return pipeline(renderPass, attachments, style, null, false);
	}

	/**
	 * @param format  the vertex format of a {@link Kind#MESH} draw
	 * @param derived whether the draw binds a {@link Derived} stream
	 */
	long pipeline(long renderPass, int attachments, Style style, VertexFormat format, boolean derived) {
		return this.pipelines.computeIfAbsent(new Key(renderPass, style, format, derived),
				key -> create(renderPass, attachments, style, format, derived));
	}

	/**
	 * The pipeline if it is there, otherwise 0 and it is built on {@link #builder}: a driver takes up to a second over a
	 * pipeline of a large pack it has not seen, and the frame that asked would wait for all of it. The draw asking is
	 * left out until then. With no builder this is {@link #pipeline}.
	 */
	long readyPipeline(long renderPass, int attachments, Style style, VertexFormat format, boolean derived) {
		Key key = new Key(renderPass, style, format, derived);
		Long built = this.pipelines.get(key);
		if (built != null) return built;
		if (this.builder == null) return pipeline(renderPass, attachments, style, format, derived);
		Future<Long> future = this.building.get(key);
		if (future == null) {
			try {
				this.building.put(key, this.builder.submit(() -> create(renderPass, attachments, style, format, derived)));
			} catch (java.util.concurrent.RejectedExecutionException e) {
				// The pack is being closed.
			}
			return 0L;
		}
		if (!future.isDone()) return 0L;
		this.building.remove(key);
		try {
			long handle = future.get();
			this.pipelines.put(key, handle);
			return handle;
		} catch (ExecutionException e) {
			// As if it had been built here: the pack fails the way it would have.
			if (e.getCause() instanceof RuntimeException failure) throw failure;
			throw new CompileException(this.name + ": " + e.getCause());
		} catch (InterruptedException | CancellationException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			return 0L;
		}
	}

	private long create(long renderPass, int attachments, Style style, VertexFormat format, boolean derived) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(this.modules.size(), stack);
			int stageIndex = 0;
			for (Map.Entry<ProgramStage, Long> module : this.modules.entrySet()) {
				int bit = switch (module.getKey()) {
					case VERTEX -> VK_SHADER_STAGE_VERTEX_BIT;
					case GEOMETRY -> VK_SHADER_STAGE_GEOMETRY_BIT;
					default -> VK_SHADER_STAGE_FRAGMENT_BIT;
				};
				stages.get(stageIndex++).sType$Default().stage(bit).module(module.getValue()).pName(stack.UTF8("main"));
			}

			List<int[]> attributes = new ArrayList<>(); // location, binding, format, offset
			int stride;
			switch (this.kind) {
				case TERRAIN -> {
					stride = TerrainFormat.STRIDE;
					this.vertexInputs.forEach((input, location) -> {
						int[] element = TERRAIN_ATTRIBUTES.get(input);
						if (element == null) {
							throw new CompileException(this.name + " reads a vertex input the terrain mesh has not got: " + input);
						}
						attributes.add(new int[] {location, 0, element[0], element[1]});
					});
				}
				case DISTANT -> {
					stride = LodBuffer.STRIDE;
					this.vertexInputs.forEach((input, location) -> {
						int[] element = DISTANT_ATTRIBUTES.get(input);
						if (element == null) {
							throw new CompileException(this.name + " reads a vertex input the far terrain mesh has not got: " + input);
						}
						attributes.add(new int[] {location, 0, element[0], element[1]});
					});
				}
				case MESH -> {
					stride = format.getVertexSize();
					List<String> names = format.getElementAttributeNames();
					this.vertexInputs.forEach((input, location) -> {
						int index = names.indexOf(input);
						int[] fromDerived = derived ? DERIVED_ATTRIBUTES.get(input) : null;
						if (index >= 0) {
							VertexFormatElement element = format.getElements().get(index);
							attributes.add(new int[] {location, 0, elementFormat(element), format.getOffset(element)});
						} else if (fromDerived != null) {
							attributes.add(new int[] {location, 2, fromDerived[0], fromDerived[1]});
						} else {
							int[] constant = CONSTANT_ATTRIBUTES.get(input);
							if (constant == null) {
								throw new CompileException(this.name + " reads a vertex input nothing answers: " + input);
							}
							attributes.add(new int[] {location, 1, constant[0], constant[1]});
						}
					});
				}
				default -> {
					stride = QUAD_STRIDE;
					Integer position = this.vertexInputs.get("Position");
					Integer uv = this.vertexInputs.get("UV0");
					if (position != null) attributes.add(new int[] {position, 0, VK_FORMAT_R32G32B32_SFLOAT, 0});
					if (uv != null) attributes.add(new int[] {uv, 0, VK_FORMAT_R32G32_SFLOAT, 12});
				}
			}
			boolean constants = this.kind == Kind.MESH;
			VkVertexInputBindingDescription.Buffer binding =
					VkVertexInputBindingDescription.calloc(constants ? derived ? 3 : 2 : 1, stack);
			binding.get(0).binding(0).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
			if (constants) binding.get(1).binding(1).stride(Constants.STRIDE).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
			if (constants && derived) binding.get(2).binding(2).stride(Derived.STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
			VkVertexInputAttributeDescription.Buffer attributeInfo = VkVertexInputAttributeDescription.calloc(attributes.size(), stack);
			for (int i = 0; i < attributes.size(); i++) {
				int[] attribute = attributes.get(i);
				attributeInfo.get(i).location(attribute[0]).binding(attribute[1]).format(attribute[2]).offset(attribute[3]);
			}
			VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
					.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributeInfo);
			VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
					.topology(style.topology());
			VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
					.viewportCount(1).scissorCount(1);
			VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					.polygonMode(VK_POLYGON_MODE_FILL).cullMode(style.cullMode())
					// The pack draws in GL orientation without a viewport flip, so GL counter-clockwise
					// comes out clockwise in Vulkan's framebuffer coordinates.
					.frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0F).depthBiasEnable(style.depthBias());
			VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
					.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
			VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(style.depthTest()).depthWriteEnable(style.depthWrite())
					.depthCompareOp(style.depthOp());
			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(Math.max(1, attachments), stack);
			for (int i = 0; i < attachments; i++) {
				Blend mode = style.blend(i);
				blend.get(i).colorWriteMask(style.colorMask()).blendEnable(mode.on());
				if (mode.on()) {
					blend.get(i).srcColorBlendFactor(mode.srcRgb()).dstColorBlendFactor(mode.dstRgb())
							.colorBlendOp(VK_BLEND_OP_ADD)
							.srcAlphaBlendFactor(mode.srcAlpha()).dstAlphaBlendFactor(mode.dstAlpha())
							.alphaBlendOp(VK_BLEND_OP_ADD);
				}
			}
			VkPipelineColorBlendStateCreateInfo colourBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
			// A map drawn with depth alone has no attachments; the setter counts what it is handed.
			if (attachments > 0) colourBlend.pAttachments(blend);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					// VulkanMod Reforged's own dynamic states as well: binding a pipeline that has one of
					// them static would invalidate what VulkanMod set, and its next draw would use it unset.
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
							VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_LINE_WIDTH, VK_DYNAMIC_STATE_BLEND_CONSTANTS,
							VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK, VK_DYNAMIC_STATE_STENCIL_WRITE_MASK,
							VK_DYNAMIC_STATE_STENCIL_REFERENCE));

			VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
					.pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(assembly)
					.pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample)
					.pDepthStencilState(depth).pColorBlendState(colourBlend).pDynamicState(dynamic)
					.layout(this.layout).renderPass(renderPass).subpass(0);
			LongBuffer handle = stack.mallocLong(1);
			long started = System.nanoTime();
			Vk.check(vkCreateGraphicsPipelines(Vk.device(), PipelineCache.handle(), info, null, handle),
					"vkCreateGraphicsPipelines " + this.name);
			PipelineCache.created(started);
			return handle.get(0);
		}
	}

	private int elementFormat(VertexFormatElement element) {
		int count = element.count();
		return switch (element.type()) {
			case FLOAT -> switch (count) {
				case 1 -> VK_FORMAT_R32_SFLOAT;
				case 2 -> VK_FORMAT_R32G32_SFLOAT;
				case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
				default -> VK_FORMAT_R32G32B32A32_SFLOAT;
			};
			case UBYTE -> VK_FORMAT_R8G8B8A8_UNORM;
			// A normal: three bytes and the format's padding, read as four.
			case BYTE -> VK_FORMAT_R8G8B8A8_SNORM;
			case SHORT -> count == 2 ? VK_FORMAT_R16G16_SINT : VK_FORMAT_R16G16B16A16_SINT;
			case USHORT -> count == 2 ? VK_FORMAT_R16G16_UINT : VK_FORMAT_R16G16B16A16_UINT;
			case INT -> VK_FORMAT_R32_SINT;
			case UINT -> VK_FORMAT_R32_UINT;
		};
	}

	/** OptiFine's blend factor names, as Vitrail keeps them (upper case, GL spelling). */
	static int factor(String name) {
		return switch (name.startsWith("GL_") ? name.substring(3) : name) {
			case "ZERO" -> VK_BLEND_FACTOR_ZERO;
			case "ONE" -> VK_BLEND_FACTOR_ONE;
			case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
			case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
			case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
			case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
			case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
			case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
			case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
			case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
			case "SRC_ALPHA_SATURATE" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
			default -> throw new CompileException("Unknown blend factor " + name);
		};
	}

	/** After {@link #builder} has stopped: what it finished is destroyed with the rest. */
	@Override
	public void close() {
		for (Future<Long> future : this.building.values()) {
			if (!future.isDone() || future.isCancelled()) continue;
			try {
				vkDestroyPipeline(Vk.device(), future.get(), null);
			} catch (ExecutionException | InterruptedException e) {
				// Never built.
			}
		}
		this.building.clear();
		this.pipelines.values().forEach(p -> vkDestroyPipeline(Vk.device(), p, null));
		this.pipelines.clear();
		if (this.computePipeline != 0L) vkDestroyPipeline(Vk.device(), this.computePipeline, null);
		vkDestroyPipelineLayout(Vk.device(), this.layout, null);
		vkDestroyDescriptorSetLayout(Vk.device(), this.setLayout, null);
		this.modules.values().forEach(module -> vkDestroyShaderModule(Vk.device(), module, null));
		this.modules.clear();
	}
}
