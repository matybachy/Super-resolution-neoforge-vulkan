// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/mixin/sodium/package-info.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
/**
 * The mixins aimed at Sodium, kept apart from those aimed at the game.
 * <p>
 * Every target in here is an internal of a mod that renames and reshapes between versions, so a
 * Sodium bump rechecks each of them against the jar the build really resolves. Listing them is a
 * directory listing rather than a search; the two accessors on Sodium types live with the other
 * accessors in {@code dev.vitrail.mixin.access}.
 */
package dev.vitrail.mixin.sodium;
