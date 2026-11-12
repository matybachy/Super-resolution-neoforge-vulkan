package dev.opalcompat.screen;

import com.google.common.collect.ImmutableList;
import dev.vitrail.pack.menu.MenuOption;
import dev.vitrail.pack.menu.MenuPage;
import dev.vitrail.pack.menu.MenuSlot;
import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.pack.menu.PackMenu;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.pack.ShaderPacks;
import dev.opalcompat.render.PackRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * A pack's own pages, {@code screen.*}, {@code sliders} and {@code profile.*} as {@link PackMenu} lays them out,
 * written to the same {@code shaderpacks/<pack>.txt} Iris reads. Cancel leaves, Apply writes and reloads, Done
 * does both. Left click steps forward, right click back, shift and a click returns a setting to the pack's value.
 * <p>
 * The field at the top searches every page at once, by a setting's name as the pack words it and by the name it has in
 * the pack's files. What matches is listed in one column with the page it is on; a page that matches can be opened
 * from there.
 */
public final class PackSettingsScreen extends Screen {

	private static final int ROW = 24;
	private static final int CELL = 20;
	private static final int GAP = 4;
	private static final int TOP = 56;
	private static final int FOOTER = 50;
	private static final int MOST_RESULTS = 300;

	/** A setting, or with {@code option} null a page, and what a search reads of it. */
	private record Found(MenuOption option, String page, String on, String words) {
	}

	private final Screen parent;
	private final PackSession session;
	private final PackMenu menu;
	private final PackLang lang;
	private final MenuValues values;
	private final Deque<String> walked = new ArrayDeque<>();
	private final List<Refreshed> live = new ArrayList<>();
	private final List<Found> index = new ArrayList<>();
	private final Map<String, String> parents = new HashMap<>();
	private PageList list;
	private EditBox search;
	private Button apply;
	private Button back;
	private String query = "";
	private int found;
	private String error;

	PackSettingsScreen(Screen parent, PackSession session) {
		super(Component.literal(session.menu().packName()));
		this.parent = parent;
		this.session = session;
		this.menu = session.menu();
		this.lang = this.menu.lang();
		this.values = MenuValues.of(this.menu, session.saved().values(), session.forcedText());
		this.walked.push("");
		readIndex();
	}

	/** Every page that can be walked to from the first, and each setting on the first page that places it. */
	private void readIndex() {
		Deque<String> waiting = new ArrayDeque<>();
		Set<String> pages = new HashSet<>();
		Set<String> options = new HashSet<>();
		waiting.add("");
		pages.add("");
		while (!waiting.isEmpty()) {
			String id = waiting.poll();
			MenuPage page = this.menu.page(id).orElse(null);
			if (page == null) continue;
			for (MenuSlot slot : page.slots()) {
				if (slot instanceof MenuSlot.Option placed) {
					MenuOption option = placed.option();
					if (options.add(option.name())) {
						this.index.add(new Found(option, null, id, plain(this.lang.option(option.name())) + " " + plain(option.name())));
					}
				} else if (slot instanceof MenuSlot.Link link && link.resolved() && pages.add(link.page())) {
					this.parents.put(link.page(), id);
					waiting.add(link.page());
					this.index.add(new Found(null, link.page(), id, plain(this.lang.page(link.page())) + " " + plain(link.page())));
				}
			}
		}
	}

	/** Without the section signs a pack colours its names with, in one case. */
	private static String plain(String text) {
		String stripped = text == null ? null : ChatFormatting.stripFormatting(text);
		return stripped == null ? "" : stripped.toLowerCase(Locale.ROOT);
	}

	/** The pages down to this one, by their names. */
	private String trail(String page) {
		Deque<String> names = new ArrayDeque<>();
		int steps = 0;
		for (String at = page; at != null && !at.isEmpty() && steps < 16; at = this.parents.get(at), steps++) {
			String name = ChatFormatting.stripFormatting(this.lang.page(at));
			names.push(name == null ? at : name);
		}
		return String.join(" / ", names);
	}

	private boolean searching() {
		return !this.query.isBlank();
	}

	private List<Found> matches() {
		List<String> words = new ArrayList<>();
		for (StringTokenizer tokens = new StringTokenizer(plain(this.query)); tokens.hasMoreTokens(); ) words.add(tokens.nextToken());
		List<Found> matching = new ArrayList<>();
		for (Found entry : this.index) {
			boolean all = true;
			for (String word : words) all &= entry.words().contains(word);
			if (all) matching.add(entry);
			if (matching.size() == MOST_RESULTS) break;
		}
		return matching;
	}

	@Override
	protected void init() {
		int fieldWidth = Math.min(260, this.width - 60);
		this.search = Flat.search(this.font, (this.width - fieldWidth) / 2, 36, fieldWidth, Component.translatable("opalcompat.screen.search"));
		this.search.setValue(this.query);
		this.search.setResponder(text -> {
			if (text.equals(this.query)) return;
			this.query = text;
			fillList();
		});
		addRenderableWidget(this.search);

		this.list = new PageList(this.minecraft, this.width, this.height - TOP - FOOTER, TOP);
		addRenderableWidget(this.list);

		int width = Math.min(100, (this.width - 20 - 3 * GAP) / 4);
		int x = (this.width - 4 * width - 3 * GAP) / 2;
		int y = this.height - 28;
		addRenderableWidget(new Flat.Action(x, y, width, Component.translatable("opalcompat.screen.reset"), false, b -> resetAll()));
		x += width + GAP;
		this.back = addRenderableWidget(new Flat.Action(x, y, width, CommonComponents.GUI_CANCEL, false, b -> goBack()));
		x += width + GAP;
		this.apply = addRenderableWidget(new Flat.Action(x, y, width, Component.translatable("opalcompat.screen.apply"), false, b -> applySettings()));
		x += width + GAP;
		addRenderableWidget(new Flat.Action(x, y, width, CommonComponents.GUI_DONE, true, b -> {
			applySettings();
			if (this.error == null) this.minecraft.setScreen(this.parent);
		}));
		fillList();
		setInitialFocus(this.search);
	}

	/** The page that is open, or what the search finds. */
	private void fillList() {
		this.live.clear();
		this.list.empty();
		if (searching()) {
			List<Found> matching = matches();
			this.found = matching.size();
			this.list.fill(matching);
		} else {
			this.menu.page(this.walked.peek()).ifPresent(this.list::fill);
		}
		refresh();
	}

	private void refresh() {
		this.live.forEach(Refreshed::refresh);
		this.apply.active = this.values.pendingCount() > 0;
		this.back.setMessage(searching() || this.walked.size() > 1 ? CommonComponents.GUI_BACK : CommonComponents.GUI_CANCEL);
	}

	/** Out of a search first, then up a page, then out of the screen. */
	private void goBack() {
		if (searching()) {
			this.search.setValue("");
		} else if (this.walked.size() > 1) {
			this.walked.pop();
			rebuildWidgets();
		} else {
			this.minecraft.setScreen(this.parent);
		}
	}

	private void open(String page) {
		this.query = "";
		this.walked.push(page);
		rebuildWidgets();
	}

	private void resetAll() {
		this.menu.optionNames().forEach(this.values::reset);
		refresh();
	}

	private void applySettings() {
		if (this.values.pendingCount() == 0) return;
		var stored = new SettingsFile.Stored(this.values.toSave());
		try {
			SettingsFile.write(this.session.settingsFile(), stored);
			this.error = null;
		} catch (IOException e) {
			OpalCompat.LOGGER.error("Cannot write {}", this.session.settingsFile(), e);
			this.error = e.toString();
			return;
		}
		this.values.rebase(stored.values(), this.session.forcedText());
		this.values.clearPending();
		if (this.session.packFileName().equals(ShaderPacks.selectedName())) PackRenderer.reload();
		refresh();
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
			setFocused(this.search);
			return true;
		}
		return super.keyPressed(key, scan, modifiers);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		Flat.searchGround(graphics, this.search);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		Component under;
		if (searching()) {
			under = Component.translatable("opalcompat.screen.results", this.found);
		} else {
			String trail = trail(this.walked.peek());
			under = trail.isEmpty() ? Component.translatable("opalcompat.screen.hint") : Component.literal(trail);
		}
		graphics.drawCenteredString(this.font, under, this.width / 2, 20, Flat.TEXT_DIM);
		Flat.searchHint(graphics, this.font, this.search, Component.translatable("opalcompat.screen.search"));
		if (searching() && this.found == 0) {
			graphics.drawCenteredString(this.font, Component.translatable("opalcompat.screen.no_results", this.query.trim()),
					this.width / 2, TOP + 20, Flat.TEXT_DIM);
		}
		int noteY = this.height - 42;
		if (this.error != null) {
			graphics.drawCenteredString(this.font, Component.literal(this.error).withStyle(ChatFormatting.RED), this.width / 2, noteY, 0xFFFFFFFF);
		} else if (this.values.pendingCount() > 0) {
			graphics.drawCenteredString(this.font, Component.translatable("opalcompat.screen.pending_changes", this.values.pendingCount()),
					this.width / 2, noteY, Flat.CHANGED);
		}
	}

	@Override
	public void onClose() {
		goBack();
	}

	/** Pack text keeps its own section signs. */
	private static MutableComponent pack(String text) {
		return Component.literal(text);
	}

	private interface Refreshed {
		void refresh();
	}

	/** A setting's name and its value in one line, for the narrator. */
	private Component label(MenuOption option, String value) {
		return pack(this.lang.option(option.name())).append(": ").append(shown(option, value));
	}

	private Component shown(MenuOption option, String value) {
		if (option.form() == MenuOption.Form.TOGGLE) return "on".equals(value) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
		return pack(this.lang.value(option.name(), value));
	}

	/** Yellow while what is shown is not what is drawn, the accent where it is not the pack's own value. */
	private int valueColour(AbstractWidget widget, MenuOption option, String value) {
		if (!widget.active) return Flat.TEXT_OFF;
		if (this.values.modified(option.name())) return Flat.CHANGED;
		return value.equals(option.defaultValue()) ? Flat.TEXT_DIM : Flat.ACCENT;
	}

	private Tooltip optionTooltip(MenuOption option) {
		return this.lang.optionComment(option.name()).map(text -> Tooltip.create(pack(text))).orElse(null);
	}

	private AbstractWidget widget(MenuSlot slot, int width) {
		return switch (slot) {
			case MenuSlot.Blank blank -> null;
			case MenuSlot.Option option -> optionWidget(option.option(), width, null);
			case MenuSlot.Link link -> new LinkCell(link.page(), link.resolved(), width, null);
			case MenuSlot.Profiles profiles -> new ProfileCell(width);
		};
	}

	private AbstractWidget optionWidget(MenuOption option, int width, String note) {
		boolean forced = this.values.forced(option.name());
		AbstractWidget widget = option.slider() ? new SliderCell(option, width, note) : new OptionCell(option, width, note);
		widget.active = !forced && option.form() != MenuOption.Form.FIXED;
		widget.setTooltip(optionTooltip(option));
		return widget;
	}

	/** A tile that steps with the left button and back with the right. */
	private abstract class Cell extends AbstractButton implements Refreshed {
		protected final String note;

		Cell(int width, String note) {
			super(0, 0, width, CELL, Component.empty());
			this.note = note;
			PackSettingsScreen.this.live.add(this);
		}

		abstract void step(int by);

		@Override
		public void onPress() {
			step(1);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button == 1 && this.active && this.visible && isHovered()) {
				playDownSound(Minecraft.getInstance().getSoundManager());
				step(-1);
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	private final class OptionCell extends Cell {
		private final MenuOption option;

		OptionCell(MenuOption option, int width, String note) {
			super(width, note);
			this.option = option;
			refresh();
		}

		@Override
		public void refresh() {
			setMessage(label(this.option, PackSettingsScreen.this.values.pending(this.option.name())));
		}

		@Override
		void step(int by) {
			var values = PackSettingsScreen.this.values;
			String name = this.option.name();
			if (Screen.hasShiftDown()) {
				values.reset(name);
			} else {
				values.queue(name, this.option.at(this.option.indexOf(values.pending(name)) + by));
			}
			PackSettingsScreen.this.refresh();
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			var screen = PackSettingsScreen.this;
			String value = screen.values.pending(this.option.name());
			boolean changed = screen.values.modified(this.option.name());
			Flat.tile(graphics, this);
			if (changed) graphics.fill(getX(), getY(), getX() + 2, getY() + getHeight(), Flat.CHANGED);
			int right = this.option.form() == MenuOption.Form.TOGGLE
					? Flat.toggle(graphics, this, "on".equals(value))
					: Flat.value(graphics, screen.font, this, shown(this.option, value), valueColour(this, this.option, value));
			Flat.name(graphics, screen.font, this, pack(screen.lang.option(this.option.name())), this.note, getX() + Flat.PAD + 2, right);
		}
	}

	private final class SliderCell extends AbstractSliderButton implements Refreshed {
		private final MenuOption option;
		private final String note;

		SliderCell(MenuOption option, int width, String note) {
			super(0, 0, width, CELL, Component.empty(), position(option, PackSettingsScreen.this.values.pending(option.name())));
			this.option = option;
			this.note = note;
			PackSettingsScreen.this.live.add(this);
			updateMessage();
		}

		private static double position(MenuOption option, String value) {
			return option.size() < 2 ? 0.0 : option.indexOf(value) / (double) (option.size() - 1);
		}

		@Override
		public void refresh() {
			this.value = position(this.option, PackSettingsScreen.this.values.pending(this.option.name()));
			updateMessage();
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (Screen.hasShiftDown() && button == 0 && this.active && this.visible && isHovered()) {
				PackSettingsScreen.this.values.reset(this.option.name());
				PackSettingsScreen.this.refresh();
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}

		@Override
		protected void updateMessage() {
			setMessage(label(this.option, PackSettingsScreen.this.values.pending(this.option.name())));
		}

		@Override
		protected void applyValue() {
			String value = this.option.at((int) Math.round(this.value * (this.option.size() - 1)));
			if (value.equals(PackSettingsScreen.this.values.pending(this.option.name()))) return;
			PackSettingsScreen.this.values.queue(this.option.name(), value);
			PackSettingsScreen.this.refresh();
		}

		/** The tile fills up to where the game's own slider would have its handle. */
		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			var screen = PackSettingsScreen.this;
			String value = screen.values.pending(this.option.name());
			Flat.tile(graphics, this);
			int reach = 4 + (int) Math.round(this.value * (getWidth() - 8));
			graphics.fill(getX(), getY(), getX() + reach, getY() + getHeight(), Flat.FILL);
			graphics.fill(getX() + reach - 1, getY(), getX() + reach + 1, getY() + getHeight(), this.active ? Flat.ACCENT : Flat.TEXT_OFF);
			if (screen.values.modified(this.option.name())) graphics.fill(getX(), getY(), getX() + 2, getY() + getHeight(), Flat.CHANGED);
			int right = Flat.value(graphics, screen.font, this, shown(this.option, value), valueColour(this, this.option, value));
			Flat.name(graphics, screen.font, this, pack(screen.lang.option(this.option.name())), this.note, getX() + Flat.PAD + 2, right);
		}
	}

	private final class LinkCell extends Cell {
		private final String page;

		LinkCell(String page, boolean resolved, int width, String note) {
			super(width, note);
			this.page = page;
			this.active = resolved;
			PackSettingsScreen.this.lang.pageComment(page).ifPresent(text -> setTooltip(Tooltip.create(pack(text))));
			refresh();
		}

		@Override
		public void refresh() {
			setMessage(pack(PackSettingsScreen.this.lang.page(this.page)));
		}

		@Override
		void step(int by) {
			if (by > 0) open(this.page);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			var screen = PackSettingsScreen.this;
			Flat.tile(graphics, this);
			int right = Flat.value(graphics, screen.font, this, Component.literal(">"), this.active ? Flat.ACCENT : Flat.TEXT_OFF);
			Flat.name(graphics, screen.font, this, getMessage(), this.note, getX() + Flat.PAD + 2, right);
		}
	}

	/** Walks the pack's profiles from the most constrained; what the values amount to is read back from them. */
	private final class ProfileCell extends Cell {
		private Component profile = Component.empty();

		ProfileCell(int width) {
			super(width, null);
			PackSettingsScreen.this.lang.profileComment().ifPresent(text -> setTooltip(Tooltip.create(pack(text))));
			refresh();
		}

		@Override
		public void refresh() {
			String current = PackSettingsScreen.this.values.matchedProfile();
			this.profile = current.isEmpty() ? Component.translatable("opalcompat.screen.profile_custom")
					: pack(PackSettingsScreen.this.lang.profile(current));
			setMessage(Component.translatable("opalcompat.screen.profile").append(": ").append(this.profile));
		}

		@Override
		void step(int by) {
			List<String> names = PackSettingsScreen.this.menu.profileNames();
			if (names.isEmpty()) return;
			int at = names.indexOf(PackSettingsScreen.this.values.matchedProfile());
			int next = at < 0 ? (by > 0 ? 0 : names.size() - 1) : Math.floorMod(at + by, names.size());
			PackSettingsScreen.this.values.queueProfile(names.get(next));
			PackSettingsScreen.this.refresh();
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			var screen = PackSettingsScreen.this;
			Flat.tile(graphics, this);
			int right = Flat.value(graphics, screen.font, this, this.profile, this.active ? Flat.ACCENT : Flat.TEXT_OFF);
			Flat.name(graphics, screen.font, this, Component.translatable("opalcompat.screen.profile"), null, getX() + Flat.PAD + 2, right);
		}
	}

	private final class PageList extends ContainerObjectSelectionList<PageList.Line> {

		PageList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, ROW);
		}

		@Override
		public int getRowWidth() {
			return Math.min(this.width - 40, 760);
		}

		@Override
		protected int getScrollbarPosition() {
			return (this.width + getRowWidth()) / 2 + 6;
		}

		/** The tiles stand on the screen's own background. */
		@Override
		protected void renderListBackground(GuiGraphics graphics) {
		}

		@Override
		protected void renderListSeparators(GuiGraphics graphics) {
			graphics.fill(getX(), getY() - 1, getRight(), getY(), Flat.LINE);
			graphics.fill(getX(), getBottom(), getRight(), getBottom() + 1, Flat.LINE);
		}

		void empty() {
			clearEntries();
			setScrollAmount(0.0);
		}

		void fill(MenuPage page) {
			int columns = Math.max(1, page.columns());
			int width = (getRowWidth() - (columns - 1) * GAP) / columns;
			List<AbstractWidget> widgets = new ArrayList<>(columns);
			int column = 0;
			for (MenuSlot slot : page.slots()) {
				widgets.add(PackSettingsScreen.this.widget(slot, width));
				if (++column == columns) {
					addEntry(new Line(widgets, width));
					widgets = new ArrayList<>(columns);
					column = 0;
				}
			}
			if (!widgets.isEmpty()) addEntry(new Line(widgets, width));
		}

		/** What a search found, one to a row, each with the page it is on. */
		void fill(List<Found> matching) {
			int width = getRowWidth();
			for (Iterator<Found> entries = matching.iterator(); entries.hasNext(); ) {
				Found entry = entries.next();
				String on = trail(entry.on());
				AbstractWidget widget = entry.option() != null
						? optionWidget(entry.option(), width, on)
						: new LinkCell(entry.page(), true, width, on);
				List<AbstractWidget> row = new ArrayList<>(1);
				row.add(widget);
				addEntry(new Line(row, width));
			}
		}

		/** One row of a page by position: a blank is a slot too, and a pack aligns its columns by them. */
		final class Line extends ContainerObjectSelectionList.Entry<Line> {
			private final List<AbstractWidget> slots;
			private final List<AbstractWidget> widgets;
			private final int width;

			Line(List<AbstractWidget> slots, int width) {
				this.slots = slots;
				this.widgets = slots.stream().filter(java.util.Objects::nonNull).toList();
				this.width = width;
			}

			@Override
			public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
					boolean hovering, float partialTick) {
				int x = left;
				for (AbstractWidget widget : this.slots) {
					if (widget != null) {
						widget.setPosition(x, top);
						widget.render(graphics, mouseX, mouseY, partialTick);
					}
					x += this.width + GAP;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return this.widgets;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return ImmutableList.copyOf(this.widgets);
			}
		}
	}
}
