// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/render/storage/package-info.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
/**
 * The storage a pack's programs write into rather than draw into: the images its {@code image.}
 * lines declare, the buffers its {@code bufferObject.} lines declare, and the encoder work that
 * creating or freeing either records outside a pass.
 * <p>
 * A leaf of the frame: nothing here reads the chain or a program. The colour targets allocate
 * and resize through it, the descriptor writes look a name up in it, and that is the whole of
 * the traffic.
 */
package dev.vitrail.render.storage;
