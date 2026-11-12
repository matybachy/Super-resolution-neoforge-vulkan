package dev.opalcompat.screen;

import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.settings.PackSession;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.pack.ShaderPacks;
import dev.opalcompat.render.PackRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which pack is drawn. Off is a row like any other, and what it leaves is VulkanMod Reforged alone. What went
 * wrong with the last pack is written here in full, since a pack that failed draws nothing to look at. The field
 * at the top narrows the list by name.
 */
public final class ShaderPackScreen extends Screen {

	private static final int ROW = 24;
	private static final int TOP = 56;
	private static final int FOOTER = 40;
	private static final int STATUS_LINES = 5;

	private final Screen parent;
	private final List<String> packs = new ArrayList<>();
	private PackList list;
	private EditBox search;
	private Button settings;
	private Button apply;
	private Button copy;
	private String chosen = ShaderPacks.selectedName();
	private String filter = "";
	private String problem;
	private MultiLineLabel statusLabel = MultiLineLabel.EMPTY;
	private String shown;

	public ShaderPackScreen(Screen parent) {
		super(Component.translatable("opalcompat.screen.packs"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.packs.clear();
		try {
			for (Path path : PackLoader.candidates(this.minecraft.gameDirectory.toPath())) this.packs.add(path.getFileName().toString());
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Cannot list the shader packs", e);
		}

		int fieldWidth = Math.min(260, this.width - 60);
		this.search = Flat.search(this.font, (this.width - fieldWidth) / 2, 32, fieldWidth, Component.translatable("opalcompat.screen.search_packs"));
		this.search.setValue(this.filter);
		this.search.setResponder(text -> {
			if (text.equals(this.filter)) return;
			this.filter = text;
			this.list.fill();
		});
		addRenderableWidget(this.search);

		int statusTop = this.height - FOOTER - STATUS_LINES * 10 - 4;
		this.list = new PackList(this.minecraft, this.width, statusTop - TOP - 4, TOP);
		this.list.fill();
		addRenderableWidget(this.list);

		int gap = 4;
		int count = 5;
		int width = Math.min(120, (this.width - 20 - (count - 1) * gap) / count);
		int x = (this.width - count * width - (count - 1) * gap) / 2;
		int y = this.height - FOOTER + 10;
		this.settings = addRenderableWidget(new Flat.Action(x, y, width, Component.translatable("opalcompat.screen.settings"), false, b -> openSettings()));
		x += width + gap;
		addRenderableWidget(new Flat.Action(x, y, width, Component.translatable("opalcompat.screen.folder"), false, b -> openFolder()));
		x += width + gap;
		this.copy = addRenderableWidget(new Flat.Action(x, y, width, Component.translatable("opalcompat.screen.copy"), false, b -> copyError()));
		x += width + gap;
		this.apply = addRenderableWidget(new Flat.Action(x, y, width, Component.translatable("opalcompat.screen.apply"), false, b -> applyChoice()));
		x += width + gap;
		addRenderableWidget(new Flat.Action(x, y, width, CommonComponents.GUI_DONE, true, b -> {
			applyChoice();
			onClose();
		}));
		refresh();
	}

	/** The state of the buttons, and the words under the list, for what is chosen and what is running. */
	private void refresh() {
		this.settings.active = !this.chosen.isEmpty();
		this.apply.active = !this.chosen.equals(ShaderPacks.selectedName());
		String error = this.problem != null ? this.problem : PackRenderer.lastError().orElse(null);
		this.copy.active = error != null;
		Component text;
		if (error != null) {
			text = Component.literal(error).withStyle(ChatFormatting.RED);
		} else if (PackRenderer.needsRestart(this.chosen)) {
			text = Component.translatable("opalcompat.screen.restart").withStyle(ChatFormatting.YELLOW);
		} else if (ShaderPacks.selectedName().isEmpty()) {
			text = Component.translatable("opalcompat.screen.off");
		} else if (PackRenderer.runningPack().isPresent()) {
			text = Component.translatable("opalcompat.screen.running", PackRenderer.runningPack().get());
		} else {
			text = Component.translatable("opalcompat.screen.pending", ShaderPacks.selectedName());
		}
		String key = text.getString();
		if (!key.equals(this.shown)) {
			this.shown = key;
			this.statusLabel = MultiLineLabel.create(this.font, this.width - 40, STATUS_LINES, text);
		}
	}

	private void applyChoice() {
		if (this.chosen.equals(ShaderPacks.selectedName())) return;
		try {
			ShaderPacks.select(this.chosen);
			this.problem = null;
			PackRenderer.reload();
		} catch (IOException e) {
			OpalCompat.LOGGER.error("Cannot save the chosen shader pack", e);
			this.problem = e.toString();
		}
		refresh();
	}

	private void openSettings() {
		openSettings(this.minecraft, this.chosen, this);
	}

	/** The settings of a pack, coming back to the screen that is showing now. */
	void openSettings(Minecraft minecraft, String pack) {
		openSettings(minecraft, pack, this.parent);
	}

	private void openSettings(Minecraft minecraft, String pack, Screen back) {
		Path path = ShaderPacks.directory().resolve(pack);
		try {
			String language = minecraft.getLanguageManager().getSelected();
			PackSession session = PackSession.read(minecraft.gameDirectory.toPath(), path, language);
			minecraft.setScreen(new PackSettingsScreen(back, session));
		} catch (IOException | RuntimeException e) {
			OpalCompat.LOGGER.error("Cannot read the settings of {}", pack, e);
			this.problem = pack + ": " + e;
			if (back == this) {
				refresh();
			} else {
				minecraft.setScreen(this);
			}
		}
	}

	private void openFolder() {
		Path directory = ShaderPacks.directory();
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Cannot create {}", directory, e);
		}
		Util.getPlatform().openFile(directory.toFile());
	}

	private void copyError() {
		String error = this.problem != null ? this.problem : PackRenderer.lastError().orElse(null);
		if (error != null) this.minecraft.keyboardHandler.setClipboard(error);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		Flat.searchGround(graphics, this.search);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// A pack that fails does so a frame or more after Apply, while this is on screen.
		refresh();
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		Flat.searchHint(graphics, this.font, this.search, Component.translatable("opalcompat.screen.search_packs"));
		this.statusLabel.renderCentered(graphics, this.width / 2, this.height - FOOTER - STATUS_LINES * 10);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	private final class PackList extends ObjectSelectionList<PackList.Row> {

		PackList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, ROW);
		}

		/** Off first, then the packs whose names hold what the field says. */
		void fill() {
			clearEntries();
			setScrollAmount(0.0);
			String wanted = ShaderPackScreen.this.filter.trim().toLowerCase(Locale.ROOT);
			Row off = new Row("", Component.translatable("opalcompat.screen.none"));
			addEntry(off);
			if (ShaderPackScreen.this.chosen.isEmpty()) setSelected(off);
			for (String name : ShaderPackScreen.this.packs) {
				if (!wanted.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(wanted)) continue;
				Row row = new Row(name, Component.literal(name));
				addEntry(row);
				if (name.equals(ShaderPackScreen.this.chosen)) setSelected(row);
			}
			if (getSelected() != null) centerScrollOn(getSelected());
		}

		@Override
		public int getRowWidth() {
			return Math.min(420, this.width - 40);
		}

		@Override
		protected int getScrollbarPosition() {
			return (this.width + getRowWidth()) / 2 + 6;
		}

		/** The rows are tiles on the screen's own background, and say themselves which is chosen. */
		@Override
		protected void renderListBackground(GuiGraphics graphics) {
		}

		@Override
		protected void renderListSeparators(GuiGraphics graphics) {
			graphics.fill(getX(), getY() - 1, getRight(), getY(), Flat.LINE);
			graphics.fill(getX(), getBottom(), getRight(), getBottom() + 1, Flat.LINE);
		}

		@Override
		protected void renderSelection(GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
		}

		final class Row extends ObjectSelectionList.Entry<Row> {
			private final String pack;
			private final Component label;

			Row(String pack, Component label) {
				this.pack = pack;
				this.label = label;
			}

			@Override
			public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
					boolean hovering, float partialTick) {
				var screen = ShaderPackScreen.this;
				boolean picked = PackList.this.getSelected() == this;
				boolean live = this.pack.equals(ShaderPacks.selectedName());
				graphics.fill(left, top, left + width, top + height, picked || hovering ? Flat.TILE_HOT : Flat.TILE);
				if (picked) graphics.fill(left, top, left + 2, top + height, Flat.ACCENT);
				int textY = top + (height - 8) / 2;
				int right = left + width - Flat.PAD;
				if (live) {
					Component active = Component.translatable("opalcompat.screen.active");
					right -= screen.font.width(active);
					graphics.drawString(screen.font, active, right, textY, Flat.ON);
					right -= Flat.PAD;
				}
				int nameLeft = left + Flat.PAD + 2;
				int colour = this.pack.isEmpty() ? Flat.TEXT_DIM : Flat.TEXT;
				if (screen.font.width(this.label) > right - nameLeft) {
					net.minecraft.client.gui.components.AbstractWidget.renderScrollingString(graphics, screen.font, this.label, nameLeft, top,
							right, top + height, colour);
				} else {
					graphics.drawString(screen.font, this.label, nameLeft, textY, colour);
				}
			}

			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				PackList.this.setSelected(this);
				ShaderPackScreen.this.chosen = this.pack;
				ShaderPackScreen.this.problem = null;
				ShaderPackScreen.this.refresh();
				return true;
			}

			@Override
			public Component getNarration() {
				return this.label;
			}
		}
	}
}
