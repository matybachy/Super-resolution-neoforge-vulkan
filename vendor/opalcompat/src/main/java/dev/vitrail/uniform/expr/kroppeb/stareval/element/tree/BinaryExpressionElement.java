// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/tree/BinaryExpressionElement.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.tree;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.ExpressionElement;
import dev.vitrail.uniform.expr.kroppeb.stareval.parser.BinaryOp;

public record BinaryExpressionElement(BinaryOp op, ExpressionElement left,
									  ExpressionElement right) implements ExpressionElement {


	@Override
	public String toString() {
		return "BinaryExpr{ {" + this.left + "} " + this.op + " {" + this.right + "} }";
	}
}
