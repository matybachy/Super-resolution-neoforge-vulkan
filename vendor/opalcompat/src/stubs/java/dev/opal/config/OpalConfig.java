package dev.opal.config;

import java.io.IOException;
import java.nio.file.Path;

/** What Opal Compat calls of Opal, to compile without its jar. Not in the built jar. */
public final class OpalConfig {
	public boolean enabled;
	public String pack;

	public void save(Path file) throws IOException {
		throw new AssertionError();
	}
}
