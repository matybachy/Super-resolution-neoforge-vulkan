package dev.opalcompat.render;

import java.util.logging.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Optional bridge to the standalone DLSS core. Reflection keeps Opal Compat
 * usable when the DLSS integration mod is absent.
 */
public final class DlssOpalBridge {
	private static final Logger LOGGER = Logger.getLogger(DlssOpalBridge.class.getName());
	private static final String PASS = "dev.matybachy.superresolution.CameraMotionVectorPass";
	private static final String INSTALLER = "dev.matybachy.superresolution.VulkanResolveInstaller";
	private static boolean installed;
	private static Object pass;
	private static boolean lookedUp;
	private static int frameIndex;
	private static float jitterX;
	private static float jitterY;

	private DlssOpalBridge() {
	}

	public static void applyJitter(Matrix4f projection, int width, int height) {
		if (!Boolean.getBoolean("superresolution.dlss") || width <= 0 || height <= 0) return;
		frameIndex++;
		jitterX = halton(frameIndex, 2) - 0.5F;
		jitterY = halton(frameIndex, 3) - 0.5F;
		projection.m20(projection.m20() + (2.0F * jitterX) / width);
		projection.m21(projection.m21() + (2.0F * jitterY) / height);
	}

	public static void beginFrame(Matrix4fc modelView, Matrix4fc projection) {
		if (!Boolean.getBoolean("superresolution.dlss")) return;
		install();
		Object motionPass = motionPass();
		if (motionPass == null) return;
		try {
			float[] matrix = multiply(projection, modelView);
			motionPass.getClass().getMethod("beginFrame", float[].class).invoke(motionPass, (Object) matrix);
			Class<?> installer = Class.forName(INSTALLER);
			installer.getMethod("beginWorldFrame", float[].class).invoke(null, (Object) matrix);
			int width = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
			int height = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
			Class<?> input = Class.forName("dev.matybachy.superresolution.FrameInput");
			Object frame = input.getConstructor(float.class, float.class, float.class, float.class,
					float.class, float.class, float.class, boolean.class, int.class, int.class,
					int.class, int.class).newInstance(jitterX, jitterY, (float) width, (float) height,
					1.0F, 1.0F, 0.016F, false, width, height, width, height);
			installer.getMethod("setFrameInput", input).invoke(null, frame);
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.warning("DLSS camera motion-vector bridge disabled: " + error.getMessage());
			pass = null;
		}
	}

	private static void install() {
		if (installed) return;
		try {
			Class<?> installer = Class.forName(INSTALLER);
			Class<?> configType = Class.forName("dev.matybachy.superresolution.DlssConfig");
			Class<?> algorithmType = Class.forName("dev.matybachy.superresolution.DlssAlgorithm");
			Object algorithm = Enum.valueOf((Class) algorithmType,
					System.getProperty("superresolution.dlss.quality", "QUALITY"));
			Object config = configType.getConstructor(boolean.class, algorithmType, float.class)
					.newInstance(true, algorithm, 0.2F);
			int width = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
			int height = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
			installed = (boolean) installer.getMethod("install", configType, int.class, int.class)
					.invoke(null, config, width, height);
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.warning("DLSS automatic bootstrap unavailable: " + error.getMessage());
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

	private static float halton(int index, int base) {
		float result = 0.0F;
		float fraction = 1.0F / base;
		while (index > 0) {
			result += (index % base) * fraction;
			index /= base;
			fraction /= base;
		}
		return result;
	}
}
