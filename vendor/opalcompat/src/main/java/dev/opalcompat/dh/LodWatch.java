package dev.opalcompat.dh;

import dev.opalcompat.OpalCompat;

import java.util.concurrent.TimeUnit;

/**
 * Says in the log when far terrain went missing for a frame, which is what a blink of it would be. Silent otherwise.
 * A frame counts when DH's opaque half never reached the draw, drew nothing while the frame before drew, or lost over
 * a quarter of its sections at once; a sudden turn of the camera can do the last, and that is why it is only counted.
 */
final class LodWatch {

	private static final long LOG_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);

	private static boolean drewThisFrame;
	private static int sectionsLastFrame;
	private static int frames;
	private static int skipped;
	private static int emptied;
	private static int dropped;
	private static int notUploaded;
	private static long loggedAt = System.nanoTime();

	private LodWatch() {
	}

	/** At the head of a level: closes the frame before. */
	static void frame() {
		if (sectionsLastFrame > 0 && !drewThisFrame) {
			skipped++;
			sectionsLastFrame = 0;
		}
		drewThisFrame = false;
		frames++;
		long now = System.nanoTime();
		if (now - loggedAt < LOG_EVERY_NANOS) return;
		if (skipped + emptied + dropped + notUploaded > 0) {
			OpalCompat.LOGGER.info("[dh] of {} frames: {} never drew far terrain, {} drew none, {} lost over a quarter of it, {} listed sections not uploaded",
					frames, skipped, emptied, dropped, notUploaded);
		}
		frames = skipped = emptied = dropped = notUploaded = 0;
		loggedAt = now;
	}

	static void drew(int kept, int listedNotUploaded, boolean drawn) {
		drewThisFrame = true;
		if (!drawn) kept = 0;
		notUploaded += listedNotUploaded;
		if (kept == 0 && sectionsLastFrame > 0) {
			emptied++;
		} else if (kept < sectionsLastFrame * 3 / 4) {
			dropped++;
		}
		sectionsLastFrame = kept;
	}
}
