package dev.opalcompat.texture;

/** On {@code SpriteContents}: whether anything has drawn with this sprite since its atlas was stitched. */
public interface UsedSprite {

	boolean opalcompat$used();

	void opalcompat$use();
}
