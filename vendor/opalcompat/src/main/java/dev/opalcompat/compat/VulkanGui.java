package dev.opalcompat.compat;

import dev.vector.fabric.gui.VectorPage;
import dev.vector.fabric.gui.VectorUiRegistry;
import dev.opalcompat.compat.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.VOptionScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * One way to every setting: VulkanMod Reforged's video settings, the screen Options leads to anyway, take the mods' pages in
 * their own registry. {@code ShaderPages} adds the pack page there; Vectors goes here. The buttons Opal and Vectors put on top
 * of the Options screen have nothing left to add. Vectors' own screen, still on its key, gets the way back.
 */
public final class VulkanGui {

	private VulkanGui() {
	}

	public static void register() {
		if (Mods.VECTORS) {
			ModSettingsRegistry.INSTANCE.addModEntry(VectorsPages.entry());
			VectorUiRegistry.registerPage(new VectorPage("opalcompat:vulkanmod", Component.translatable("opalcompat.compat.page.vulkan"), "▣", 40,
					page -> page.addAction(Component.translatable("opalcompat.compat.vulkan.title").getString(),
							Component.translatable("opalcompat.compat.vulkan.hint").getString(),
							Component.translatable("opalcompat.compat.vulkan.open").getString(), true, VulkanGui::openSettings)));
		}
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ScreenEvent.Init.Post.class, VulkanGui::tidyOptions);
	}

	private static void openSettings() {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new VOptionScreen(Component.literal("Video Setting"), mc.screen));
	}

	private static void tidyOptions(ScreenEvent.Init.Post event) {
		if (!(event.getScreen() instanceof OptionsScreen)) return;
		String opal = Component.translatable("opal.screen.title").getString();
		List<GuiEventListener> extra = new ArrayList<>();
		for (GuiEventListener listener : event.getListenersList()) {
			if (listener instanceof Button button && button.getY() == 4) {
				String text = button.getMessage().getString();
				if (text.equals("Vectors") || text.equals(opal)) extra.add(button);
			}
		}
		extra.forEach(event::removeListener);
	}
}
