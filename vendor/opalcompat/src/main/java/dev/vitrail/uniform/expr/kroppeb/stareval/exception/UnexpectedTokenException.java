// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/exception/UnexpectedTokenException.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.exception;

public class UnexpectedTokenException extends ParseException {
	public UnexpectedTokenException(String message, int index) {
		super(message + " at index " + index);
	}
}
