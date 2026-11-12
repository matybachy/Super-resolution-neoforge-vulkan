// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/pack/menu/MenuPage.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.pack.menu;

import java.util.List;

/**
 * One page, with the column count already resolved: what the pack asked for, or three past
 * eighteen slots and two below, which is Iris's rule and the one packs are laid out against.
 */
public record MenuPage(String name, List<MenuSlot> slots, int columns) {

	public MenuPage {
		slots = List.copyOf(slots);
	}
}
