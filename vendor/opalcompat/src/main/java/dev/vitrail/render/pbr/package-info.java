// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/render/pbr/package-info.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
/**
 * The material maps a pack reads beside the block atlas: which names they answer to, the atlases
 * a resource pack ships them in, and the flat textures that stand in where it ships none.
 * <p>
 * A leaf of the frame: nothing here reads the chain, the targets or a program. The frame asks it
 * which view serves a material name, and that is the whole of the traffic.
 */
package dev.vitrail.render.pbr;
