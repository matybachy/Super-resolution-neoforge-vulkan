package dev.matybachy.srvmr.render;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.SrvmrConfig;
import dev.matybachy.srvmr.ngx.Ngx;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.RenderScale;
import net.vulkanmod.vulkan.Vulkan;

import java.util.Locale;

import static org.lwjgl.vulkan.VK10.vkDestroySampler;

/**
 * The mip level of a texture follows how many texels fall on a pixel. Drawn at a Render Scale of s, a block covers s times
 * as many pixels across as on the screen, so the GPU takes a mip level log2(1 / s) coarser than the screen would need:
 * at 33 % that is 1.6 levels, textures a third as sharp, and DLSS cannot bring back detail the texture never had. While
 * DLSS runs, every texture sampler VulkanMod Reforged makes ({@code SamplerManager.createTextureSampler}, whose own bias
 * is -0.5) gets log2(s) more, which is the level the screen itself would take.
 * <p>
 * VulkanMod Reforged keeps its samplers in a cache and every texture asks it for one again at each {@code setFilter},
 * which the game calls each time a render type binds its texture. When the bias changes the cache is emptied, so the
 * textures take samplers with the new bias in the next frame; the old samplers may still be bound for that frame and are
 * destroyed with the device.
 */
public final class TextureBias {

	private static float applied;
	private static int seenScale = -1;
	private static boolean seenOn;
	private static float wanted;
	private static final LongArrayList RETIRED = new LongArrayList();

	private TextureBias() {
	}

	/** What is added to VulkanMod Reforged's own bias in a sampler made now. */
	public static float current() {
		return applied;
	}

	/** Before VulkanMod Reforged looks a sampler up: if the bias DLSS wants has changed, its cache is emptied. */
	public static void refresh(Short2LongMap samplers) {
		try {
			float bias = wanted();
			if (bias == applied) return;
			applied = bias;
			RETIRED.addAll(samplers.values());
			samplers.clear();
			Srvmr.LOGGER.info("[dlss] texture mip bias {} for the Render Scale", String.format(Locale.ROOT, "%+.2f", bias));
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot set the texture mip bias", e);
		}
	}

	private static float wanted() {
		boolean on = SrvmrConfig.get().enabled && !Ngx.failed();
		int scale = Math.max(1, Math.min(RenderScale.MAX, Initializer.CONFIG.renderScale));
		if (on != seenOn || scale != seenScale) {
			seenOn = on;
			seenScale = scale;
			wanted = on && scale < RenderScale.MAX ? (float) (Math.log(RenderScale.clamp(scale) / 100.0) / Math.log(2.0)) : 0.0F;
		}
		return wanted;
	}

	/** With the device still there: the samplers the cache let go of. */
	public static void shutdown() {
		try {
			for (int i = 0; i < RETIRED.size(); i++) vkDestroySampler(Vulkan.getVkDevice(), RETIRED.getLong(i), null);
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot destroy the retired texture samplers", e);
		}
		RETIRED.clear();
	}
}
