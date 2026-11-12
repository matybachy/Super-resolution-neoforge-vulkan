// OpalCompat: stand-in for Vitrail's HandDraw, of which only drawing() is read by the code taken from
// Vitrail (CapturedProjection). The OpalCompat backend sets it around its own hand pass. LGPL-3.0.
package dev.vitrail.render;

/** Whether the held item / hand is being drawn right now. */
public final class HandDraw {

	private static volatile boolean drawing;

	private HandDraw() {
	}

	public static boolean drawing() {
		return drawing;
	}

	public static void drawing(boolean value) {
		drawing = value;
	}
}
