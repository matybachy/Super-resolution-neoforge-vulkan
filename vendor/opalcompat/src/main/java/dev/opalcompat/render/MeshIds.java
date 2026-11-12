package dev.opalcompat.render;

/** Per-vertex identifiers captured while a shared game buffer is being built. */
public interface MeshIds {
	void opalcompat$setIds(DrawIds[] ids);
	DrawIds[] opalcompat$ids();
}
