package dev.opal.pack;

import java.io.IOException;
import java.nio.file.Path;

/** What Opal Compat calls of Opal, to compile without its jar. Not in the built jar. */
public final class PackSource {
	public static PackSource open(Path path) throws IOException {
		throw new AssertionError();
	}
}
