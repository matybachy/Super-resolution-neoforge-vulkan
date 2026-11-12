// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/expression/ConstantExpression.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.expression;

import dev.vitrail.uniform.expr.kroppeb.stareval.function.Type;

import java.util.Collection;

public abstract class ConstantExpression implements Expression {
	private final Type type;

	protected ConstantExpression(Type type) {
		this.type = type;
	}

	public Type getType() {
		return this.type;
	}

	@Override
	public void listVariables(Collection<? super VariableExpression> variables) {
	}
}
