package dev.opalcompat.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The look the pack screens share: flat tiles on the game's blurred background, a name on the left and its value on the
 * right, one accent colour. Drawn with fills and the game's font alone, so it needs no texture of its own.
 */
final class Flat {

	static final int PAD = 6;
	static final int TILE = 0x70101216;
	static final int TILE_HOT = 0x902A303C;
	static final int TILE_OFF = 0x40101216;
	static final int LINE = 0x30FFFFFF;
	static final int TEXT = 0xFFE6E8EC;
	static final int TEXT_DIM = 0xFF8B919C;
	static final int TEXT_OFF = 0xFF6B7078;
	static final int ACCENT = 0xFF8AB4F8;
	static final int CHANGED = 0xFFFFD166;
	static final int ON = 0xFF4ADE80;
	static final int OFF = 0xFF596070;
	static final int FILL = 0x554F8EF7;
	static final int PRIMARY = 0xD02563EB;
	static final int PRIMARY_HOT = 0xFF3B82F6;

	private Flat() {
	}

	/** A tile's ground: dimmer when it cannot be used, lighter under the mouse or the keyboard's focus. */
	static void tile(GuiGraphics graphics, AbstractWidget widget) {
		int x = widget.getX();
		int y = widget.getY();
		boolean hot = widget.active && widget.isHoveredOrFocused();
		graphics.fill(x, y, x + widget.getWidth(), y + widget.getHeight(), !widget.active ? TILE_OFF : hot ? TILE_HOT : TILE);
		if (widget.isFocused()) graphics.renderOutline(x, y, widget.getWidth(), widget.getHeight(), 0x80FFFFFF);
	}

	/**
	 * A name on the left of a tile and, where there is room after it, a dimmer note. A name too long for its room scrolls
	 * in it, as the game's own buttons do.
	 *
	 * @param left  where the name starts
	 * @param right where its room ends
	 */
	static void name(GuiGraphics graphics, Font font, AbstractWidget widget, Component name, String note, int left, int right) {
		int y = widget.getY();
		int textY = y + (widget.getHeight() - 8) / 2;
		int colour = widget.active ? TEXT : TEXT_OFF;
		int width = font.width(name);
		if (width > right - left) {
			AbstractWidget.renderScrollingString(graphics, font, name, left, y, right, y + widget.getHeight(), colour);
			return;
		}
		graphics.drawString(font, name, left, textY, colour);
		if (note == null || note.isEmpty()) return;
		int room = right - left - width - 8;
		if (room > 24) graphics.drawString(font, font.plainSubstrByWidth(note, room), left + width + 8, textY, TEXT_DIM);
	}

	/**
	 * A value at the right edge of a tile, in at most half of it.
	 *
	 * @return where the room left of it ends
	 */
	static int value(GuiGraphics graphics, Font font, AbstractWidget widget, Component value, int colour) {
		int y = widget.getY();
		int right = widget.getX() + widget.getWidth() - PAD;
		int room = Math.max(30, (widget.getWidth() - 3 * PAD) / 2);
		int width = font.width(value);
		if (width <= room) {
			graphics.drawString(font, value, right - width, y + (widget.getHeight() - 8) / 2, colour);
			return right - width - PAD;
		}
		AbstractWidget.renderScrollingString(graphics, font, value, right - room, y, right, y + widget.getHeight(), colour);
		return right - room - PAD;
	}

	/**
	 * A switch at the right edge of a tile.
	 *
	 * @return where the room left of it ends
	 */
	static int toggle(GuiGraphics graphics, AbstractWidget widget, boolean on) {
		int trackX = widget.getX() + widget.getWidth() - PAD - 18;
		int trackY = widget.getY() + (widget.getHeight() - 10) / 2;
		graphics.fill(trackX, trackY, trackX + 18, trackY + 10, !widget.active ? 0xFF3A3F4A : on ? ON : OFF);
		int knobX = on ? trackX + 9 : trackX + 1;
		graphics.fill(knobX, trackY + 1, knobX + 8, trackY + 9, widget.active ? 0xFFFFFFFF : 0xFFA0A4AC);
		return trackX - PAD;
	}

	/** The search field: no border of its own, on a tile the screen draws behind it. */
	static EditBox search(Font font, int x, int y, int width, Component narration) {
		EditBox box = new EditBox(font, x, y, width, 12, narration);
		box.setBordered(false);
		box.setMaxLength(64);
		box.setTextColor(TEXT);
		return box;
	}

	/** The tile behind {@link #search}, and what it asks for while it is empty. */
	static void searchGround(GuiGraphics graphics, EditBox box) {
		graphics.fill(box.getX() - PAD, box.getY() - 5, box.getX() + box.getWidth() + PAD, box.getY() + 13,
				box.isFocused() ? TILE_HOT : TILE);
		graphics.fill(box.getX() - PAD, box.getY() + 13, box.getX() + box.getWidth() + PAD, box.getY() + 14,
				box.isFocused() ? ACCENT : LINE);
	}

	static void searchHint(GuiGraphics graphics, Font font, EditBox box, Component hint) {
		if (box.getValue().isEmpty()) graphics.drawString(font, hint, box.getX() + 4, box.getY(), TEXT_OFF, false);
	}

	/** A button of the footer. The one that finishes the screen is filled with the accent. */
	static final class Action extends Button {
		private final boolean primary;

		Action(int x, int y, int width, Component label, boolean primary, OnPress press) {
			super(x, y, width, 20, label, press, DEFAULT_NARRATION);
			this.primary = primary;
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			boolean hot = this.active && isHoveredOrFocused();
			int ground = !this.active ? TILE_OFF : this.primary ? (hot ? PRIMARY_HOT : PRIMARY) : hot ? TILE_HOT : TILE;
			graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), ground);
			if (isFocused()) graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), 0x80FFFFFF);
			Font font = Minecraft.getInstance().font;
			int colour = this.active ? 0xFFFFFFFF : TEXT_OFF;
			if (font.width(getMessage()) > getWidth() - 8) {
				AbstractWidget.renderScrollingString(graphics, font, getMessage(), getX() + 4, getY(), getX() + getWidth() - 4,
						getY() + getHeight(), colour);
			} else {
				graphics.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, colour);
			}
		}
	}
}
