// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/exception/UnexpectedCharacterException.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.exception;

public class UnexpectedCharacterException extends ParseException {
	public UnexpectedCharacterException(char expected, char actual, int index) {
		this("Expected to read '" + expected + "' but got '" + actual + "' at index " + index);
	}

	public UnexpectedCharacterException(char actual, int index) {
		this("Read an unexpected character '" + actual + "' at index " + index);
	}

	public UnexpectedCharacterException(String expected, char actual, int index) {
		this("Expected to read " + expected + " but got '" + actual + "' at index " + index);
	}

	private UnexpectedCharacterException(String message) {
		super(message);
	}
}
