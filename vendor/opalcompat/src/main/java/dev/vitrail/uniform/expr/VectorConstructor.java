// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/VectorConstructor.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.AbstractTypedFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionContext;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionReturn;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.Type;
import dev.vitrail.uniform.expr.kroppeb.stareval.Util;

import java.util.Arrays;

public class VectorConstructor extends AbstractTypedFunction {

	public VectorConstructor(Type inner, int size) {
		super(
			new VectorType.ArrayVector(inner, size),
			Util.make(new Type[size], params -> Arrays.fill(params, inner))
		);
	}

	@Override
	public VectorType.ArrayVector getReturnType() {
		return (VectorType.ArrayVector) super.getReturnType();
	}

	@Override
	public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn
		functionReturn) {
		VectorType.ArrayVector vectorType = this.getReturnType();
		vectorType.map(params, context, functionReturn, (i, p, ctx, fr) -> p[i].evaluateTo(ctx, fr));
	}
}
