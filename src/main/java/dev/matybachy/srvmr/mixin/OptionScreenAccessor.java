package dev.matybachy.srvmr.mixin;

import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The mods and pages column of VulkanMod Reforged's video settings, for {@code SidebarScroll}. Read only. */
@Mixin(value = VOptionScreen.class, remap = false)
public interface OptionScreenAccessor {

	@Accessor("iconWidgets")
	List<VAbstractWidget> srvmr$iconWidgets();

	@Accessor("pageButtons")
	List<VButtonWidget> srvmr$pageButtons();
}
