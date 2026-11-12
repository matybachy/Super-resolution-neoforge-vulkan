package dev.opalcompat.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.vulkan.util.ColorUtil;

import java.util.function.Supplier;

/**
 * A row of VulkanMod Reforged's settings lists that does something when clicked, or with no action only says something.
 * It holds no value, so nothing waits for Apply.
 */
public final class ActionOption extends Option<Boolean> {

	private final Runnable action;

	public ActionOption(Component name, Supplier<Component> text, Runnable action) {
		super(name, value -> {}, () -> Boolean.FALSE, value -> text.get());
		this.action = action;
	}

	@Override
	protected OptionWidget<?> createWidget() {
		return new Widget(this, this.name);
	}

	private static final class Widget extends OptionWidget<ActionOption> {

		private final ActionOption owner;

		Widget(ActionOption owner, Component name) {
			super(owner, name);
			this.owner = owner;
		}

		@Override
		protected void renderControls(double mouseX, double mouseY) {
			if (this.owner.action != null) {
				int top = this.y + 2;
				int height = this.height - 4;
				float fill = !this.active ? 0.1F : this.controlHovered ? 0.35F : 0.2F;
				GuiRenderer.fillBox(this.controlX, top, this.controlWidth, height, ColorUtil.ARGB.pack(fill, fill, fill, 0.9F));
				GuiRenderer.renderBoxBorder(this.controlX, top, this.controlWidth, height, 1, ColorUtil.ARGB.pack(0.6F, 0.6F, 0.6F, 1.0F));
			}
			GuiRenderer.drawScrollingString(Minecraft.getInstance().font, getDisplayedValue(), this.controlX + this.controlWidth / 2,
					this.y + (this.height - 9) / 2, this.controlWidth - 8, this.active ? 0xFFFFFFFF : 0xFFA0A0A0);
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			if (this.owner.action != null) this.owner.action.run();
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
		}

		@Override
		protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
		}
	}
}
