package dev.matybachy.srvmr.render;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.SrvmrConfig;
import dev.matybachy.srvmr.ngx.Ngx;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.MainDepthSnapshot;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;

/**
 * One frame of the world as DLSS sees it: whether it is drawn for DLSS at all, the jitter it is drawn with, the camera
 * and the entities, against the frame DLSS last upscaled. Render thread.
 * <p>
 * A frame is drawn for DLSS when VulkanMod Reforged draws it at its Render Scale, which is when no screen is open and
 * the scale is below 100 %. Only such a frame is jittered: any other is drawn as the game draws it.
 * <p>
 * The jitter moves the whole picture by less than a pixel, differently each frame, in a Halton (2, 3) sequence of
 * {@code 8 * (output / render)^2} steps as NVIDIA's guide puts it. It is applied to the projection in clip space, so
 * every draw made with the game's projection moves with it, and told to DLSS in render pixels, x to the right and y
 * down, which is how the picture lies in VulkanMod Reforged's target.
 * <p>
 * Without a shader pack the game clears the depth before it draws the hand, so the depth of the world is gone by the
 * time DLSS runs. It is copied just before that clear, with VulkanMod Reforged's own depth snapshot.
 */
public final class DlssFrame {

	/** A camera that moves further than this between two frames has jumped: DLSS starts its history afresh. */
	private static final double JUMP = 16.0;

	static boolean planned;
	static boolean captured;
	static int renderWidth;
	static int renderHeight;
	static int outputWidth;
	static int outputHeight;
	/** Render pixels, x right, y down. */
	static float jitterX;
	static float jitterY;
	private static long sequence;

	private static final Matrix4f MODEL_VIEW = new Matrix4f();
	private static final Matrix4f PROJECTION = new Matrix4f();
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static Object level;

	private static boolean hasPrevious;
	private static final Matrix4f PREVIOUS_MODEL_VIEW = new Matrix4f();
	private static final Matrix4f PREVIOUS_PROJECTION = new Matrix4f();
	private static double previousX;
	private static double previousY;
	private static double previousZ;
	private static Object previousLevel;

	/** The depth of the world before the hand's clear, without a shader pack; null where it was not taken this frame. */
	static VulkanImage worldDepth;
	private static MainDepthSnapshot snapshot;
	private static boolean snapshotWarned;

	private DlssFrame() {
	}

	/** {@code GameRenderer.renderLevel} HEAD. */
	public static void beginWorld() {
		try {
			planWorld();
		} catch (Throwable e) {
			failed("planning a frame", e);
		}
	}

	private static void planWorld() {
		// A frame planned for DLSS that never reached the resolve would otherwise leave the jitter on with nothing to
		// undo it.
		if (planned) DlssResolve.unresolved();
		DlssResolve.collect();
		planned = false;
		captured = false;
		worldDepth = null;
		SrvmrConfig config = SrvmrConfig.get();
		if (!config.enabled || Ngx.failed()) return;
		Minecraft minecraft = Minecraft.getInstance();
		// A panorama screenshot turns the camera every frame: nothing of it is for DLSS.
		if (minecraft.screen != null || minecraft.level == null || minecraft.gameRenderer.isPanoramicMode()) return;
		Renderer renderer = Renderer.getInstance();
		SwapChain swapChain = Vulkan.getSwapChain();
		if (renderer == null || swapChain == null) return;
		MainPass pass = renderer.getMainPass();
		VulkanImage color = pass != null ? pass.getColorAttachment() : null;
		if (color == null) return;
		int width = swapChain.getWidth();
		int height = swapChain.getHeight();
		if (width <= 0 || height <= 0 || color.width >= width && color.height >= height) return;
		if (!DlssResolve.wanted(color.width, color.height, width, height)) return;
		renderWidth = color.width;
		renderHeight = color.height;
		outputWidth = width;
		outputHeight = height;
		planned = true;
		if (config.jitter) {
			double scale = (double) width / renderWidth * height / renderHeight;
			long phases = Math.max(8L, (long) Math.ceil(8.0 * scale));
			long index = sequence++ % phases + 1;
			jitterX = halton(index, 2) - 0.5F;
			jitterY = halton(index, 3) - 0.5F;
		} else {
			jitterX = 0.0F;
			jitterY = 0.0F;
		}
	}

	/** {@code GameRenderer.getProjectionMatrix} RETURN: the projection moved by this frame's jitter. */
	public static Matrix4f jitter(Matrix4f projection) {
		if (!planned || jitterX == 0.0F && jitterY == 0.0F) return projection;
		try {
			return projection.mulLocal(new Matrix4f().translation(jitterNdcX(), jitterNdcY(), 0.0F));
		} catch (Throwable e) {
			failed("jittering the projection", e);
			return projection;
		}
	}

	/** {@code LevelRenderer.renderLevel} HEAD: the first drawing of the world in the frame is the one shown. */
	public static void capture(Matrix4f modelView, Matrix4f projection, Camera camera, DeltaTracker tracker) {
		if (!planned || captured) return;
		try {
			captureWorld(modelView, projection, camera, tracker);
		} catch (Throwable e) {
			failed("taking the camera and entities", e);
		}
	}

	private static void captureWorld(Matrix4f modelView, Matrix4f projection, Camera camera, DeltaTracker tracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return;
		Vec3 position = camera.getPosition();
		MODEL_VIEW.set(modelView);
		PROJECTION.set(projection);
		cameraX = position.x;
		cameraY = position.y;
		cameraZ = position.z;
		level = minecraft.level;
		// Entities of another world have ids of their own: nothing of the last frame's carries over. The partial tick is
		// the one the game draws entities with while the game runs (LevelRenderer passes true for entities not frozen).
		if (!hasPrevious || previousLevel != level) EntityMotion.forget();
		EntityMotion.capture(minecraft.level, camera.getEntity(), !camera.isDetached(),
				tracker.getGameTimeDeltaPartialTick(true), cameraX, cameraY, cameraZ, modelView, projection);
		captured = true;
	}

	/**
	 * Just before the game clears the depth for the hand: the world's depth, copied, where no shader pack draws (a pack
	 * keeps its own depth). VulkanMod Reforged's snapshot suspends its render pass, copies and resumes it.
	 */
	public static void beforeHandClear() {
		if (!planned || !captured || OpalDepth.current() != null) return;
		try {
			MainPass pass = Renderer.getInstance().getMainPass();
			VulkanImage depth = pass != null ? pass.getDepthAttachment() : null;
			if (depth == null) return;
			if (snapshot == null) snapshot = new MainDepthSnapshot();
			VulkanImage copy = snapshot.resolveForSampling(depth);
			worldDepth = copy != depth ? copy : null;
		} catch (Throwable e) {
			worldDepth = null;
			if (!snapshotWarned) {
				snapshotWarned = true;
				Srvmr.LOGGER.warn("[dlss] cannot copy the world's depth before the hand is drawn; the hand's depth is used", e);
			}
		}
	}

	/** Something failed in a hook into the game's rendering: DLSS goes off for the session and the frame is the game's. */
	private static void failed(String what, Throwable e) {
		Srvmr.LOGGER.error("[dlss] DLSS failed {} and is off for this session", what, e);
		Ngx.giveUp();
		planned = false;
		captured = false;
		worldDepth = null;
	}

	static float jitterNdcX() {
		return 2.0F * jitterX / renderWidth;
	}

	/** The picture lies top row first, and up in clip space is towards the top row. */
	static float jitterNdcY() {
		return -2.0F * jitterY / renderHeight;
	}

	/** Whether DLSS has to forget what it has accumulated: the first frame, another world, a camera that jumped. */
	static boolean reset() {
		if (!hasPrevious || previousLevel != level) return true;
		double dx = cameraX - previousX;
		double dy = cameraY - previousY;
		double dz = cameraZ - previousZ;
		return dx * dx + dy * dy + dz * dz > JUMP * JUMP;
	}

	/** From this frame's clip space, jittered, to positions relative to this frame's camera. */
	static Matrix4f inverseCurrent(Matrix4f dest) {
		return dest.set(PROJECTION).mul(MODEL_VIEW).invert();
	}

	/**
	 * From positions relative to this frame's camera to the last upscaled frame's clip space, without jitter: the camera
	 * moved between the two, so the move is in it. With no frame before, or after a reset, this frame's own clip space
	 * without jitter, so that nothing moves.
	 */
	static Matrix4f previous(Matrix4f dest) {
		if (reset()) return dest.set(unjittered(new Matrix4f(PROJECTION))).mul(MODEL_VIEW);
		return dest.set(PREVIOUS_PROJECTION).mul(PREVIOUS_MODEL_VIEW)
				.translate((float) (cameraX - previousX), (float) (cameraY - previousY), (float) (cameraZ - previousZ));
	}

	/** How many entity boxes to use: none on a frame DLSS starts afresh. */
	static int entityCount() {
		return reset() ? 0 : EntityMotion.count;
	}

	private static Matrix4f unjittered(Matrix4f projection) {
		return projection.mulLocal(new Matrix4f().translation(-jitterNdcX(), -jitterNdcY(), 0.0F));
	}

	/** After the frame was resolved, by DLSS or not. */
	static void finish(boolean upscaled) {
		if (upscaled) {
			PREVIOUS_PROJECTION.set(unjittered(new Matrix4f(PROJECTION)));
			PREVIOUS_MODEL_VIEW.set(MODEL_VIEW);
			previousX = cameraX;
			previousY = cameraY;
			previousZ = cameraZ;
			previousLevel = level;
			hasPrevious = true;
			EntityMotion.commit();
		} else {
			hasPrevious = false;
			EntityMotion.forget();
		}
		planned = false;
		worldDepth = null;
	}

	private static float halton(long index, int base) {
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
