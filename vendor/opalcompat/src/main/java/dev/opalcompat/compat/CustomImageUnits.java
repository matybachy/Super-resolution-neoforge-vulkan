package dev.opalcompat.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.opalcompat.OpalCompat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL46C;

import java.util.List;

/**
 * Image and sampler units for Opal's custom images, handed out for the program in use each time it is bound, which is what
 * Iris does too: the limit of the hardware is on what one program uses, not on what a pack declares. Opal calls this right
 * after it makes a program current, for every draw state, so a unit another program used last is simply bound again.
 */
public final class CustomImageUnits {

	/** What Opal leaves free: its first seven images have the sampler units 20 to 26, custom textures go up to 39. */
	private static final int OPAL_SAMPLER_UNIT = 20;
	private static final int OPAL_SAMPLER_UNITS = 7;
	private static final int MORE_SAMPLER_UNIT = 40;

	private static final Int2ObjectOpenHashMap<int[]> LOCATIONS = new Int2ObjectOpenHashMap<>();
	private static int maxUnits;
	private static boolean warned;

	private CustomImageUnits() {
	}

	/** Read from a record of Opal's; see {@code OpalImageMixin}. */
	public interface Image {
		String opalcompat$uniform();

		String opalcompat$sampler();

		int opalcompat$texture();

		int opalcompat$target();

		int opalcompat$format();
	}

	/** New pack, new programs; their ids may be the old ones. */
	public static void forget() {
		LOCATIONS.clear();
		warned = false;
	}

	public static void bind(List<?> images) {
		if (images.isEmpty()) return;
		if (maxUnits == 0) maxUnits = GL46C.glGetInteger(GL46C.GL_MAX_IMAGE_UNITS);
		int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
		int[] locations = LOCATIONS.get(program);
		if (locations == null) {
			locations = locate(images, program);
			LOCATIONS.put(program, locations);
		}
		int unit = 0;
		for (int i = 0; i < images.size(); i++) {
			Image image = (Image) images.get(i);
			int location = locations[i * 2];
			if (location >= 0) {
				if (unit < maxUnits) {
					GL46C.glBindImageTexture(unit, image.opalcompat$texture(), 0, image.opalcompat$target() == GL46C.GL_TEXTURE_3D, 0,
							GL46C.GL_READ_WRITE, image.opalcompat$format());
					GL46C.glUniform1i(location, unit);
					unit++;
				} else if (!warned) {
					warned = true;
					OpalCompat.LOGGER.warn("Program {} uses more custom images than the {} image units there are", program, maxUnits);
				}
			}
			location = locations[i * 2 + 1];
			if (location >= 0) {
				int samplerUnit = i < OPAL_SAMPLER_UNITS ? OPAL_SAMPLER_UNIT + i : MORE_SAMPLER_UNIT + i - OPAL_SAMPLER_UNITS;
				GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + samplerUnit);
				GL46C.glBindTexture(image.opalcompat$target(), image.opalcompat$texture());
				GL46C.glActiveTexture(GlStateManager._getActiveTexture());
				GL46C.glUniform1i(location, samplerUnit);
			}
		}
	}

	private static int[] locate(List<?> images, int program) {
		int[] locations = new int[images.size() * 2];
		for (int i = 0; i < images.size(); i++) {
			Image image = (Image) images.get(i);
			locations[i * 2] = GL46C.glGetUniformLocation(program, image.opalcompat$uniform());
			locations[i * 2 + 1] = GL46C.glGetUniformLocation(program, image.opalcompat$sampler());
		}
		return locations;
	}
}
