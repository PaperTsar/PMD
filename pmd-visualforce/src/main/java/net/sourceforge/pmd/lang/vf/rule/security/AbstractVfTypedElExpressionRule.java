/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.vf.rule.security;

import java.io.File;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.lang.vf.ExpressionType;
import net.sourceforge.pmd.lang.vf.VfExpressionTypeVisitor;
import net.sourceforge.pmd.lang.vf.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.vf.ast.ASTIdentifier;
import net.sourceforge.pmd.lang.vf.rule.AbstractVfRule;
import net.sourceforge.pmd.properties.PropertyDescriptor;
import net.sourceforge.pmd.properties.PropertyFactory;

/**
 * Represents a rule where the {@link net.sourceforge.pmd.lang.vf.ast.ASTIdentifier} nodes are enhanced with the
 * node's {@link ExpressionType}. This is achieved by processing metadata files referenced by the Visualforce page.
 */
class AbstractVfTypedElExpressionRule extends AbstractVfRule {
    /**
     * Directory that contains Apex classes that may be referenced from a Visualforce page.
     */
    private static final PropertyDescriptor<List<String>> APEX_DIRECTORIES_DESCRIPTOR =
            PropertyFactory.stringListProperty("apexDirectories")
                    .desc("Location of Apex Class directories. Absolute or relative to the Visualforce directory")
                    .defaultValue(Collections.singletonList(".." + File.separator + "classes"))
                    .delim(',')
                    .build();

    /**
     * Directory that contains Object definitions that may be referenced from a Visualforce page.
     */
    private static final PropertyDescriptor<List<String>> OBJECTS_DIRECTORIES_DESCRIPTOR =
            PropertyFactory.stringListProperty("objectsDirectories")
                    .desc("Location of CustomObject directories. Absolute or relative to the Visualforce directory")
                    .defaultValue(Collections.singletonList(".." + File.separator + "objects"))
                    .delim(',')
                    .build();

    private Map<ASTIdentifier, ExpressionType> expressionTypes;

    AbstractVfTypedElExpressionRule() {
        definePropertyDescriptor(APEX_DIRECTORIES_DESCRIPTOR);
        definePropertyDescriptor(OBJECTS_DIRECTORIES_DESCRIPTOR);
    }

    public ExpressionType getExpressionType(ASTIdentifier node) {
        return expressionTypes.get(node);
    }

    @Override
    public void start(RuleContext ctx) {
        this.expressionTypes = Collections.synchronizedMap(new IdentityHashMap<ASTIdentifier, ExpressionType>());
        super.start(ctx);
    }

    /**
     * Invoke {@link VfExpressionTypeVisitor#visit(ASTCompilationUnit, Object)} to identify Visualforce expression's and
     * their types.
     */
    @Override
    public Object visit(ASTCompilationUnit node, Object data) {
        List<String> apexDirectories = getProperty(APEX_DIRECTORIES_DESCRIPTOR);
        List<String> objectsDirectories = getProperty(OBJECTS_DIRECTORIES_DESCRIPTOR);

        // The visitor will only find information if there are directories to look in. This allows users to disable the
        // visitor in the unlikely scenario that they want to.
        if (!apexDirectories.isEmpty() || !objectsDirectories.isEmpty()) {
            RuleContext ctx = (RuleContext) data;
            File file = ctx.getSourceCodeFile();
            if (file != null) {
                VfExpressionTypeVisitor visitor = new VfExpressionTypeVisitor(file.getAbsolutePath(),
                        apexDirectories,
                        objectsDirectories);
                visitor.visit(node, data);
                this.expressionTypes.putAll(visitor.getExpressionTypes());
            }
        }

        return super.visit(node, data);
    }
}
