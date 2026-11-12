// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/tree/partial/PartialBinaryExpression.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.tree.partial;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.ExpressionElement;
import dev.vitrail.uniform.expr.kroppeb.stareval.element.PriorityOperatorElement;
import dev.vitrail.uniform.expr.kroppeb.stareval.element.tree.BinaryExpressionElement;
import dev.vitrail.uniform.expr.kroppeb.stareval.parser.BinaryOp;

public class PartialBinaryExpression extends PartialExpression implements PriorityOperatorElement {
	private final ExpressionElement left;
	private final BinaryOp op;

	public PartialBinaryExpression(ExpressionElement left, BinaryOp op) {
		this.left = left;
		this.op = op;
	}

	@Override
	public String toString() {
		return "PartialBinaryExpression{ {" + this.left + "} " + this.op + "}";
	}

	@Override
	public int getPriority() {
		return this.op.priority();
	}

	@Override
	public BinaryExpressionElement resolveWith(ExpressionElement right) {
		return new BinaryExpressionElement(this.op, this.left, right);
	}
}
