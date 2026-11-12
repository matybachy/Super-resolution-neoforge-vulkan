package dev.opalcompat.compile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled stage, read just far enough to know its resources and interface, and to renumber them.
 * <p>
 * Vitrail's translator leaves bindings and varying locations to the compiler and expects them to be
 * matched by name afterwards (Minecraft 26.x does that from SPIRV-Cross reflection). shaderc numbers
 * each stage on its own, so a pipeline renumbers its stages here: descriptors by name across the
 * program, fragment inputs onto the vertex outputs of the same name. Only decoration operands are
 * rewritten, in place.
 */
public final class SpirvModule {

	// Opcodes
	private static final int OP_NAME = 5;
	private static final int OP_TYPE_IMAGE = 25;
	private static final int OP_TYPE_SAMPLER = 26;
	private static final int OP_TYPE_SAMPLED_IMAGE = 27;
	private static final int OP_TYPE_ARRAY = 28;
	private static final int OP_TYPE_RUNTIME_ARRAY = 29;
	private static final int OP_TYPE_STRUCT = 30;
	private static final int OP_TYPE_POINTER = 32;
	private static final int OP_CONSTANT = 43;
	private static final int OP_VARIABLE = 59;
	private static final int OP_LOAD = 61;
	private static final int OP_ACCESS_CHAIN = 65;
	private static final int OP_IN_BOUNDS_ACCESS_CHAIN = 66;
	private static final int OP_DECORATE = 71;

	// Decorations
	private static final int DEC_BLOCK = 2;
	private static final int DEC_BUFFER_BLOCK = 3;
	private static final int DEC_BUILTIN = 11;
	private static final int DEC_LOCATION = 30;
	private static final int DEC_BINDING = 33;
	private static final int DEC_DESCRIPTOR_SET = 34;

	// Storage classes
	public static final int SC_UNIFORM_CONSTANT = 0;
	public static final int SC_INPUT = 1;
	public static final int SC_UNIFORM = 2;
	public static final int SC_OUTPUT = 3;
	public static final int SC_STORAGE_BUFFER = 12;

	/** Descriptor kinds, numbered as {@code VkDescriptorType}. */
	public static final int SAMPLER = 0;
	public static final int COMBINED_IMAGE_SAMPLER = 1;
	public static final int SAMPLED_IMAGE = 2;
	public static final int STORAGE_IMAGE = 3;
	public static final int UNIFORM_TEXEL_BUFFER = 4;
	public static final int STORAGE_TEXEL_BUFFER = 5;
	public static final int UNIFORM_BUFFER = 6;
	public static final int STORAGE_BUFFER = 7;

	/**
	 * A descriptor the stage declares. {@code dim} is SPIR-V's ({@code 1} = 2D, {@code 2} = 3D, -1 for
	 * buffers); {@code depth} says whether it is a comparison ("shadow") sampler.
	 */
	public record Resource(String name, int type, int count, int dim, boolean depth, int bindingWord) {
	}

	/**
	 * A user-defined input or output with a location.
	 *
	 * @param read whether the stage's code touches it. Every declared variable is in the entry point's
	 *             interface whether it is read or not, so being listed says nothing.
	 */
	public record Interface(String name, int location, int locationWord, int typeId, boolean read) {
	}

	private final int[] words;
	private final Map<String, Resource> resources = new LinkedHashMap<>();
	private final Map<String, Interface> inputs = new LinkedHashMap<>();
	private final Map<String, Interface> outputs = new LinkedHashMap<>();
	private final List<Integer> descriptorSetWords = new ArrayList<>();

	public SpirvModule(byte[] spirv) {
		IntBuffer view = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		this.words = new int[view.remaining()];
		view.get(this.words);
		if (this.words.length < 5 || this.words[0] != 0x07230203) {
			throw new IllegalArgumentException("Not SPIR-V");
		}
		parse();
	}

	private void parse() {
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, Integer> bindingWord = new HashMap<>();
		Map<Integer, Integer> locationWord = new HashMap<>();
		Map<Integer, Boolean> builtin = new HashMap<>();
		Map<Integer, Integer> blockKind = new HashMap<>();
		Map<Integer, int[]> pointers = new HashMap<>(); // id -> {storage, pointee}
		Map<Integer, int[]> images = new HashMap<>(); // id -> {dim, sampled, depth}
		Map<Integer, Integer> sampledImages = new HashMap<>(); // id -> image type
		Map<Integer, int[]> arrays = new HashMap<>(); // id -> {element, lengthId or -1}
		Map<Integer, Integer> constants = new HashMap<>();
		Map<Integer, Boolean> samplers = new HashMap<>();
		List<int[]> variables = new ArrayList<>(); // {resultType, id, storage}
		java.util.Set<Integer> read = new java.util.HashSet<>();

		int at = 5;
		while (at < this.words.length) {
			int count = this.words[at] >>> 16;
			int op = this.words[at] & 0xFFFF;
			if (count == 0) throw new IllegalArgumentException("Malformed SPIR-V at word " + at);
			switch (op) {
				case OP_NAME -> names.put(this.words[at + 1], string(at + 2, count - 2));
				case OP_DECORATE -> {
					int target = this.words[at + 1];
					int decoration = this.words[at + 2];
					switch (decoration) {
						case DEC_BINDING -> bindingWord.put(target, at + 3);
						case DEC_LOCATION -> locationWord.put(target, at + 3);
						case DEC_DESCRIPTOR_SET -> this.descriptorSetWords.add(at + 3);
						case DEC_BUILTIN -> builtin.put(target, true);
						case DEC_BLOCK -> blockKind.put(target, DEC_BLOCK);
						case DEC_BUFFER_BLOCK -> blockKind.put(target, DEC_BUFFER_BLOCK);
						default -> { }
					}
				}
				case OP_TYPE_POINTER -> pointers.put(this.words[at + 1], new int[] {this.words[at + 2], this.words[at + 3]});
				case OP_TYPE_IMAGE -> images.put(this.words[at + 1],
						new int[] {this.words[at + 3], this.words[at + 7], this.words[at + 4]});
				case OP_TYPE_SAMPLED_IMAGE -> sampledImages.put(this.words[at + 1], this.words[at + 2]);
				case OP_TYPE_SAMPLER -> samplers.put(this.words[at + 1], true);
				case OP_TYPE_ARRAY -> arrays.put(this.words[at + 1], new int[] {this.words[at + 2], this.words[at + 3]});
				case OP_TYPE_RUNTIME_ARRAY -> arrays.put(this.words[at + 1], new int[] {this.words[at + 2], -1});
				case OP_CONSTANT -> constants.put(this.words[at + 2], this.words[at + 3]);
				case OP_VARIABLE -> variables.add(new int[] {this.words[at + 1], this.words[at + 2], this.words[at + 3]});
				case OP_LOAD, OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN -> read.add(this.words[at + 3]);
				default -> { }
			}
			at += count;
		}

		for (int[] variable : variables) {
			int id = variable[1];
			int storage = variable[2];
			int[] pointer = pointers.get(variable[0]);
			if (pointer == null) continue;
			int type = pointer[1];
			String name = names.getOrDefault(id, "");

			if (storage == SC_INPUT || storage == SC_OUTPUT) {
				Integer word = locationWord.get(id);
				if (word == null || builtin.containsKey(id) || name.isEmpty()) continue;
				Interface entry = new Interface(name, this.words[word], word, type, read.contains(id));
				(storage == SC_INPUT ? this.inputs : this.outputs).put(name, entry);
				continue;
			}

			Integer binding = bindingWord.get(id);
			if (binding == null) continue;

			int count = 1;
			int element = type;
			int[] array = arrays.get(element);
			if (array != null) {
				count = array[1] < 0 ? 0 : constants.getOrDefault(array[1], 1);
				element = array[0];
			}

			int descriptor;
			int dim = -1;
			boolean depth = false;
			if (storage == SC_UNIFORM || storage == SC_STORAGE_BUFFER) {
				boolean storageBlock = storage == SC_STORAGE_BUFFER
						|| blockKind.getOrDefault(element, 0) == DEC_BUFFER_BLOCK;
				descriptor = storageBlock ? STORAGE_BUFFER : UNIFORM_BUFFER;
				if (name.isEmpty()) name = names.getOrDefault(element, "");
			} else if (sampledImages.containsKey(element)) {
				int[] image = images.get(sampledImages.get(element));
				dim = image == null ? 1 : image[0];
				depth = image != null && image[2] == 1;
				descriptor = dim == 5 ? UNIFORM_TEXEL_BUFFER : COMBINED_IMAGE_SAMPLER;
			} else if (images.containsKey(element)) {
				int[] image = images.get(element);
				dim = image[0];
				if (dim == 5) {
					descriptor = image[1] == 2 ? STORAGE_TEXEL_BUFFER : UNIFORM_TEXEL_BUFFER;
				} else {
					descriptor = image[1] == 2 ? STORAGE_IMAGE : SAMPLED_IMAGE;
				}
			} else if (samplers.containsKey(element)) {
				descriptor = SAMPLER;
			} else {
				continue;
			}
			if (name.isEmpty()) name = "_unnamed" + id;
			this.resources.put(name, new Resource(name, descriptor, count, dim, depth, binding));
		}
	}

	private String string(int start, int wordCount) {
		byte[] bytes = new byte[wordCount * 4];
		int length = 0;
		for (int i = 0; i < wordCount; i++) {
			int word = this.words[start + i];
			for (int b = 0; b < 4; b++) {
				byte value = (byte) (word >>> (8 * b));
				if (value == 0) return new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
				bytes[length++] = value;
			}
		}
		return new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
	}

	public Map<String, Resource> resources() {
		return this.resources;
	}

	public Map<String, Interface> inputs() {
		return this.inputs;
	}

	public Map<String, Interface> outputs() {
		return this.outputs;
	}

	public void setBinding(String name, int binding) {
		this.words[this.resources.get(name).bindingWord()] = binding;
	}

	public void setInputLocation(String name, int location) {
		this.words[this.inputs.get(name).locationWord()] = location;
	}

	/** Every descriptor into set 0, which is the only set the pipelines here use. */
	public void allInSetZero() {
		for (int word : this.descriptorSetWords) this.words[word] = 0;
	}

	public ByteBuffer code() {
		ByteBuffer out = ByteBuffer.allocateDirect(this.words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		out.asIntBuffer().put(this.words);
		return out;
	}
}
