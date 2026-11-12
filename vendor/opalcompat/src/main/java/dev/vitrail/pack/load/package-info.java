// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/pack/load/package-info.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
/**
 * Loading a pack whole, for the report that describes it: the walk of every entry point, the
 * counts and the lines the log prints once per pack.
 * <p>
 * Apart from the reading packages because it is the one that uses all of them at once, and the
 * one that reaches the mod's own logger; the engine loads what it draws through the source
 * and the program packages directly, and this walk is a diagnosis beside that load.
 */
package dev.vitrail.pack.load;
