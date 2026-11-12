package dev.opal.client;

/** What Opal Compat calls of Opal, to compile without its jar. Not in the built jar. */
public final class Opal {
	public static String lastError;

	public static void requestReload() {
		throw new AssertionError();
	}

	public static boolean active() {
		throw new AssertionError();
	}
}
