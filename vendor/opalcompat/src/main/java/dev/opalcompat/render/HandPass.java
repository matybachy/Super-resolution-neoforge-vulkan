package dev.opalcompat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vitrail.render.HandDraw;
import dev.opalcompat.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Two hand submissions around the deferred stage. Follows Iris 1.21.1 ({@code pipeline/HandRenderer}):
 * when it runs, what it draws in which of the two, how the pose is built. LGPL-3.0, see NOTICE.
 */
public final class HandPass {
	private static boolean active;
	private static boolean solid;

	private HandPass() {
	}

	public static boolean active() {
		return active;
	}

	public static boolean opaque() {
		return solid;
	}

	public static boolean skip(ItemStack stack) {
		boolean translucent = stack.getItem() instanceof BlockItem block
				&& ItemBlockRenderTypes.getChunkRenderType(block.getBlock().defaultBlockState()) == RenderType.translucent();
		return active && solid == translucent;
	}

	/**
	 * The projection the hand is drawn under. Iris scales clip z by 0.125 in GL's -1..1 range, the range the
	 * game's matrices are in here, which puts the hand's window depth into 0.4375..0.5625, and packs test for
	 * that range ({@code depth < 0.56}).
	 */
	private static Matrix4f handProjection(GameRenderer game, GameRendererAccessor access, Camera camera, float tick) {
		Matrix4f m = new Matrix4f(game.getProjectionMatrix(access.opalcompat$getFov(camera, tick, false)));
		m.m02(0.125F * m.m02());
		m.m12(0.125F * m.m12());
		m.m22(0.125F * m.m22());
		m.m32(0.125F * m.m32());
		return m;
	}

	public static void render(Camera camera, GameRenderer game, float tick, boolean opaque) {
		Minecraft mc = Minecraft.getInstance();
		GameRendererAccessor access = (GameRendererAccessor) game;
		if (!access.opalcompat$renderHand() || game.isPanoramicMode() || camera.isDetached()
				|| !(camera.getEntity() instanceof Player) || mc.player == null || mc.gameMode == null
				|| mc.options.hideGui || ((LivingEntity) camera.getEntity()).isSleeping()
				|| mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return;
		Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
		Matrix4f handProjection = handProjection(game, access, camera, tick);
		PoseStack poses = new PoseStack();
		access.opalcompat$bobHurt(poses, tick);
		if (mc.options.bobView().get()) access.opalcompat$bobView(poses, tick);
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		active = true;
		solid = opaque;
		HandDraw.drawing(true);
		PackRenderer.pushPhase(Families.Phase.HAND);
		modelView.pushMatrix();
		try {
			game.resetProjectionMatrix(handProjection);
			modelView.set(poses.last().pose());
			RenderSystem.applyModelViewMatrix();
			game.lightTexture().turnOnLightLayer();
			MultiBufferSource.BufferSource buffers = PassBuffers.source();
			game.itemInHandRenderer.renderHandsWithItems(tick, new PoseStack(), buffers, mc.player,
					mc.getEntityRenderDispatcher().getPackedLightCoords(camera.getEntity(), tick));
		} finally {
			game.lightTexture().turnOffLightLayer();
			modelView.popMatrix();
			RenderSystem.applyModelViewMatrix();
			game.resetProjectionMatrix(projection);
			PackRenderer.popPhase();
			HandDraw.drawing(false);
			active = false;
		}
	}
}
