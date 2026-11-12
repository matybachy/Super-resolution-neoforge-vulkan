package dev.opalcompat.texture;

import dev.opalcompat.OpalCompat;
import dev.opalcompat.pack.ShaderPacks;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Animated textures tick only once something has drawn with them.
 * <p>
 * The game uploads the next frame of every animated sprite of every atlas twenty times a second, whether anything
 * shows it or not, and a large mod set has hundreds: every mod's fluids and machines, most of which are nowhere near
 * the player. Here a sprite is left alone until it is used: until its texture coordinates are asked for, a quad of it
 * is drawn or VulkanMod Reforged meshes one into a chunk. From then on it animates like any other, until the atlas
 * is stitched again. Its frame counter runs all along, so sprites that animate together stay together.
 * <p>
 * Baking the models asks every sprite for its coordinates once, and does not count. Nothing is ever taken back: a
 * mesh cached somewhere may still show a sprite long after it was built, and there is no telling from here.
 * <p>
 * Off with {@code animateUsedTexturesOnly=false} in {@code config/opalcompat.properties}, or on the Shaders page.
 */
public final class AnimatedSprites {

	private static final String KEY = "animateUsedTexturesOnly";
	private static final long LOG_EVERY_NANOS = TimeUnit.SECONDS.toNanos(60);
	private static final ThreadLocal<int[]> BAKING = ThreadLocal.withInitial(() -> new int[1]);

	private static volatile boolean enabled = ShaderPacks.flag(KEY, true);
	/** Render thread: an atlas is running its animations. Uploads outside of that are never touched. */
	private static boolean cycling;
	private static long uploaded;
	private static long leftAlone;
	private static long loggedAt = System.nanoTime();

	private AnimatedSprites() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void enable(boolean value) {
		enabled = value;
		try {
			ShaderPacks.setFlag(KEY, value);
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Cannot save {}", KEY, e);
		}
	}

	/** From any thread: this sprite is being drawn with. */
	public static void use(SpriteContents contents) {
		UsedSprite sprite = (UsedSprite) (Object) contents;
		if (sprite.opalcompat$used() || BAKING.get()[0] > 0) return;
		sprite.opalcompat$use();
	}

	public static void use(TextureAtlasSprite sprite) {
		if (sprite != null) use(sprite.contents());
	}

	/** Around the baking of the models, on the thread that bakes them. */
	public static void baking(int by) {
		BAKING.get()[0] += by;
	}

	public static void cycling(boolean now) {
		cycling = now;
		if (now) return;
		long time = System.nanoTime();
		if (time - loggedAt < LOG_EVERY_NANOS) return;
		if (uploaded + leftAlone > 0) {
			OpalCompat.LOGGER.info("[textures] in {} s: {} animation frames uploaded, {} left alone for textures nothing has drawn",
					TimeUnit.NANOSECONDS.toSeconds(time - loggedAt), uploaded, leftAlone);
		}
		uploaded = 0;
		leftAlone = 0;
		loggedAt = time;
	}

	/** Whether this frame of an animation stays where it is. */
	public static boolean leftAlone(UsedSprite sprite) {
		if (!cycling) return false;
		if (enabled && !sprite.opalcompat$used()) {
			leftAlone++;
			return true;
		}
		uploaded++;
		return false;
	}
}
