package dev.matybachy.srvmr.gui;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.mixin.OptionScreenAccessor;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;

import java.util.List;

/**
 * VulkanMod Reforged's video settings put the mods and their pages in a column on the left, one under another from the
 * top, and the column never scrolls ({@code VOptionScreen.buildPage}). With VulkanMod Reforged's own four pages, Opal
 * Compat's, Vectors' and this mod's, the column is taller than the screen at a large GUI scale (at 1440p the automatic
 * scale leaves 240 points), and the pages at the bottom cannot be reached. Here the mouse wheel over the column scrolls
 * it, and a thin bar beside it shows where it is.
 * <p>
 * Done through NeoForge's screen events and a read-only accessor, so nothing is added to VulkanMod Reforged's screen
 * class: the widgets of the column are moved, and moved again whenever the screen builds them anew.
 */
public final class SidebarScroll {

	private static final int TOP = 4;
	private static final int STEP = 20;

	private static Screen screen;
	/** The column's first button: a new one means VulkanMod Reforged built the column again, at its own positions. */
	private static Object built;
	private static int scroll;
	private static int applied;

	private SidebarScroll() {
	}

	public static void register() {
		try {
			NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseScrolled.Pre.class, SidebarScroll::scrolled);
			NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Pre.class, SidebarScroll::beforeRender);
			NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, SidebarScroll::afterRender);
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot make VulkanMod Reforged's settings column scroll", e);
		}
	}

	private static void scrolled(ScreenEvent.MouseScrolled.Pre event) {
		try {
			if (!(event.getScreen() instanceof VOptionScreen)) return;
			if (event.getMouseX() >= VOptionScreen.MARGIN + VGuiConstants.PAGE_BUTTON_WIDTH + 4) return;
			sync(event.getScreen());
			if (overflow(event.getScreen()) <= 0) return;
			scrollTo(event.getScreen(), scroll - (int) Math.round(event.getScrollDeltaY() * STEP));
			event.setCanceled(true);
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] the settings column did not scroll", e);
		}
	}

	private static void beforeRender(ScreenEvent.Render.Pre event) {
		try {
			if (event.getScreen() instanceof VOptionScreen) sync(event.getScreen());
		} catch (Throwable ignored) {
			// Nothing of the screen's own drawing depends on this.
		}
	}

	private static void afterRender(ScreenEvent.Render.Post event) {
		try {
			Screen current = event.getScreen();
			if (!(current instanceof VOptionScreen)) return;
			int overflow = overflow(current);
			if (overflow <= 0) return;
			int visible = current.height - 2 * TOP;
			int bar = Math.max(16, visible * visible / (visible + overflow));
			int top = TOP + (visible - bar) * scroll / overflow;
			int x = VOptionScreen.MARGIN + VGuiConstants.PAGE_BUTTON_WIDTH + 1;
			event.getGuiGraphics().fill(x, TOP, x + 2, TOP + visible, 0x33FFFFFF);
			event.getGuiGraphics().fill(x, top, x + 2, top + bar, 0xAAFFFFFF);
		} catch (Throwable ignored) {
			// The bar is a hint only.
		}
	}

	/** A new screen, or the column built anew: its widgets are at their own positions, and get the scroll again. */
	private static void sync(Screen current) {
		List<VButtonWidget> buttons = ((OptionScreenAccessor) current).srvmr$pageButtons();
		Object first = buttons.isEmpty() ? null : buttons.get(0);
		if (current != screen) {
			screen = current;
			scroll = 0;
			applied = 0;
			built = first;
			return;
		}
		if (first != built) {
			built = first;
			applied = 0;
			scrollTo(current, scroll);
		}
	}

	/** How much of the column, at its own positions, is below the screen. */
	private static int overflow(Screen current) {
		OptionScreenAccessor access = (OptionScreenAccessor) current;
		int bottom = 0;
		for (VAbstractWidget widget : access.srvmr$iconWidgets()) bottom = Math.max(bottom, widget.y + widget.getHeight());
		for (VButtonWidget widget : access.srvmr$pageButtons()) bottom = Math.max(bottom, widget.y + widget.getHeight());
		return Math.max(0, bottom + applied - (current.height - TOP));
	}

	private static void scrollTo(Screen current, int wanted) {
		OptionScreenAccessor access = (OptionScreenAccessor) current;
		int target = Math.max(0, Math.min(wanted, overflow(current)));
		int delta = applied - target;
		if (delta != 0) {
			for (VAbstractWidget widget : access.srvmr$iconWidgets()) widget.y += delta;
			for (VButtonWidget widget : access.srvmr$pageButtons()) widget.y += delta;
		}
		applied = target;
		scroll = target;
	}
}
