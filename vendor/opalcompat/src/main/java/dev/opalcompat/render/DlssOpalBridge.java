package dev.opalcompat.render;

import java.util.logging.Logger;
import org.joml.Matrix4fc;

/**
 * Optional bridge to the standalone DLSS core. Reflection keeps Opal Compat
 * usable when the DLSS integration mod is absent.
 */
public final class DlssOpalBridge {
	private static final Logger LOGGER = Logger.getLogger(DlssOpalBridge.class.getName());
	private static final String PASS = "dev.matybachy.superresolution.CameraMotionVectorPass";
	private static Object pass;
	private static boolean lookedUp;

	private DlssOpalBridge() {
	}

	public static void beginFrame(Matrix4fc modelView, Matrix4fc projection) {
		Object motionPass = motionPass();
		if (motionPass == null) return;
		try {
			float[] matrix = multiply(projection, modelView);
			motionPass.getClass().getMethod("beginFrame", float[].class).invoke(motionPass, (Object) matrix);
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.warning("DLSS camera motion-vector bridge disabled: " + error.getMessage());
			pass = null;
		}
	}

	public static void endFrame() {
		Object motionPass = motionPass();
		if (motionPass == null) return;
		try {
			motionPass.getClass().getMethod("endFrame").invoke(motionPass);
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.warning("DLSS camera motion-vector bridge failed: " + error.getMessage());
			pass = null;
		}
	}

	private static Object motionPass() {
		if (!lookedUp) {
			lookedUp = true;
			try {
				pass = Class.forName(PASS).getConstructor().newInstance();
			} catch (ReflectiveOperationException ignored) {
				pass = null;
			}
		}
		return pass;
	}

	private static float[] multiply(Matrix4fc left, Matrix4fc right) {
		float[] out = new float[16];
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				float value = 0.0F;
				for (int k = 0; k < 4; k++) {
					value += left.get(column * 4 + k) * right.get(k * 4 + row);
				}
				out[column * 4 + row] = value;
			}
		}
		return out;
	}
}
