package dev.matybachy.srvmr.mixin;

import dev.matybachy.srvmr.SrvmrConfig;
import dev.matybachy.srvmr.ngx.Ngx;
import net.vulkanmod.config.option.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * VulkanMod Reforged's own Render Scale slider ({@code Options.getOtherOpts}) runs from 50 %. Its widget, when made,
 * clamps the value it shows into that range and takes the clamped value as a change ({@code RangeOptionWidget.setValue}
 * calls {@code applyNewValue} when they differ): with DLSS's 33 % it held 50 % as a pending change, kept Apply lit, and
 * any Apply wrote 50 % back, so Ultra Performance gave way to Performance. While DLSS is on the slider starts at 30 %, as
 * on the DLSS page; its getter and setter go through {@code RenderScale.clamp}, which then holds 33 % for the lowest step.
 * Not required: if VulkanMod Reforged's code differs, its slider stays as it is.
 */
@Mixin(value = Options.class, remap = false)
public abstract class OptionsRenderScaleMixin {

	@ModifyConstant(method = "getOtherOpts", constant = @Constant(intValue = 50), require = 0)
	private static int srvmr$lowestRenderScale(int lowest) {
		return SrvmrConfig.get().enabled && !Ngx.failed() ? 30 : lowest;
	}
}
