package net.vulkanmod.config;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.RangeOption;

import java.util.List;

public final class DlssSettings {
    private static boolean enabled = Boolean.getBoolean("superresolution.dlss");
    private static String quality = System.getProperty("superresolution.dlss.quality", "QUALITY");
    private static int sharpness = loadSharpness();

    private DlssSettings() {
    }

    public static List<OptionPage> pages() {
        return List.of(new OptionPage("DLSS", new OptionBlock[]{
                new OptionBlock("", new net.vulkanmod.config.option.Option[]{
                        new CyclingOption<>(Component.literal("Enable DLSS"),
                                new Boolean[]{false, true},
                                value -> {
                                    enabled = value;
                                    System.setProperty("superresolution.dlss", Boolean.toString(value));
                                },
                                () -> enabled),
                        new CyclingOption<>(Component.literal("DLSS quality"),
                                new String[]{"QUALITY", "BALANCED", "PERFORMANCE", "ULTRA_PERFORMANCE"},
                                value -> {
                                    quality = value;
                                    System.setProperty("superresolution.dlss.quality", value);
                                },
                                () -> quality),
                        new RangeOption(Component.literal("DLSS sharpness"), 0, 100, 5,
                                value -> Component.literal(value + "%"),
                                value -> {
                                    sharpness = value;
                                    System.setProperty("superresolution.dlss.sharpness",
                                            Float.toString(value / 100.0F));
                                },
                                () -> sharpness)
                })
        }));
    }

    private static int loadSharpness() {
        try {
            return Math.max(0, Math.min(100, Math.round(Float.parseFloat(
                    System.getProperty("superresolution.dlss.sharpness", "0.2")) * 100.0F)));
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }
}
