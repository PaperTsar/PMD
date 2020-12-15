/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.internal;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sourceforge.pmd.benchmark.TimeTracker;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.ast.NodeStream;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.InternalApiBridge;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.SymbolResolver;
import net.sourceforge.pmd.lang.java.symbols.internal.UnresolvedClassStore;
import net.sourceforge.pmd.lang.java.symbols.internal.ast.SymbolResolutionPass;
import net.sourceforge.pmd.lang.java.symbols.table.internal.ReferenceCtx;
import net.sourceforge.pmd.lang.java.symbols.table.internal.SymbolTableResolver;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import net.sourceforge.pmd.lang.java.types.internal.infer.TypeInferenceLogger;
import net.sourceforge.pmd.lang.java.types.internal.infer.TypeInferenceLogger.SimpleLogger;
import net.sourceforge.pmd.lang.java.types.internal.infer.TypeInferenceLogger.VerboseLogger;

/**
 * Processes the output of the parser before rules get access to the AST.
 * This performs all semantic analyses in layered passes.
 *
 * <p>This is the root context object for file-specific context. Instances
 * do not need to be thread-safe. Global information about eg the classpath
 * is held in a {@link TypeSystem} instance.
 */
public final class JavaAstProcessor {

    private static final Logger DEFAULT_LOG = Logger.getLogger(JavaAstProcessor.class.getName());

    private static final Map<ClassLoader, TypeSystem> TYPE_SYSTEMS = new IdentityHashMap<>();
    private static final Level INFERENCE_LOG_LEVEL;


    static {
        Level level;
        try {
            level = Level.parse(System.getenv("PMD_DEBUG_LEVEL"));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            level = Level.OFF;
        }
        INFERENCE_LOG_LEVEL = level;
    }


    private final TypeInferenceLogger typeInferenceLogger;
    private final SemanticErrorReporter logger;
    private final LanguageVersion languageVersion;
    private final TypeSystem typeSystem;

    private SymbolResolver symResolver;

    private final UnresolvedClassStore unresolvedTypes;


    private JavaAstProcessor(TypeSystem typeSystem,
                             SymbolResolver symResolver,
                             SemanticErrorReporter logger,
                             TypeInferenceLogger typeInfLogger,
                             LanguageVersion languageVersion) {

        this.symResolver = symResolver;
        this.logger = logger;
        this.typeInferenceLogger = typeInfLogger;
        this.languageVersion = languageVersion;

        this.typeSystem = typeSystem;
        unresolvedTypes = new UnresolvedClassStore(typeSystem);
    }

    public UnresolvedClassStore getUnresolvedStore() {
        return unresolvedTypes;
    }

    @SuppressWarnings("PMD.LiteralsFirstInComparisons") // see #3315
    static TypeInferenceLogger defaultTypeInfLogger() {
        if (Level.FINEST.equals(INFERENCE_LOG_LEVEL)) {
            return new VerboseLogger(System.err);
        } else if (Level.FINE.equals(INFERENCE_LOG_LEVEL)) {
            return new SimpleLogger(System.err);
        } else {
            return TypeInferenceLogger.noop();
        }
    }


    public JClassSymbol findSymbolCannotFail(String name) {
        JClassSymbol found = getSymResolver().resolveClassFromCanonicalName(name);
        return found == null ? makeUnresolvedReference(name, 0)
                             : found;
    }

    public JClassSymbol makeUnresolvedReference(String canonicalName, int typeArity) {
        return unresolvedTypes.makeUnresolvedReference(canonicalName, typeArity);
    }

    public JClassSymbol makeUnresolvedReference(JTypeDeclSymbol outer, String simpleName, int typeArity) {
        if (outer instanceof JClassSymbol) {
            return unresolvedTypes.makeUnresolvedReference((JClassSymbol) outer, simpleName, typeArity);
        }
        return makeUnresolvedReference("error." + simpleName, typeArity);
    }

    public SymbolResolver getSymResolver() {
        return symResolver;
    }

    public SemanticErrorReporter getLogger() {
        return logger;
    }

    public LanguageVersion getLanguageVersion() {
        return languageVersion;
    }

    public int getJdkVersion() {
        return ((JavaLanguageHandler) languageVersion.getLanguageVersionHandler()).getJdkVersion();
    }

    /**
     * Performs semantic analysis on the given source file.
     */
    public void process(ASTCompilationUnit acu) {

        SymbolResolver knownSyms = TimeTracker.bench("Symbol resolution", () -> SymbolResolutionPass.traverse(this, acu));

        // Now symbols are on the relevant nodes
        this.symResolver = SymbolResolver.layer(knownSyms, this.symResolver);

        // this needs to be initialized before the symbol table resolution
        // as scopes depend on type resolution in some cases.
        InternalApiBridge.initTypeResolver(acu, this, typeInferenceLogger);

        TimeTracker.bench("2. Symbol table resolution", () -> SymbolTableResolver.traverse(this, acu));
        TimeTracker.bench("3. AST disambiguation", () -> InternalApiBridge.disambigWithCtx(NodeStream.of(acu), ReferenceCtx.root(this, acu)));
        TimeTracker.bench("4. Comment assignment", () -> InternalApiBridge.assignComments(acu));
        bench("5. Usage resolution", () -> InternalApiBridge.usageResolution(this, acu));
        bench("6. Override resolution", () -> InternalApiBridge.overrideResolution(this, acu));
    }

    public TypeSystem getTypeSystem() {
        return typeSystem;
    }

    public static SemanticErrorReporter defaultLogger() {
        return SemanticErrorReporter.reportToLogger(DEFAULT_LOG);
    }

    public static JavaAstProcessor create(SymbolResolver symResolver,
                                          TypeSystem typeSystem,
                                          LanguageVersion languageVersion,
                                          SemanticErrorReporter logger) {

        return new JavaAstProcessor(
            typeSystem,
            symResolver,
            logger,
            defaultTypeInfLogger(),
            languageVersion
        );
    }

    public static JavaAstProcessor create(ClassLoader classLoader,
                                          LanguageVersion languageVersion,
                                          SemanticErrorReporter logger,
                                          TypeInferenceLogger typeInfLogger) {

        TypeSystem typeSystem = TYPE_SYSTEMS.computeIfAbsent(classLoader, TypeSystem::usingClassLoaderClasspath);
        return new JavaAstProcessor(
            typeSystem,
            typeSystem.bootstrapResolver(),
            logger,
            typeInfLogger,
            languageVersion
        );
    }


    public static JavaAstProcessor create(ClassLoader classLoader,
                                          LanguageVersion languageVersion,
                                          SemanticErrorReporter logger) {
        return create(classLoader, languageVersion, logger, defaultTypeInfLogger());
    }

    public static JavaAstProcessor create(TypeSystem typeSystem,
                                          LanguageVersion languageVersion,
                                          SemanticErrorReporter semanticLogger,
                                          TypeInferenceLogger typeInfLogger) {
        return new JavaAstProcessor(
            typeSystem,
            typeSystem.bootstrapResolver(),
            semanticLogger,
            typeInfLogger,
            languageVersion
        );
    }

}
