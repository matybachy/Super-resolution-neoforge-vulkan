package net.vulkanmod.config;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.RangeOption;

import java.util.List;
import java.lang.reflect.Method;

public final class DlssSettings {
    private static boolean enabled = Boolean.getBoolean("superresolution.dlss");
    private static String quality = System.getProperty("superresolution.dlss.quality", "QUALITY");
    private static int sharpness = loadSharpness();
    private static int preExposure = loadPercent("superresolution.dlss.preExposure");
    private static int exposureScale = loadPercent("superresolution.dlss.exposureScale");

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
                                () -> sharpness),
                        new RangeOption(Component.literal("DLSS pre-exposure"), 1, 200, 1,
                                value -> Component.literal(String.format("%.2fx", value / 100.0F)),
                                value -> {
                                    preExposure = value;
                                    System.setProperty("superresolution.dlss.preExposure",
                                            Float.toString(value / 100.0F));
                                },
                                () -> preExposure),
                        new RangeOption(Component.literal("DLSS exposure scale"), 1, 200, 1,
                                value -> Component.literal(String.format("%.2fx", value / 100.0F)),
                                value -> {
                                    exposureScale = value;
                                    System.setProperty("superresolution.dlss.exposureScale",
                                            Float.toString(value / 100.0F));
                                },
                                () -> exposureScale)
                })
        }));
    }

    public static void apply() {
        try {
            Class<?> installer = Class.forName("dev.matybachy.superresolution.VulkanResolveInstaller");
            Class<?> configType = Class.forName("dev.matybachy.superresolution.DlssConfig");
            Class<?> algorithmType = Class.forName("dev.matybachy.superresolution.DlssAlgorithm");
            Object algorithm = Enum.valueOf((Class) algorithmType, quality);
            Object config = configType.getConstructor(boolean.class, algorithmType, float.class)
                    .newInstance(enabled, algorithm, sharpness / 100.0F);
            Method reload = installer.getMethod("reload", configType, int.class, int.class);
            reload.invoke(null, config, Minecraft.getInstance().getWindow().getWidth(),
                    Minecraft.getInstance().getWindow().getHeight());
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("DLSS settings could not be applied: " + error.getMessage());
        }
    }

    private static int loadSharpness() {
        try {
            return Math.max(0, Math.min(100, Math.round(Float.parseFloat(
                    System.getProperty("superresolution.dlss.sharpness", "0.2")) * 100.0F)));
        } catch (NumberFormatException ignored) {
            return 20;
        }

        private static int loadPercent(String property) {
            try {
                return Math.max(1, Math.min(200, Math.round(Float.parseFloat(
                        System.getProperty(property, "1.0")) * 100.0F)));
            } catch (NumberFormatException ignored) {
                return 100;
            }
        }
    }
}
