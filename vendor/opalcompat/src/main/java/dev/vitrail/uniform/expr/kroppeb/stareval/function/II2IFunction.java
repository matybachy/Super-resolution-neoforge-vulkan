// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/function/II2IFunction.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.function;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;

@FunctionalInterface
public interface II2IFunction extends TypedFunction {
	int eval(int a, int b);

	@Override
	default void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
		params[0].evaluateTo(context, functionReturn);
		int a = functionReturn.intReturn;

		params[1].evaluateTo(context, functionReturn);
		int b = functionReturn.intReturn;

		functionReturn.intReturn = this.eval(a, b);
	}

	@Override
	default Type getReturnType() {
		return Type.Int;
	}

	@Override
	default Parameter[] getParameters() {
		return new Parameter[]{Type.IntParameter, Type.IntParameter};
	}
}
