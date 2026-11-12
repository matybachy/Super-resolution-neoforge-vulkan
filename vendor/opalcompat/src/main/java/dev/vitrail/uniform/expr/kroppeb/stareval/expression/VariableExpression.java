// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/expression/VariableExpression.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.expression;

import java.util.Collection;

public interface VariableExpression extends Expression {
	@Override
	default void listVariables(Collection<? super VariableExpression> variables) {
		variables.add(this);
	}
}
