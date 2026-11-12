// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit 7a3cdcb,
// common/src/main/java/dev/vitrail/uniform/expr/kroppeb/stareval/element/token/IdToken.java. LGPL-3.0, see NOTICE and NOTICE-vitrail.
// Unchanged unless marked with a "OpalCompat:" comment.
package dev.vitrail.uniform.expr.kroppeb.stareval.element.token;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.AccessibleExpressionElement;

public class IdToken extends Token implements AccessibleExpressionElement {
	private final String id;

	public IdToken(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	@Override
	public String toString() {
		return "Id{" + this.id + "}";
	}
}
