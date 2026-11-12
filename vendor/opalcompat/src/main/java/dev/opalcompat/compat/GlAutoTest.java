package dev.opalcompat.compat;

import com.mojang.blaze3d.platform.NativeImage;
import dev.opal.client.Opal;
import dev.opal.client.OpalClient;
import dev.opalcompat.compat.Mods;
import dev.opalcompat.OpalCompat;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The test run of {@code AutoTest} for a game without VulkanMod Reforged, that is with Opal on OpenGL: the same properties, the
 * same world and the same script lines where they mean anything there. Only in test instances, off by default.
 */
public final class GlAutoTest {

	public static final boolean ENABLED = Boolean.getBoolean("opalcompat.autotest") && !Mods.VULKAN;
	private static final List<String> DEFAULT_SCRIPT = List.of("settle", "shot screenshot", "quit");
	public static final GlAutoTest INSTANCE = new GlAutoTest();

	private static final String WORLD = "opalcompat-autotest";
	private static final long SEED = 20261027L;
	private static final int SETTLE_TICKS = 200;

	private enum State { START, LOADING, RUNNING, DONE }

	private final int timeoutSeconds = Integer.getInteger("opalcompat.autotest.timeout", 300);
	private final Path out = Path.of(System.getProperty("opalcompat.autotest.out", "autotest"));
	private final double[] camera = parseCamera(System.getProperty("opalcompat.autotest.camera", "0,0,135,20,14"));
	private final int time = Integer.getInteger("opalcompat.autotest.time", 6000);
	private final long startNanos = System.nanoTime();
	private final List<String> notes = new ArrayList<>();
	private final Deque<String> script = new ArrayDeque<>();

	private State state = State.START;
	private int waitTicks;
	private String shotName;
	private int shotFrames;
	private String fpsName;
	private long fpsEnd;
	private final List<Long> fpsFrames = new ArrayList<>();
	private long lastFrame;
	private long frames;

	private GlAutoTest() {
		if (!ENABLED) return;
		try {
			Files.createDirectories(this.out);
			Files.deleteIfExists(this.out.resolve("result.txt"));
			String file = System.getProperty("opalcompat.autotest.script", "");
			this.script.addAll(file.isEmpty() ? DEFAULT_SCRIPT : Files.readAllLines(Path.of(file)));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot prepare " + this.out, e);
		}
		Thread watchdog = new Thread(this::watchdog, "Opal Compat autotest watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	@SubscribeEvent
	public void onTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		switch (this.state) {
			case START -> {
				boolean menu = mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen;
				if (mc.getOverlay() == null && menu && mc.level == null) {
					mc.options.pauseOnLostFocus = false;
					mc.options.onboardAccessibility = false;
					mc.options.hideGui = true;
					mc.options.renderDistance().set(8);
					String pack = System.getProperty("opalcompat.pack");
					if (pack != null && Mods.OPAL) choosePack(pack);
					openWorld(mc);
					this.state = State.LOADING;
				}
			}
			case LOADING -> {
				IntegratedServer server = mc.getSingleplayerServer();
				if (mc.level != null && mc.player != null && server != null) {
					server.execute(() -> setUpWorld(server));
					this.state = State.RUNNING;
					OpalCompat.LOGGER.info("[autotest] world joined");
				}
			}
			case RUNNING -> advance(mc);
			case DONE -> mc.stop();
		}
	}

	private void advance(Minecraft mc) {
		if (this.waitTicks > 0) {
			this.waitTicks--;
			return;
		}
		if (this.shotName != null || this.fpsName != null) return;
		while (!this.script.isEmpty() && this.waitTicks == 0 && this.shotName == null && this.fpsName == null && this.state == State.RUNNING) {
			String line = this.script.poll().trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			OpalCompat.LOGGER.info("[autotest] > {}", line);
			try {
				run(mc, line);
			} catch (RuntimeException | ReflectiveOperationException e) {
				OpalCompat.LOGGER.error("[autotest] {} failed", line, e);
				this.notes.add(line + " failed: " + e);
			}
		}
		if (this.script.isEmpty() && this.waitTicks == 0 && this.shotName == null && this.fpsName == null && this.state == State.RUNNING) {
			writeResult("OK");
			this.state = State.DONE;
		}
	}

	private void run(Minecraft mc, String line) throws ReflectiveOperationException {
		int space = line.indexOf(' ');
		String word = space < 0 ? line : line.substring(0, space);
		String rest = space < 0 ? "" : line.substring(space + 1).trim();
		String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
		switch (word) {
			case "quit" -> this.script.clear();
			case "cmd" -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), rest));
			}
			case "wait" -> this.waitTicks = Integer.parseInt(args[0]);
			case "settle" -> this.waitTicks = SETTLE_TICKS;
			case "shot" -> {
				this.shotName = rest;
				this.shotFrames = 3;
			}
			case "fps" -> {
				this.fpsName = args[0];
				this.fpsEnd = System.nanoTime() + (long) (Double.parseDouble(args[1]) * 1e9);
				this.fpsFrames.clear();
				this.lastFrame = 0;
			}
			case "gui" -> mc.options.hideGui = !Boolean.parseBoolean(rest);
			case "distance" -> mc.options.renderDistance().set(Integer.parseInt(rest));
			case "graphics" -> {
				mc.options.graphicsMode().set(GraphicsStatus.valueOf(rest.toUpperCase(Locale.ROOT)));
				mc.levelRenderer.allChanged();
			}
			case "resize" -> GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), Integer.parseInt(args[0]), Integer.parseInt(args[1]));
			case "minimize" -> GLFW.glfwIconifyWindow(mc.getWindow().getWindow());
			case "restore" -> GLFW.glfwRestoreWindow(mc.getWindow().getWindow());
			case "pack" -> choosePack(rest);
			case "reload" -> Opal.requestReload();
			case "screen" -> mc.setScreen(rest.equals("close") ? null
					: (Screen) Class.forName(rest).getConstructor(Screen.class).newInstance(mc.screen));
			case "click" -> click(mc, Double.parseDouble(args[0]), Double.parseDouble(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 0);
			case "press" -> {
				AbstractWidget widget = find(mc.screen.children(), rest);
				if (widget == null) throw new IllegalArgumentException("No widget says " + rest);
				click(mc, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0);
			}
			case "note" -> this.notes.add(rest);
			case "error" -> this.notes.add("last error: " + Opal.lastError);
			default -> throw new IllegalArgumentException("Unknown line");
		}
	}

	private static void choosePack(String pack) {
		OpalClient.config.pack = pack;
		OpalClient.config.enabled = !pack.isEmpty();
		Opal.requestReload();
	}

	private static AbstractWidget find(List<? extends GuiEventListener> widgets, String text) {
		for (GuiEventListener listener : widgets) {
			if (listener instanceof AbstractWidget widget && widget.getMessage().getString().contains(text)) return widget;
			if (listener instanceof ContainerEventHandler container) {
				AbstractWidget found = find(container.children(), text);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static void click(Minecraft mc, double x, double y, int button) {
		mc.screen.mouseClicked(x, y, button);
		mc.screen.mouseReleased(x, y, button);
	}

	@SubscribeEvent
	public void onFrame(RenderFrameEvent.Post event) {
		this.frames++;
		if (this.fpsName != null) {
			long now = System.nanoTime();
			if (this.lastFrame != 0) this.fpsFrames.add(now - this.lastFrame);
			this.lastFrame = now;
			if (now >= this.fpsEnd) finishFps();
		}
		if (this.shotName == null || --this.shotFrames > 0) return;
		String name = this.shotName;
		this.shotName = null;
		try (NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
			image.writeToFile(this.out.resolve(name + ".png"));
		} catch (Exception e) {
			OpalCompat.LOGGER.error("[autotest] screenshot failed", e);
			this.notes.add("screenshot " + name + " failed: " + e);
		}
	}

	private void finishFps() {
		long[] times = this.fpsFrames.stream().mapToLong(Long::longValue).sorted().toArray();
		String name = this.fpsName;
		this.fpsName = null;
		if (times.length == 0) return;
		double total = Arrays.stream(times).sum() / 1e9;
		int worst = Math.max(1, times.length / 100);
		double low = 0;
		for (int i = times.length - worst; i < times.length; i++) low += times[i];
		this.notes.add(String.format(Locale.ROOT, "fps %s: avg %.1f, 1%% low %.1f, %d frames", name, times.length / total,
				worst / (low / 1e9), times.length));
	}

	private void openWorld(Minecraft mc) {
		if (mc.getLevelSource().levelExists(WORLD)) {
			mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("could not open " + WORLD));
			return;
		}
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(SEED, true, false), WorldPresets::createNormalWorldDimensions, mc.screen);
	}

	private void setUpWorld(IntegratedServer server) {
		ServerLevel level = server.overworld();
		int x = (int) this.camera[0];
		int z = (int) this.camera[1];
		level.getChunk(x >> 4, z >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + (int) this.camera[4];
		var source = server.createCommandSourceStack();
		for (String command : List.of(
				"gamemode spectator @a",
				"time set " + this.time,
				"weather clear",
				"gamerule doDaylightCycle false",
				"gamerule doWeatherCycle false",
				String.format(Locale.ROOT, "tp @a %d.5 %d %d.5 %.1f %.1f", x, y, z, this.camera[2], this.camera[3]))) {
			server.getCommands().performPrefixedCommand(source, command);
		}
	}

	private void writeResult(String status) {
		StringBuilder text = new StringBuilder();
		text.append("status=").append(status).append('\n');
		text.append("frames=").append(this.frames).append('\n');
		text.append(String.format(Locale.ROOT, "elapsed_s=%.1f%n", (System.nanoTime() - this.startNanos) / 1e9));
		if (Mods.OPAL) {
			text.append("pack=").append(OpalClient.config.pack).append(" enabled=").append(OpalClient.config.enabled).append('\n');
			text.append("opal_active=").append(Opal.active()).append('\n');
			text.append("pack_error=").append(Opal.lastError).append('\n');
		}
		for (String note : this.notes) text.append("note=").append(note).append('\n');
		try {
			Files.writeString(this.out.resolve("result.txt"), text);
		} catch (IOException e) {
			OpalCompat.LOGGER.error("[autotest] cannot write result", e);
		}
		OpalCompat.LOGGER.info("[autotest] {}", text.toString().replace('\n', ' '));
	}

	private void fail(String reason) {
		this.notes.add(reason);
		writeResult("FAILED");
		this.state = State.DONE;
	}

	private void watchdog() {
		try {
			Thread.sleep(this.timeoutSeconds * 1000L);
		} catch (InterruptedException e) {
			return;
		}
		if (this.state == State.DONE) return;
		this.notes.add("timed out in state " + this.state + " with " + this.script.size() + " lines to go");
		writeResult("TIMEOUT");
		Runtime.getRuntime().halt(3);
	}

	private static double[] parseCamera(String spec) {
		String[] parts = spec.split(",");
		double[] values = new double[5];
		for (int i = 0; i < values.length; i++) values[i] = Double.parseDouble(parts[i].trim());
		return values;
	}
}
