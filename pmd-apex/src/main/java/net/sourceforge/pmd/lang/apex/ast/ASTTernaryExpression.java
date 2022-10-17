/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.apex.ast;

import com.google.summit.ast.expression.TernaryExpression;

public class ASTTernaryExpression extends AbstractApexNode.Single<TernaryExpression> {

    ASTTernaryExpression(TernaryExpression ternaryExpression) {
        super(ternaryExpression);
    }

    @Override
    public Object jjtAccept(ApexParserVisitor visitor, Object data) {
        return visitor.visit(this, data);
    }
}
