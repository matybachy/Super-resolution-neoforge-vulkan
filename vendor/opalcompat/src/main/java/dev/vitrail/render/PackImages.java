// OpalCompat: stand-in for Vitrail's PackImages (custom textures a pack ships, texture.<stage>.<name>
// in shaders.properties). A pack gets none yet and the note says so. LGPL-3.0.
package dev.vitrail.render;

import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.util.List;
import java.util.Map;

/** The custom textures of a pack: none yet on this backend. */
final class PackImages {

	private static final PackImages NONE = new PackImages(List.of());

	private final List<String> notes;

	private PackImages(List<String> notes) {
		this.notes = List.copyOf(notes);
	}

	static PackImages none() {
		return NONE;
	}

	static PackImages read(ShaderProperties properties, Map<String, String> defines, ShaderPackSource source) {
		return new PackImages(List.of("custom textures (texture.*) are not loaded on VulkanMod Reforged yet"));
	}

	List<String> notes() {
		return this.notes;
	}
}
