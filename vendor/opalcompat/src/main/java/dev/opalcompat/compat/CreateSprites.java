package dev.opalcompat.compat;

/**
 * The one lock taken around the sprite registries of Ponder ({@code StitchedSprite.ALL}, {@code SpriteShifter.ENTRY_CACHE}) and
 * Create ({@code CTSpriteShifter.ENTRY_CACHE}). All three are plain {@code HashMap}s filled from mod constructors and static
 * initialisers, and NeoForge constructs mods on several threads at once: in the log of 2026-11-11 17:23 Create Deco's
 * {@code SpriteShifts.<clinit>} threw a {@code ConcurrentModificationException} out of {@code HashMap.computeIfAbsent} in
 * {@code StitchedSprite.<init>}, and the game stopped at "Failed to create mod instance. ModID: createdeco". One lock for all
 * three, because a call into one registry goes on into the next.
 */
public final class CreateSprites {

	public static final Object LOCK = new Object();

	private CreateSprites() {
	}
}
