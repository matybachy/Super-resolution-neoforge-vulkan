package dev.opalcompat.compile;

import dev.vitrail.pack.model.ProgramStage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Compiles translated pack GLSL to SPIR-V with shaderc, the same LWJGL shaderc VulkanMod Reforged
 * ships. Its own options rather than VulkanMod's global ones: bindings and locations are left to the
 * compiler ({@code auto_bind_uniforms}, {@code auto_map_locations}), because Vitrail's translator
 * deliberately emits none and expects them to be matched up by name afterwards.
 * <p>
 * Compilation calls are serialised; one instance is meant to be shared.
 */
public final class SpirvCompiler implements AutoCloseable {

	/** {@code name:line: error: text}, which is how glslang words every diagnostic. */
	private static final Pattern DIAGNOSTIC = Pattern.compile("^(.*?):(\\d+): (error|warning): (.*)$");

	public record Diagnostic(String severity, int line, String message, String sourceLine) {
		@Override
		public String toString() {
			return severity + " at line " + line + ": " + message
					+ (sourceLine == null ? "" : "\n        | " + sourceLine.strip());
		}
	}

	/** A compiled unit: the words, or null with the diagnostics that say why not. */
	public record Result(byte[] spirv, List<Diagnostic> diagnostics) {
		public boolean ok() {
			return spirv != null;
		}

		public long errors() {
			return diagnostics.stream().filter(d -> d.severity().equals("error")).count();
		}
	}

	private final long compiler;
	private final long options;

	public SpirvCompiler() {
		this.compiler = shaderc_compiler_initialize();
		if (this.compiler == 0L) throw new IllegalStateException("shaderc_compiler_initialize failed");
		this.options = shaderc_compile_options_initialize();
		shaderc_compile_options_set_source_language(this.options, shaderc_source_language_glsl);
		shaderc_compile_options_set_target_env(this.options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
		// Not "performance": spirv-opt strips OpName, and stages are matched by name afterwards (SpirvModule).
		shaderc_compile_options_set_optimization_level(this.options, shaderc_optimization_level_zero);
		shaderc_compile_options_set_auto_bind_uniforms(this.options, true);
		shaderc_compile_options_set_auto_map_locations(this.options, true);
	}

	public synchronized Result compile(String name, String source, ProgramStage stage) {
		// Encoded off-heap by hand: the CharSequence overload goes through LWJGL's MemoryStack, which a
		// translated unit easily outgrows.
		ByteBuffer text = MemoryUtil.memUTF8(source, false);
		ByteBuffer file = MemoryUtil.memUTF8(name, true);
		ByteBuffer entry = MemoryUtil.memUTF8("main", true);
		long result;
		try {
			result = shaderc_compile_into_spv(this.compiler, text, kindOf(stage), file, entry, this.options);
		} finally {
			MemoryUtil.memFree(text);
			MemoryUtil.memFree(file);
			MemoryUtil.memFree(entry);
		}
		if (result == 0L) {
			return new Result(null, List.of(new Diagnostic("error", 0, "shaderc returned no result", null)));
		}
		try {
			List<Diagnostic> diagnostics = parse(shaderc_result_get_error_message(result), source);
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				if (diagnostics.isEmpty()) {
					diagnostics = List.of(new Diagnostic("error", 0,
							"compilation status " + shaderc_result_get_compilation_status(result), null));
				}
				return new Result(null, diagnostics);
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			byte[] spirv = new byte[bytes.remaining()];
			bytes.get(spirv);
			return new Result(spirv, diagnostics);
		} finally {
			shaderc_result_release(result);
		}
	}

	private static List<Diagnostic> parse(String text, String source) {
		List<Diagnostic> out = new ArrayList<>();
		if (text == null || text.isBlank()) return out;
		String[] lines = source.split("\n", -1);
		for (String raw : text.split("\n")) {
			Matcher m = DIAGNOSTIC.matcher(raw.strip());
			if (m.matches()) {
				int line = Integer.parseInt(m.group(2));
				String at = line >= 1 && line <= lines.length ? lines[line - 1] : null;
				out.add(new Diagnostic(m.group(3), line, m.group(4), at));
			} else if (!raw.isBlank() && !raw.matches("^\\d+ (errors?|warnings?)( and \\d+ warnings?)? generated\\.$")) {
				out.add(new Diagnostic("error", 0, raw.strip(), null));
			}
		}
		return out;
	}

	private static int kindOf(ProgramStage stage) {
		return switch (stage) {
			case VERTEX -> shaderc_glsl_vertex_shader;
			case FRAGMENT -> shaderc_glsl_fragment_shader;
			case GEOMETRY -> shaderc_glsl_geometry_shader;
			case COMPUTE -> shaderc_glsl_compute_shader;
			case TESSELLATION_CONTROL -> shaderc_glsl_tess_control_shader;
			case TESSELLATION_EVALUATION -> shaderc_glsl_tess_evaluation_shader;
		};
	}

	@Override
	public void close() {
		shaderc_compile_options_release(this.options);
		shaderc_compiler_release(this.compiler);
	}
}
