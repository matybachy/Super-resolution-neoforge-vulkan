package dev.opalcompat.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The matrices the level is drawn with this frame, captured from the arguments of
 * {@code LevelRenderer.renderLevel}. The projection is the OpenGL form, z from -1 to 1. VulkanMod Reforged's
 * {@code Matrix4fM} would force 0..1, but it never applies on NeoForge: JOML is loaded outside the transformer, and a
 * mixin export of a running game has nothing under {@code org/joml}. Its far plane is one a pack can use while one draws
 * ({@code GameRendererDepthFarMixin}). The model view is the camera rotation only.
 */
public final class LevelCapture {

	private static final Matrix4f MODEL_VIEW = new Matrix4f();
	private static final Matrix4f PROJECTION = new Matrix4f();
	private static final Matrix4f CLEAN_PROJECTION = new Matrix4f();
	private static final Matrix4f BOB = new Matrix4f();
	private static boolean cleanHeld;

	private LevelCapture() {
	}

	/** The camera's own projection, as {@code GameRenderer.renderLevel} first builds it, ahead of {@link #capture}. */
	public static void clean(Matrix4fc projection) {
		CLEAN_PROJECTION.set(projection);
		cleanHeld = true;
	}

	public static void capture(Matrix4fc modelView, Matrix4fc projection) {
		MODEL_VIEW.set(modelView);
		PROJECTION.set(projection);
		// A level drawn from anywhere else has been through no such step, and is left as it came.
		if (!cleanHeld) CLEAN_PROJECTION.set(projection);
		cleanHeld = false;
		if (CLEAN_PROJECTION.equals(projection)) {
			BOB.identity();
		} else {
			CLEAN_PROJECTION.invert(BOB).mul(projection);
			// Translations, rotations and a scale: what the last row holds beyond this is rounding.
			BOB.m03(0.0F).m13(0.0F).m23(0.0F).m33(1.0F);
		}
	}

	/**
	 * What the game multiplied onto its projection: the walk bob, the damage tilt and the nausea. A pack expects them in
	 * the model view, as OptiFine and Iris's {@code MixinModelViewBobbing} put them. Its {@code ScreenToView} reads the
	 * projection as a diagonal, so left in there every position a pack rebuilds from depth swings with each step, and the
	 * shadows, reflections and history read at those positions with it. This times {@link #cleanProjection} is what the
	 * world is drawn with either way.
	 */
	public static Matrix4fc bob() {
		return BOB;
	}

	public static Matrix4fc cleanProjection() {
		return CLEAN_PROJECTION;
	}

	public static Matrix4fc modelView() {
		return MODEL_VIEW;
	}

	public static Matrix4fc projection() {
		return PROJECTION;
	}

	/**
	 * Rewrites an OpenGL form matrix as the reversed 0..1 one Vitrail's {@code ViewMatrices} expects: the z row
	 * becomes {@code (rowW - rowZ) / 2}. Vitrail's {@code ClipSpace.toLegacyDepth}, {@code rowW - 2 * rowZ}, then
	 * gives back the matrix this was handed, so a pack reads what it would read under Iris.
	 */
	public static Matrix4f glToReversed(Matrix4fc gl, Matrix4f dest) {
		float m02 = (gl.m03() - gl.m02()) * 0.5F;
		float m12 = (gl.m13() - gl.m12()) * 0.5F;
		float m22 = (gl.m23() - gl.m22()) * 0.5F;
		float m32 = (gl.m33() - gl.m32()) * 0.5F;
		dest.set(gl);
		dest.m02(m02);
		dest.m12(m12);
		dest.m22(m22);
		dest.m32(m32);
		return dest;
	}
}
