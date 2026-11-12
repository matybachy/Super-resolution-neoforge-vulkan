// OpalCompat: replaces Vitrail's per-game GameTransforms (common/src/mc26.2 and mc26.3), which declare the
// block the way Minecraft 26.x fills it. On VulkanMod Reforged this backend fills the block itself, so
// the order is ours; it keeps the 26.2 order so Emitter's offsets stay as Vitrail wrote them.
// Based on Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb. LGPL-3.0.
package dev.vitrail.glsl;

import java.util.List;

/** The per-draw transforms block, in the order the OpalCompat backend writes it (std140). */
public final class GameTransforms {

	/** The declaration, one line to an entry, as {@link Emitter} writes it into a stage. */
	public static final List<String> BLOCK = List.of(
			"layout(std140) uniform " + LegacyGlsl.GAME_TRANSFORMS + " {",
			"\tmat4 " + LegacyGlsl.GAME_MODEL_VIEW + ";",
			"\tvec4 of_GameColorModulator;",
			"\tvec3 of_GameModelOffset;",
			"\tmat4 " + LegacyGlsl.GAME_TEXTURE_MATRIX + ";",
			"};");

	private GameTransforms() {
	}
}
