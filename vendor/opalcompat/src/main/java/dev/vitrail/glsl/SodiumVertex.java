// OpalCompat: replaces Vitrail's SodiumVertex (common/src/main/java/dev/vitrail/glsl/SodiumVertex.java,
// commit 7a3cdcb). The class keeps its name and public interface so the translator that calls it is
// unchanged, but the mesh it decodes is VulkanMod Reforged's compressed terrain format extended by
// OpalCompat (dev.opalcompat.terrain.TerrainFormat), not Sodium's. Written for OpalCompat; the shape of
// the interface is Vitrail's. LGPL-3.0, see NOTICE and NOTICE-vitrail.
package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a VulkanMod Reforged chunk mesh carries once OpalCompat extends it, and how the names a pack
 * reads are made out of it.
 * <p>
 * Forty bytes a vertex. VulkanMod's own twenty: {@code a_Position} four shorts, x y z in 1/2048 of a
 * block relative to the chunk area (minus four blocks, which the region offset puts back) and w the
 * light, block light in the low byte and sky light in the high one, each 0..240; {@code a_Color} the
 * tint with the ambient occlusion multiplied in; {@code a_TexCoord} the atlas coordinate in 1/32768;
 * four bytes VulkanMod declares and never writes. OpalCompat's twenty: {@code a_BlockId} the
 * block.properties id (-1 for none), {@code a_MidTexCoord} the middle of the sprite in 1/32768,
 * {@code a_Normal} and {@code a_Tangent} as signed bytes (tangent w the handedness) and
 * {@code a_MidBlock} the offset to the middle of the block in 1/64 with the light emission in w.
 * <p>
 * The section's place inside its area comes from {@code gl_InstanceIndex}, as in VulkanMod's own
 * terrain shader: its draws carry it as the base instance.
 */
public final class SodiumVertex {

	/** What the prologue is called. The wrapper around the pack's own {@code main} calls it. */
	public static final String PROLOGUE = "ofSodiumVertex";

	public static final String BLOCK_ID = "a_BlockId";
	public static final String MID_TEX_COORD = "a_MidTexCoord";
	public static final String MID_BLOCK = "a_MidBlock";
	public static final String TANGENT_FRAME = "a_Tangent";
	public static final String NORMAL = "a_Normal";
	/** Not carried by this mesh; kept so callers asking for a separate ambient occlusion compile. */
	public static final String TINT_AND_AO = "a_Color";

	/** Every element of the mesh, in buffer order. The mesh always carries all of them. */
	public static final List<String> ATTRIBUTES = List.of("a_Position", "a_Color", "a_TexCoord", BLOCK_ID,
			MID_TEX_COORD, NORMAL, TANGENT_FRAME, MID_BLOCK);

	private static final String OWN_NORMAL = "of_Normal";

	private static final Map<String, String> OUT_OF = outOf();

	/** The pack names this mesh answers. */
	public static final Set<String> ANSWERED = OUT_OF.keySet();

	private static final Map<String, String> FIXED = fixed();

	private SodiumVertex() {
	}

	public static Set<String> reads(Set<String> used, Map<String, String> synthesized, boolean separateAo) {
		Set<String> reads = new LinkedHashSet<>();
		for (String name : VertexPrologue.globals(used, synthesized, FIXED).keySet()) {
			String element = OUT_OF.get(name);
			if (element != null) {
				reads.add(element);
			}
		}
		if (used.contains(OWN_NORMAL)) {
			reads.add(NORMAL);
		}
		return reads;
	}

	/** The format is fixed, so every element is carried whatever the pack reads. */
	public static List<String> carried(Set<String> reads) {
		return ATTRIBUTES;
	}

	public static List<String> prologue(List<String> carried, Set<String> used, Map<String, String> synthesized,
			boolean separateAo) {
		List<String> lines = new ArrayList<>();
		for (String attribute : ATTRIBUTES) {
			lines.add("in " + type(attribute) + " " + attribute + ";");
		}
		lines.add("layout(push_constant) uniform OfVulkanModRegion {");
		lines.add("\tvec3 of_RegionOffset;");
		lines.add("};");
		lines.add("vec4 of_Vertex;");
		lines.add("vec4 of_Color;");
		lines.add("vec4 of_MultiTexCoord0;");
		lines.add("vec4 of_MultiTexCoord1;");
		lines.add("vec3 of_Normal;");
		Map<String, String> globals = VertexPrologue.globals(used, synthesized, FIXED);
		globals.forEach((name, type) -> lines.add(OUT_OF.containsKey(name)
				? type + " " + name + ";"
				: VertexPrologue.declaration(name, type)));
		lines.add("void " + PROLOGUE + "() {");
		lines.add("\tvec3 ofSection = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));");
		lines.add("\tof_Vertex = vec4(vec3(a_Position.xyz) * (1.0 / 2048.0) + of_RegionOffset + ofSection, 1.0);");
		lines.add("\tof_Color = a_Color;");
		lines.add("\tof_MultiTexCoord0 = vec4(vec2(a_TexCoord) * (1.0 / 32768.0), 0.0, 1.0);");
		lines.add("\tuint ofLight = uint(a_Position.w) & 65535u;");
		lines.add("\tof_MultiTexCoord1 = vec4(float(ofLight & 255u), float(ofLight >> 8u), 0.0, 1.0);");
		lines.add("\tof_Normal = normalize(a_Normal.xyz);");
		globals.forEach((name, type) -> {
			if (OUT_OF.containsKey(name)) {
				lines.add("\t" + name + " = " + answer(name, type) + ";");
			}
		});
		lines.add("}");
		return List.copyOf(lines);
	}

	private static String answer(String name, String type) {
		return switch (name) {
			case "mc_midTexCoord" -> midTexCoord(type);
			case "at_midBlock" -> midBlock(type);
			case "at_tangent" -> tangent(type);
			default -> entity(type);
		};
	}

	private static String midTexCoord(String type) {
		String pair = "vec2(" + MID_TEX_COORD + ") * (1.0 / 32768.0)";
		return switch (type) {
			case "float" -> "float(" + MID_TEX_COORD + ".x) * (1.0 / 32768.0)";
			case "vec2" -> pair;
			case "vec3" -> "vec3(" + pair + ", 0.0)";
			case "vec4" -> "vec4(" + pair + ", 0.0, 1.0)";
			default -> VertexPrologue.zero(type);
		};
	}

	private static String tangent(String type) {
		return switch (type) {
			case "float" -> TANGENT_FRAME + ".x";
			case "vec2" -> TANGENT_FRAME + ".xy";
			case "vec3" -> TANGENT_FRAME + ".xyz";
			case "vec4" -> TANGENT_FRAME;
			default -> type.startsWith("mat") ? type + "(1.0)" : VertexPrologue.zero(type);
		};
	}

	private static String midBlock(String type) {
		return switch (type) {
			case "float" -> "float(" + MID_BLOCK + ".x)";
			case "vec2" -> "vec2(" + MID_BLOCK + ".xy)";
			case "vec3" -> "vec3(" + MID_BLOCK + ".xyz)";
			case "vec4" -> "vec4(" + MID_BLOCK + ")";
			case "ivec2" -> MID_BLOCK + ".xy";
			case "ivec3" -> MID_BLOCK + ".xyz";
			case "ivec4" -> MID_BLOCK;
			default -> VertexPrologue.zero(type);
		};
	}

	private static String entity(String type) {
		String id = BLOCK_ID;
		return switch (type) {
			case "float" -> "float(" + id + ")";
			case "int" -> id;
			case "uint" -> "uint(" + id + ")";
			case "vec2" -> "vec2(" + id + ", 0.0)";
			case "vec3" -> "vec3(" + id + ", 0.0, 0.0)";
			case "vec4" -> "vec4(" + id + ", 0.0, 0.0, 1.0)";
			case "ivec2" -> "ivec2(" + id + ", 0)";
			case "ivec3" -> "ivec3(" + id + ", 0, 0)";
			case "ivec4" -> "ivec4(" + id + ", 0, 0, 1)";
			default -> VertexPrologue.zero(type);
		};
	}

	private static String type(String attribute) {
		return switch (attribute) {
			case "a_Position", MID_BLOCK -> "ivec4";
			case "a_TexCoord", MID_TEX_COORD -> "uvec2";
			case BLOCK_ID -> "int";
			default -> "vec4";
		};
	}

	private static Map<String, String> outOf() {
		Map<String, String> names = new LinkedHashMap<>();
		names.put("mc_Entity", BLOCK_ID);
		names.put("mc_midTexCoord", MID_TEX_COORD);
		names.put("at_midBlock", MID_BLOCK);
		names.put("at_tangent", TANGENT_FRAME);
		return Collections.unmodifiableMap(names);
	}

	private static Map<String, String> fixed() {
		Map<String, String> names = new LinkedHashMap<>();
		for (int unit = 2; unit <= 7; unit++) {
			names.put("of_MultiTexCoord" + unit, "vec4");
		}
		return Collections.unmodifiableMap(names);
	}
}
