// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/function/F2IFunction.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.function;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;

@FunctionalInterface
public interface F2IFunction extends TypedFunction {
	int eval(float a);

	@Override
	default void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
		params[0].evaluateTo(context, functionReturn);
		functionReturn.intReturn = this.eval(functionReturn.floatReturn);
	}

	@Override
	default Type getReturnType() {
		return Type.Int;
	}

	@Override
	default Parameter[] getParameters() {
		return new Parameter[]{Type.FloatParameter};
	}
}
