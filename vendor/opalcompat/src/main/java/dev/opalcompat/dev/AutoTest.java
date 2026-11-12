package dev.opalcompat.dev;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.pack.ShaderPacks;
import dev.opalcompat.render.BlinkProbe;
import dev.opalcompat.render.PackRenderer;
import dev.opalcompat.screen.ShaderPackScreen;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
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
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.device.DeviceManager;
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
 * Unattended test run for development: opens a fixed world, runs a script, and quits the game. Without a script it
 * waits until the terrain has been built, writes a screenshot and a result file.
 * <p>
 * Enabled with {@code -Dopalcompat.autotest=true}. Properties: {@code opalcompat.autotest.frames} (frames to render before
 * the picture counts as settled), {@code opalcompat.autotest.out} (output directory), {@code opalcompat.autotest.camera}
 * ({@code x,z,yaw,pitch,heightAboveGround}), {@code opalcompat.autotest.time} (world time),
 * {@code opalcompat.autotest.timeout} (seconds) and {@code opalcompat.autotest.script} (a file of the lines {@link #run}
 * reads, one a line, {@code #} for a comment).
 */
public final class AutoTest {

	public static final boolean ENABLED = Boolean.getBoolean("opalcompat.autotest");
	private static final List<String> DEFAULT_SCRIPT = List.of("settle", "shot screenshot", "quit");
	public static final AutoTest INSTANCE = new AutoTest();

	/** Frames VulkanMod Reforged has presented, counted by {@code RendererMixin}. */
	public static volatile long presentedFrames;

	private static final String WORLD = "opalcompat-autotest";
	private static final long SEED = 20261027L;
	/** Ticks the chunk builder must stay idle before the picture counts as settled. */
	private static final int IDLE_TICKS = 40;

	private enum State { START, LOADING, RUNNING, DONE }

	private final int frames = Integer.getInteger("opalcompat.autotest.frames", 120);
	private final int timeoutSeconds = Integer.getInteger("opalcompat.autotest.timeout", 300);
	private final Path out = Path.of(System.getProperty("opalcompat.autotest.out", "autotest"));
	private final double[] camera = parseCamera(System.getProperty("opalcompat.autotest.camera", "0,0,135,20,14"));
	private final int time = Integer.getInteger("opalcompat.autotest.time", 6000);
	private final long startNanos = System.nanoTime();
	private final List<String> notes = new ArrayList<>();
	private final Deque<String> script = new ArrayDeque<>();

	private State state = State.START;
	private int waitTicks;
	private boolean settling;
	private boolean scriptScreen;
	private long settleStartFrame;
	private int idleTicks;
	private int settleTicks;
	private String shotName;
	private int shotFrames;
	private String fpsName;
	private long fpsEnd;
	private final List<Long> fpsFrames = new ArrayList<>();
	private long lastFrame;
	private net.minecraft.client.KeyMapping held;
	private net.minecraft.client.KeyMapping lastHeld;
	private float turnPerTick;
	private double driftPerTick;

	private AutoTest() {
		if (!ENABLED) return;
		try {
			Files.createDirectories(this.out);
			Files.deleteIfExists(this.out.resolve("result.txt"));
			String file = System.getProperty("opalcompat.autotest.script", "");
			this.script.addAll(file.isEmpty() ? DEFAULT_SCRIPT : Files.readAllLines(Path.of(file)));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot prepare " + this.out, e);
		}
		Thread watchdog = new Thread(this::watchdog, "OpalCompat autotest watchdog");
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
					configureOptions(mc);
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
			case RUNNING -> {
				moveCamera(mc);
				advance(mc);
			}
			case DONE -> mc.stop();
		}
	}

	private void advance(Minecraft mc) {
		// A screen the script did not ask for is someone at the window; it would be in every picture from here on.
		if (mc.screen != null && !this.scriptScreen) mc.setScreen(null);
		if (this.settling) {
			this.settleTicks++;
			boolean busy = WorldRenderer.getInstance().hasPendingSectionWork();
			this.idleTicks = busy ? 0 : this.idleTicks + 1;
			boolean settled = presentedFrames - this.settleStartFrame >= this.frames && this.idleTicks >= IDLE_TICKS;
			// Give up waiting for an idle builder after a while, but still go on.
			if (settled || this.settleTicks > 20 * 60) {
				if (!settled) this.notes.add("chunk builder never went idle");
				this.settling = false;
			}
			return;
		}
		if (this.waitTicks > 0) {
			this.waitTicks--;
			return;
		}
		if (this.shotName != null || this.fpsName != null) return;
		while (!this.script.isEmpty() && this.waitTicks == 0 && !this.settling && this.shotName == null
				&& this.fpsName == null && this.state == State.RUNNING) {
			String line = this.script.poll().trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			OpalCompat.LOGGER.info("[autotest] > {}", line);
			try {
				run(mc, line);
			} catch (RuntimeException | IOException e) {
				OpalCompat.LOGGER.error("[autotest] {} failed", line, e);
				this.notes.add(line + " failed: " + e);
			}
		}
		if (this.script.isEmpty() && this.waitTicks == 0 && !this.settling && this.shotName == null && this.fpsName == null
				&& this.state == State.RUNNING) {
			writeResult("OK");
			this.state = State.DONE;
		}
	}

	/** One line of a script. */
	private void run(Minecraft mc, String line) throws IOException {
		int space = line.indexOf(' ');
		String word = space < 0 ? line : line.substring(0, space);
		String rest = space < 0 ? "" : line.substring(space + 1).trim();
		String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
		switch (word) {
			case "quit" -> {
				this.script.clear();
			}
			// A command on the integrated server, as an operator.
			case "cmd" -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), rest));
			}
			case "wait" -> this.waitTicks = Integer.parseInt(args[0]);
			case "settle" -> {
				this.settling = true;
				this.settleTicks = 0;
				this.idleTicks = 0;
				this.settleStartFrame = presentedFrames;
			}
			// Screenshot after the frames that are on their way, so that what the last line did has reached it.
			case "shot" -> {
				this.shotName = rest;
				this.shotFrames = 3;
			}
			// Every render target of the pack as PNG, next to the screenshots.
			case "dump" -> PackRenderer.requestDump(this.out.resolve(rest).toString());
			case "fps" -> {
				this.fpsName = args[0];
				this.fpsEnd = System.nanoTime() + (long) (Double.parseDouble(args[1]) * 1e9);
				this.fpsFrames.clear();
				this.lastFrame = 0;
			}
			case "gui" -> mc.options.hideGui = !Boolean.parseBoolean(rest);
			case "slot" -> mc.player.getInventory().selected = Integer.parseInt(rest);
			case "distance" -> mc.options.renderDistance().set(Integer.parseInt(rest));
			case "graphics" -> {
				mc.options.graphicsMode().set(GraphicsStatus.valueOf(rest.toUpperCase(Locale.ROOT)));
				mc.levelRenderer.allChanged();
			}
			case "resize" -> GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), Integer.parseInt(args[0]), Integer.parseInt(args[1]));
			case "minimize" -> GLFW.glfwIconifyWindow(mc.getWindow().getWindow());
			case "restore" -> GLFW.glfwRestoreWindow(mc.getWindow().getWindow());
			case "pack" -> {
				ShaderPacks.select(rest);
				PackRenderer.reload();
			}
			case "reload" -> PackRenderer.reload();
			case "screen" -> {
				this.scriptScreen = !rest.equals("close");
				mc.setScreen(switch (rest) {
				case "video" -> new VOptionScreen(Component.literal("Video Setting"), null);
				case "packs" -> new ShaderPackScreen(null);
				case "options" -> new net.minecraft.client.gui.screens.options.OptionsScreen(null, mc.options);
				case "close" -> null;
				default -> newScreen(mc, rest);
				});
			}
			// A left click in GUI coordinates, or on the first widget whose text holds the words.
			case "click" -> click(mc, Double.parseDouble(args[0]), Double.parseDouble(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 0);
			case "press" -> {
				AbstractWidget widget = find(mc.screen.children(), rest);
				if (widget == null) throw new IllegalArgumentException("No widget says " + rest);
				click(mc, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0);
			}
			// An upload to a texture with no image yet, what Steam 'n' Rails does while the game loads.
			case "emptyupload" -> {
				int id = TextureUtil.generateTextureId();
				GlStateManager._bindTexture(id);
				try (NativeImage image = new NativeImage(1, 1, false)) {
					image.upload(0, 0, 0, false);
				}
				TextureUtil.releaseTextureId(id);
				this.notes.add("empty upload passed");
			}
			// What shows for a frame or three and is gone: BlinkProbe.
			case "probe" -> {
				if (rest.equals("stop")) this.notes.add(BlinkProbe.stop());
				else BlinkProbe.start(this.out);
			}
			// A key held down until "hold none", and the camera turned this many degrees a tick.
			case "hold" -> this.held = rest.equals("none") ? null : key(mc, rest);
			case "turn" -> this.turnPerTick = Float.parseFloat(rest);
			case "bob" -> mc.options.bobView().set(Boolean.parseBoolean(rest));
			case "fly" -> {
				mc.player.getAbilities().flying = Boolean.parseBoolean(rest);
				mc.player.onUpdateAbilities();
			}
			// The player moved this many blocks a tick along x: slow enough that nothing on the screen moves to speak of.
			case "drift" -> this.driftPerTick = Double.parseDouble(rest);
			case "note" -> this.notes.add(rest);
			case "error" -> this.notes.add("last error: " + PackRenderer.lastError().orElse("none"));
			default -> throw new IllegalArgumentException("Unknown line");
		}
	}

	private static net.minecraft.client.KeyMapping key(Minecraft mc, String name) {
		return switch (name) {
			case "forward" -> mc.options.keyUp;
			case "back" -> mc.options.keyDown;
			case "left" -> mc.options.keyLeft;
			case "right" -> mc.options.keyRight;
			case "up" -> mc.options.keyJump;
			default -> throw new IllegalArgumentException("No key " + name);
		};
	}

	private void moveCamera(Minecraft mc) {
		if (mc.player == null) return;
		// Someone at the window would move the camera; the script is what is measured.
		if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
		if (this.lastHeld != null && this.lastHeld != this.held) this.lastHeld.setDown(false);
		if (this.held != null) this.held.setDown(true);
		this.lastHeld = this.held;
		if (this.turnPerTick != 0.0F) mc.player.setYRot(mc.player.getYRot() + this.turnPerTick);
		if (this.driftPerTick != 0.0) mc.player.setPos(mc.player.getX() + this.driftPerTick, mc.player.getY(), mc.player.getZ());
	}

	/** Another mod's screen, by the name of its class. */
	private static net.minecraft.client.gui.screens.Screen newScreen(Minecraft mc, String name) {
		try {
			return (net.minecraft.client.gui.screens.Screen) Class.forName(name)
					.getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance(mc.screen);
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException(name, e);
		}
	}

	private static AbstractWidget find(List<? extends GuiEventListener> widgets, String text) {
		for (GuiEventListener listener : widgets) {
			if (listener instanceof AbstractWidget widget && widget.getMessage().getString().contains(text)) return widget;
			if (listener instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
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

	private void configureOptions(Minecraft mc) {
		mc.options.pauseOnLostFocus = false;
		mc.options.onboardAccessibility = false;
		mc.options.hideGui = true;
		mc.options.renderDistance().set(8);
	}

	private void openWorld(Minecraft mc) {
		if (mc.getLevelSource().levelExists(WORLD)) {
			OpalCompat.LOGGER.info("[autotest] opening world {}", WORLD);
			mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("could not open " + WORLD));
			return;
		}
		OpalCompat.LOGGER.info("[autotest] creating world {} with seed {}", WORLD, SEED);
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.PEACEFUL,
				true, rules, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(SEED, true, false),
				WorldPresets::createNormalWorldDimensions, mc.screen);
	}

	/** Runs on the server thread: fixed time, weather and camera. */
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
		double seconds = (System.nanoTime() - this.startNanos) / 1e9;
		StringBuilder text = new StringBuilder();
		text.append("status=").append(status).append('\n');
		text.append("frames=").append(presentedFrames).append('\n');
		text.append(String.format(Locale.ROOT, "elapsed_s=%.1f%n", seconds));
		text.append("device=").append(DeviceManager.device != null ? DeviceManager.device.deviceName : "?").append('\n');
		text.append("pack=").append(ShaderPacks.selectedName()).append('\n');
		text.append("pack_error=").append(PackRenderer.lastError().orElse("none").lines().findFirst().orElse("")).append('\n');
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
