// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/function/B2BFunction.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.function;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;

@FunctionalInterface
public interface B2BFunction extends TypedFunction {
	boolean eval(boolean a);

	@Override
	default void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
		params[0].evaluateTo(context, functionReturn);
		boolean a = functionReturn.booleanReturn;

		functionReturn.booleanReturn = this.eval(a);
	}

	@Override
	default Type getReturnType() {
		return Type.Boolean;
	}

	@Override
	default Parameter[] getParameters() {
		return new Parameter[]{Type.BooleanParameter};
	}
}
