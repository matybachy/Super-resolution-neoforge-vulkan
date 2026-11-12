// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/exception/UnexpectedEndingException.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.exception;

public class UnexpectedEndingException extends ParseException {
	public UnexpectedEndingException() {
		this("Expected to read more text, but the string has ended");
	}

	public UnexpectedEndingException(String message) {
		super(message);
	}
}
