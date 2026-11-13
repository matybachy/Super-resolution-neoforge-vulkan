package dev.matybachy.srvmr.render;

import dev.matybachy.srvmr.Srvmr;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The depth of the world when an Opal Compat shader pack draws it. The pack draws into targets of its own and leaves
 * VulkanMod Reforged's depth attachment as it was, so the depth that goes with the picture is Opal Compat's
 * {@code depthtex0}: everything drawn, the hand and translucent blocks included, forward 0..1, with row 0 at the bottom
 * where the picture has it at the top.
 * <p>
 * With Distant Horizons the far terrain is in a depth of its own, drawn with the frame's projection but DH's near and
 * far planes ({@code PackRenderer.distantHalf}); where the world's depth is empty, that one says how far the terrain
 * is.
 * <p>
 * Read by reflection, so that Opal Compat is not needed and no version of it is compiled in; if its fields are ever
 * renamed, this returns null and VulkanMod Reforged's depth is used.
 */
final class OpalDepth {

	/** DH's depth and the two numbers of its projection's z row, with the game's, for {@link MotionPass}. */
	record Distant(VulkanImage depth, float dhM22, float dhM32, float gameM22, float gameM32) {
	}

	private static boolean looked;
	private static Field active;
	private static Field depth;
	private static Field distantDepth;
	private static Method planes;
	private static Method packDraws;
	private static Method cleanProjection;
	private static boolean distantBroken;

	private OpalDepth() {
	}

	private static boolean look() {
		if (!looked) {
			looked = true;
			try {
				Class<?> renderer = Class.forName("dev.opalcompat.render.PackRenderer");
				active = open(renderer.getDeclaredField("active"));
				depth = open(renderer.getDeclaredField("depth"));
				packDraws = renderer.getMethod("packDraws");
				Srvmr.LOGGER.info("[dlss] Opal Compat found: with a shader pack its depth is used");
				try {
					distantDepth = open(renderer.getDeclaredField("distantDepth"));
					planes = Class.forName("dev.opalcompat.dh.DhSupport").getMethod("planes");
					cleanProjection = Class.forName("dev.opalcompat.render.LevelCapture").getMethod("cleanProjection");
				} catch (ReflectiveOperationException | RuntimeException e) {
					distantBroken = true;
				}
			} catch (ClassNotFoundException e) {
				active = null;
			} catch (ReflectiveOperationException | RuntimeException e) {
				Srvmr.LOGGER.warn("[dlss] Opal Compat's renderer is not what this was written for; its depth is not used", e);
				active = null;
			}
		}
		return active != null;
	}

	private static Field open(Field field) {
		field.setAccessible(true);
		return field;
	}

	/** Opal Compat's depth while its pack draws the world, or null. */
	static VulkanImage current() {
		if (!look()) return null;
		try {
			if (!(Boolean) packDraws.invoke(null)) return null;
			Object renderer = active.get(null);
			return renderer == null ? null : (VulkanImage) depth.get(renderer);
		} catch (ReflectiveOperationException | RuntimeException e) {
			active = null;
			Srvmr.LOGGER.warn("[dlss] cannot read Opal Compat's depth; it is not used from now on", e);
			return null;
		}
	}

	/** Distant Horizons' depth while the pack draws it, and its planes, or null. */
	static Distant distant() {
		if (!look() || distantBroken) return null;
		try {
			Object renderer = active.get(null);
			if (renderer == null) return null;
			VulkanImage image = (VulkanImage) distantDepth.get(renderer);
			if (image == null) return null;
			// The planes DH drew this frame with ({@code DhSupport.drew}), or null where it drew nothing.
			float[] drawn = (float[]) planes.invoke(null);
			if (drawn == null || drawn.length < 2) return null;
			float near = drawn[0];
			float far = drawn[1];
			if (!(near > 0.0F) || !(far > near)) return null;
			Matrix4fc game = (Matrix4fc) cleanProjection.invoke(null);
			return new Distant(image, (far + near) / (near - far), 2.0F * far * near / (near - far), game.m22(), game.m32());
		} catch (ReflectiveOperationException | RuntimeException e) {
			distantBroken = true;
			Srvmr.LOGGER.warn("[dlss] cannot read Distant Horizons' depth from Opal Compat; far terrain is taken as far away", e);
			return null;
		}
	}

}
