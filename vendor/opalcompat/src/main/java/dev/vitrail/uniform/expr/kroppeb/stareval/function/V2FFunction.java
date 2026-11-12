// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/function/V2FFunction.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.function;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;

@FunctionalInterface
public interface V2FFunction extends TypedFunction {
	float eval();

	@Override
	default void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
		functionReturn.floatReturn = this.eval();
	}

	@Override
	default Type getReturnType() {
		return Type.Float;
	}

	@Override
	default Parameter[] getParameters() {
		return new Parameter[]{};
	}
}
