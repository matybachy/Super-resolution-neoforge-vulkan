// OpalCompat: stand-in for Vitrail's FaceShading (common/src/main/java/dev/vitrail/render/FaceShading.java),
// which switches Sodium's per-face shading in the chunk mesh. Here only the pack's request is kept. LGPL-3.0.
package dev.vitrail.render;

/** Whether the pack asked for the game's own per-face brightness ({@code oldLighting}). */
public final class FaceShading {

	private static volatile boolean oldLighting = true;

	private FaceShading() {
	}

	public static void install(boolean packOldLighting) {
		oldLighting = packOldLighting;
	}

	public static boolean oldLighting() {
		return oldLighting;
	}
}
