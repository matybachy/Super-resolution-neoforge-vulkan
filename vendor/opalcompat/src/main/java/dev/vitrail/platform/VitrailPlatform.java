// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/platform/VitrailPlatform.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.platform;

import java.nio.file.Path;

/**
 * The whole surface the loader modules have to implement. Kept deliberately small: if a
 * new method shows up here it should be because the common code genuinely cannot answer
 * the question on its own.
 */
public interface VitrailPlatform {

	String loaderName();

	String loaderVersion();

	String modVersion();

	String minecraftVersion();

	/** Root of the game instance, where the user-editable shader sources live. */
	Path gameDirectory();

	boolean isModLoaded(String modId);
}
