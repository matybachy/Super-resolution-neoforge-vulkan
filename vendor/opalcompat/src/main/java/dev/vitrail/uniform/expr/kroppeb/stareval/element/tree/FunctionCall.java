// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/tree/FunctionCall.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.tree;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.ExpressionElement;

import java.util.List;

public record FunctionCall(String id, List<? extends ExpressionElement> args) implements ExpressionElement {


	@Override
	public String toString() {
		return "FunctionCall{" + this.id + " {" + this.args + "} }";
	}
}
