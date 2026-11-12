package dev.opalcompat.terrain;

/** On VulkanMod Reforged's {@code BuildTask}: whether it was scheduled for a section near the camera or changed by the player. */
public interface UrgentTask {

	boolean opalcompat$urgent();

	/** {@link System#nanoTime()} when the task was made, on the render thread. */
	long opalcompat$created();
}
