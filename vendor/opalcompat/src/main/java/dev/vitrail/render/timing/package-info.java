// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/render/timing/package-info.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
/**
 * What the frame is measured and switched with, and nothing it is drawn with: the per-pass GPU
 * timings and their census, the ring timings of the chunk renderer, and the switch that puts the
 * game's own wide wait back on every pass.
 * <p>
 * A leaf of the frame: nothing here reads the chain, the targets or a program. The mixins that
 * open and close passes report into it, and the chain reads the census back once a frame.
 */
package dev.vitrail.render.timing;
