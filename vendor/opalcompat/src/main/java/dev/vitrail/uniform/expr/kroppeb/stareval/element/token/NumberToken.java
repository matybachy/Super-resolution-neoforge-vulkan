// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/token/NumberToken.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.token;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.ExpressionElement;

public class NumberToken extends Token implements ExpressionElement {
	private final String number;

	public NumberToken(String number) {
		this.number = number;
	}

	public String getNumber() {
		return number;
	}

	@Override
	public String toString() {
		return "Number{" + this.number + "}";
	}
}
