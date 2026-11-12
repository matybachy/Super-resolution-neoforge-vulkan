// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/token/BinaryOperatorToken.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.token;

import dev.vitrail.uniform.expr.kroppeb.stareval.parser.BinaryOp;

public class BinaryOperatorToken extends Token {
	public final BinaryOp op;

	public BinaryOperatorToken(BinaryOp op) {
		this.op = op;
	}

	@Override
	public String toString() {
		return "BinaryOp{" + this.op + "}";
	}
}
