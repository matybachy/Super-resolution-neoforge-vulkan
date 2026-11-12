// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/tree/partial/PartialExpression.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.tree.partial;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.Element;

public abstract class PartialExpression implements Element {
	@Override
	public abstract String toString();
}
