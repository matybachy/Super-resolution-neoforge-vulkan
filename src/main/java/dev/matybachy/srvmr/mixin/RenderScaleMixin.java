package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.matybachy.srvmr.SrvmrConfig;
import dev.matybachy.srvmr.ngx.Ngx;
import net.vulkanmod.config.RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * VulkanMod Reforged keeps its Render Scale between 50 and 100 % ({@code RenderScale.clamp}), and DLSS Ultra Performance
 * draws at a third of the screen. While DLSS is on and has not failed, the scale may go down to {@link #LOWEST} %; with
 * DLSS off, or on a GPU it cannot run on, VulkanMod Reforged's own floor of 50 % holds again at the next frame, since every
 * use of the scale goes through this method.
 */
@Mixin(value = RenderScale.class, remap = false)
public abstract class RenderScaleMixin {

	/** DLSS Ultra Performance: a third of the screen. */
	@Unique
	private static final int LOWEST = 33;

	@WrapMethod(method = "clamp")
	private static int srvmr$ultraPerformance(int scale, Operation<Integer> original) {
		if (scale < RenderScale.MIN && SrvmrConfig.get().enabled && !Ngx.failed()) {
			return Math.max(LOWEST, scale);
		}
		return original.call(scale);
	}

	/**
	 * VulkanMod Reforged sizes the picture as {@code round(size * scale / 100)}, which at 33 % of 2560x1440 is 845x475:
	 * less than the third DLSS Ultra Performance is made for, and outside the range NGX lists for it ("845x475 for
	 * 2560x1440 is outside the ranges NGX lists" in the log). At the lowest step the size is a third exactly, 853x480. The
	 * scale is below 50 % only while DLSS is on ({@link #srvmr$ultraPerformance}), so nothing else changes.
	 */
	@WrapMethod(method = "scaleDimension")
	private static int srvmr$exactThird(int size, int scale, Operation<Integer> original) {
		if (RenderScale.clamp(scale) == LOWEST) return Math.max(1, Math.round(size / 3.0F));
		return original.call(size, scale);
	}
}
