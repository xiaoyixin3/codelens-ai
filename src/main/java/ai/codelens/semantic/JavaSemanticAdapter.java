package ai.codelens.semantic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static ai.codelens.semantic.SemanticModels.CoverageLevel.FAILED;
import static ai.codelens.semantic.SemanticModels.CoverageLevel.SEMANTIC;
import static ai.codelens.semantic.SemanticModels.CoverageLevel.SEMANTIC_PARTIAL;
import static ai.codelens.semantic.SemanticModels.RelationType.ANNOTATED_WITH;
import static ai.codelens.semantic.SemanticModels.RelationType.CALLS;
import static ai.codelens.semantic.SemanticModels.RelationType.CATCHES;
import static ai.codelens.semantic.SemanticModels.RelationType.EXTENDS;
import static ai.codelens.semantic.SemanticModels.RelationType.IMPLEMENTS;
import static ai.codelens.semantic.SemanticModels.RelationType.OVERRIDES;
import static ai.codelens.semantic.SemanticModels.RelationType.READS;
import static ai.codelens.semantic.SemanticModels.RelationType.TESTS;
import static ai.codelens.semantic.SemanticModels.RelationType.THROWS;
import static ai.codelens.semantic.SemanticModels.RelationType.WRITES;

public final class JavaSemanticAdapter implements SemanticAdapter {
    public static final String ADAPTER_VERSION = "javaparser-3.28.2-v1";
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "target", "build", "node_modules", "dist", "out"
    );

    private final int maxFiles;
    private final long maxFileBytes;

    public JavaSemanticAdapter() {
        this(50_000, 2L * 1024 * 1024);
    }

    public JavaSemanticAdapter(int maxFiles, long maxFileBytes) {
        if (maxFiles <= 0 || maxFileBytes <= 0) throw new IllegalArgumentException("Index limits must be positive");
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
    }

    @Override public String language() { return "java"; }
    @Override public String version() { return ADAPTER_VERSION; }

    @Override
    public SemanticModels.Index index(Path repositoryRoot, String commitSha, BuildModel buildModel) {
        return indexSelected(repositoryRoot, commitSha, buildModel, null);
    }

    public SemanticModels.Index indexIncremental(
            Path repositoryRoot,
            String commitSha,
            BuildModel buildModel,
            SemanticModels.Index base,
            Set<String> changedPaths
    ) {
        if (!version().equals(base.adapterVersion()) || !buildModel.hash().equals(base.buildModelHash())) {
            return index(repositoryRoot, commitSha, buildModel);
        }
        Set<String> affected = new LinkedHashSet<>();
        changedPaths.stream().map(JavaSemanticAdapter::normalizePath).forEach(affected::add);
        Map<String, SemanticModels.Symbol> baseSymbols = base.symbols().stream()
                .collect(java.util.stream.Collectors.toMap(SemanticModels.Symbol::stableKey, symbol -> symbol, (left, right) -> left));
        Set<String> changedSymbolKeys = base.symbols().stream()
                .filter(symbol -> affected.contains(symbol.path()))
                .map(SemanticModels.Symbol::stableKey)
                .collect(java.util.stream.Collectors.toSet());
        for (SemanticModels.Relationship relationship : base.relationships()) {
            if (!changedSymbolKeys.contains(relationship.toStableKey())) continue;
            SemanticModels.Symbol source = baseSymbols.get(relationship.fromStableKey());
            if (source != null) affected.add(source.path());
        }

        SemanticModels.Index changed = indexSelected(repositoryRoot, commitSha, buildModel, affected);
        Set<String> newlyResolvableTargets = unresolvedTargetCandidates(changed.symbols());
        boolean expanded = false;
        for (SemanticModels.Relationship relationship : base.relationships()) {
            if (!newlyResolvableTargets.contains(relationship.toStableKey())) continue;
            SemanticModels.Symbol source = baseSymbols.get(relationship.fromStableKey());
            if (source != null) expanded |= affected.add(source.path());
        }
        if (expanded) changed = indexSelected(repositoryRoot, commitSha, buildModel, affected);
        List<SemanticModels.FileStatus> files = new ArrayList<>();
        base.files().stream().filter(file -> !affected.contains(file.path())).forEach(files::add);
        files.addAll(changed.files());
        files.sort(Comparator.comparing(SemanticModels.FileStatus::path));

        List<SemanticModels.Symbol> symbols = new ArrayList<>();
        base.symbols().stream().filter(symbol -> !affected.contains(symbol.path())).forEach(symbols::add);
        symbols.addAll(changed.symbols());
        symbols.sort(Comparator.comparing(SemanticModels.Symbol::stableKey));
        Set<String> currentKeys = symbols.stream().map(SemanticModels.Symbol::stableKey).collect(java.util.stream.Collectors.toSet());

        Map<String, SemanticModels.Relationship> relationships = new LinkedHashMap<>();
        base.relationships().stream()
                .filter(relationship -> !affected.contains(relationship.sourcePath()))
                .filter(relationship -> !changedSymbolKeys.contains(relationship.toStableKey()) || currentKeys.contains(relationship.toStableKey()))
                .forEach(relationship -> add(relationships, relationship));
        changed.relationships().forEach(relationship -> add(relationships, relationship));
        addTestRelationships(relationships, symbols.stream().collect(java.util.stream.Collectors.toMap(
                SemanticModels.Symbol::stableKey, symbol -> symbol, (left, right) -> left)));

        int indexed = (int) files.stream().filter(file -> file.status().equals("indexed")).count();
        int failed = (int) files.stream().filter(file -> file.status().equals("failed")).count();
        int skipped = (int) files.stream().filter(file -> file.status().equals("skipped")).count();
        int reused = Math.max(0, files.size() - changed.files().size());
        int resolved = (int) relationships.values().stream().filter(SemanticModels.Relationship::typeResolved).count();
        int unresolved = relationships.size() - resolved;
        Map<String, Long> degradations = new LinkedHashMap<>(changed.coverage().degradationReasons());
        if (reused > 0) degradations.put("incremental_reused_file", (long) reused);
        if (unresolved > 0) degradations.put("unresolved_relationship", (long) unresolved);
        SemanticModels.CoverageLevel level = indexed == 0 ? FAILED
                : failed > 0 || skipped > 0 ? SEMANTIC_PARTIAL : SEMANTIC;
        SemanticModels.Coverage coverage = new SemanticModels.Coverage(
                level, files.size(), indexed, failed, skipped, reused, resolved, unresolved, degradations
        );
        List<SemanticModels.Relationship> sortedRelationships = relationships.values().stream()
                .sorted(Comparator.comparing(SemanticModels.Relationship::fromStableKey)
                        .thenComparing(relationship -> relationship.type().name())
                        .thenComparing(SemanticModels.Relationship::toStableKey)
                        .thenComparingInt(SemanticModels.Relationship::sourceLine))
                .toList();
        return new SemanticModels.Index(commitSha, version(), buildModel.hash(), files, symbols, sortedRelationships, coverage);
    }

    private SemanticModels.Index indexSelected(Path repositoryRoot, String commitSha, BuildModel buildModel, Set<String> selectedPaths) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Repository root does not exist: " + root);

        List<Path> sourceRoots = sourceRoots(root, buildModel);
        List<Path> testRoots = testRoots(root, buildModel);
        List<Path> allFiles = javaFiles(root, sourceRoots);
        List<Path> files = selectedPaths == null ? allFiles : allFiles.stream()
                .filter(file -> selectedPaths.contains(relative(root, file)))
                .toList();
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        sourceRoots.forEach(path -> typeSolver.add(new JavaParserTypeSolver(path)));
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(configuration);

        List<SemanticModels.FileStatus> statuses = new ArrayList<>();
        List<Unit> units = new ArrayList<>();
        Map<String, Long> degradations = new LinkedHashMap<>();
        int eligible = files.size();
        int indexed = 0;
        int failed = 0;
        int skipped = 0;

        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            String path = relative(root, file);
            if (index >= maxFiles) {
                statuses.add(new SemanticModels.FileStatus(path, "skipped", "repository_file_limit", ""));
                degradations.merge("repository_file_limit", 1L, Long::sum);
                skipped++;
                continue;
            }
            try {
                long size = Files.size(file);
                if (size > maxFileBytes) {
                    statuses.add(new SemanticModels.FileStatus(path, "skipped", "file_too_large", ""));
                    degradations.merge("file_too_large", 1L, Long::sum);
                    skipped++;
                    continue;
                }
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (result.getResult().isEmpty() || !result.isSuccessful()) {
                    statuses.add(new SemanticModels.FileStatus(path, "failed", "parse_error", digest(file)));
                    degradations.merge("parse_error", 1L, Long::sum);
                    failed++;
                    continue;
                }
                boolean testSource = isUnderAny(file, testRoots);
                units.add(new Unit(path, result.getResult().orElseThrow(), testSource));
                statuses.add(new SemanticModels.FileStatus(path, "indexed", "", digest(file)));
                indexed++;
            } catch (IOException | RuntimeException exception) {
                statuses.add(new SemanticModels.FileStatus(path, "failed", "content_or_parser_unavailable", ""));
                degradations.merge("content_or_parser_unavailable", 1L, Long::sum);
                failed++;
            }
        }

        List<SemanticModels.Symbol> symbols = new ArrayList<>();
        Map<Node, String> symbolByNode = new IdentityHashMap<>();
        Map<String, SemanticModels.Symbol> symbolByKey = new LinkedHashMap<>();
        for (Unit unit : units) extractSymbols(unit, symbols, symbolByNode, symbolByKey, degradations);

        Map<String, SemanticModels.Relationship> relationships = new LinkedHashMap<>();
        for (Unit unit : units) {
            extractTypeRelationships(unit, symbolByNode, relationships);
            extractOverrides(unit, symbolByNode, relationships, degradations);
            extractExceptionRelationships(unit, symbolByNode, relationships, degradations);
            extractAnnotations(unit, symbolByNode, relationships);
            extractCalls(unit, symbolByNode, relationships, degradations);
            extractFieldAccess(unit, symbolByNode, relationships, degradations);
        }
        addTestRelationships(relationships, symbolByKey);

        long resolved = relationships.values().stream().filter(SemanticModels.Relationship::typeResolved).count();
        long unresolved = relationships.size() - resolved;
        if (unresolved > 0) degradations.put("unresolved_relationship", unresolved);
        SemanticModels.CoverageLevel level = indexed == 0 ? FAILED
                : failed > 0 || skipped > 0 ? SEMANTIC_PARTIAL : SEMANTIC;
        SemanticModels.Coverage coverage = new SemanticModels.Coverage(
                level, eligible, indexed, failed, skipped, 0, Math.toIntExact(resolved), Math.toIntExact(unresolved), degradations
        );
        statuses.sort(Comparator.comparing(SemanticModels.FileStatus::path));
        symbols.sort(Comparator.comparing(SemanticModels.Symbol::stableKey));
        List<SemanticModels.Relationship> sortedRelationships = relationships.values().stream()
                .sorted(Comparator.comparing(SemanticModels.Relationship::fromStableKey)
                        .thenComparing(relationship -> relationship.type().name())
                        .thenComparing(SemanticModels.Relationship::toStableKey)
                        .thenComparingInt(SemanticModels.Relationship::sourceLine))
                .toList();
        return new SemanticModels.Index(commitSha, version(), buildModel.hash(), statuses, symbols, sortedRelationships, coverage);
    }

    private static void extractSymbols(
            Unit unit,
            List<SemanticModels.Symbol> symbols,
            Map<Node, String> symbolByNode,
            Map<String, SemanticModels.Symbol> symbolByKey,
            Map<String, Long> degradations
    ) {
        for (TypeDeclaration<?> type : unit.compilationUnit().findAll(TypeDeclaration.class)) {
            ResolvedName resolved = resolve(() -> type.resolve().getQualifiedName(), () -> astTypeName(type));
            putSymbol(new SemanticModels.Symbol("java:type:" + resolved.name(), SemanticModels.SymbolKind.TYPE,
                    resolved.name(), type.getNameAsString(), unit.path(), begin(type), end(type),
                    unit.testSource(), resolved.resolved()), type, symbols, symbolByNode, symbolByKey);
        }
        for (MethodDeclaration method : unit.compilationUnit().findAll(MethodDeclaration.class)) {
            ResolvedMethod resolved = resolveMethod(method);
            putSymbol(new SemanticModels.Symbol(resolved.key(), SemanticModels.SymbolKind.METHOD,
                    resolved.owner() + "." + method.getNameAsString(), method.getDeclarationAsString(false, false, false),
                    unit.path(), begin(method), end(method), unit.testSource(), resolved.resolved()),
                    method, symbols, symbolByNode, symbolByKey);
            if (!resolved.resolved()) degradations.merge("unresolved_declaration", 1L, Long::sum);
        }
        for (ConstructorDeclaration constructor : unit.compilationUnit().findAll(ConstructorDeclaration.class)) {
            ResolvedConstructor resolved = resolveConstructor(constructor);
            putSymbol(new SemanticModels.Symbol(resolved.key(), SemanticModels.SymbolKind.CONSTRUCTOR,
                    resolved.owner() + ".<init>", constructor.getDeclarationAsString(false, false, false),
                    unit.path(), begin(constructor), end(constructor), unit.testSource(), resolved.resolved()),
                    constructor, symbols, symbolByNode, symbolByKey);
            if (!resolved.resolved()) degradations.merge("unresolved_declaration", 1L, Long::sum);
        }
        for (FieldDeclaration field : unit.compilationUnit().findAll(FieldDeclaration.class)) {
            String owner = field.findAncestor(TypeDeclaration.class).map(JavaSemanticAdapter::astTypeName).orElse("<unknown>");
            for (var variable : field.getVariables()) {
                ResolvedName resolved = resolve(() -> {
                    ResolvedValueDeclaration value = variable.resolve();
                    if (!value.isField()) throw new IllegalStateException("Not a field");
                    ResolvedFieldDeclaration declaration = value.asField();
                    return declaration.declaringType().getQualifiedName() + "#" + declaration.getName();
                }, () -> owner + "#" + variable.getNameAsString());
                String key = "java:field:" + resolved.name();
                putSymbol(new SemanticModels.Symbol(key, SemanticModels.SymbolKind.FIELD, resolved.name(),
                                variable.getTypeAsString() + " " + variable.getNameAsString(), unit.path(), begin(variable), end(variable),
                                unit.testSource(), resolved.resolved()),
                        variable, symbols, symbolByNode, symbolByKey);
            }
        }
    }

    private static void extractTypeRelationships(Unit unit, Map<Node, String> symbolByNode,
                                                  Map<String, SemanticModels.Relationship> relationships) {
        for (ClassOrInterfaceDeclaration type : unit.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
            String from = symbolByNode.get(type);
            for (ClassOrInterfaceType target : type.getExtendedTypes()) {
                addTypeRelationship(from, target, EXTENDS, unit.path(), relationships);
            }
            for (ClassOrInterfaceType target : type.getImplementedTypes()) {
                addTypeRelationship(from, target, IMPLEMENTS, unit.path(), relationships);
            }
        }
        for (EnumDeclaration type : unit.compilationUnit().findAll(EnumDeclaration.class)) {
            String from = symbolByNode.get(type);
            for (ClassOrInterfaceType target : type.getImplementedTypes()) {
                addTypeRelationship(from, target, IMPLEMENTS, unit.path(), relationships);
            }
        }
        for (RecordDeclaration type : unit.compilationUnit().findAll(RecordDeclaration.class)) {
            String from = symbolByNode.get(type);
            for (ClassOrInterfaceType target : type.getImplementedTypes()) {
                addTypeRelationship(from, target, IMPLEMENTS, unit.path(), relationships);
            }
        }
    }

    private static void addTypeRelationship(String from, ClassOrInterfaceType target, SemanticModels.RelationType relation,
                                            String path, Map<String, SemanticModels.Relationship> relationships) {
        if (from == null) return;
        ResolvedName resolved = resolve(() -> target.resolve().asReferenceType().getQualifiedName(), target::getNameWithScope);
        add(relationships, new SemanticModels.Relationship(from, "java:type:" + resolved.name(), relation,
                path, begin(target), resolved.resolved() ? 1.0 : 0.35, resolved.resolved()));
    }

    private static void extractAnnotations(Unit unit, Map<Node, String> symbolByNode,
                                           Map<String, SemanticModels.Relationship> relationships) {
        symbolByNode.forEach((node, from) -> {
            if (!(node instanceof NodeWithAnnotations<?> annotated)) return;
            for (AnnotationExpr annotation : annotated.getAnnotations()) {
                ResolvedName resolved = resolve(() -> annotation.resolve().getQualifiedName(), annotation::getNameAsString);
                add(relationships, new SemanticModels.Relationship(from, "java:annotation:" + resolved.name(), ANNOTATED_WITH,
                        unit.path(), begin(annotation), resolved.resolved() ? 1.0 : 0.35, resolved.resolved()));
            }
        });
        for (FieldDeclaration field : unit.compilationUnit().findAll(FieldDeclaration.class)) {
            for (var variable : field.getVariables()) {
                String from = symbolByNode.get(variable);
                if (from == null) continue;
                for (AnnotationExpr annotation : field.getAnnotations()) {
                    ResolvedName resolved = resolve(() -> annotation.resolve().getQualifiedName(), annotation::getNameAsString);
                    add(relationships, new SemanticModels.Relationship(from, "java:annotation:" + resolved.name(), ANNOTATED_WITH,
                            unit.path(), begin(annotation), resolved.resolved() ? 1.0 : 0.35, resolved.resolved()));
                }
            }
        }
    }

    private static void extractOverrides(Unit unit, Map<Node, String> symbolByNode,
                                         Map<String, SemanticModels.Relationship> relationships,
                                         Map<String, Long> degradations) {
        for (MethodDeclaration method : unit.compilationUnit().findAll(MethodDeclaration.class)) {
            String from = symbolByNode.get(method);
            if (from == null) continue;
            try {
                ResolvedMethodDeclaration resolved = method.resolve();
                for (var ancestor : resolved.declaringType().getAllAncestors()) {
                    var declaration = ancestor.getTypeDeclaration();
                    if (declaration.isEmpty()) continue;
                    for (ResolvedMethodDeclaration candidate : declaration.get().getDeclaredMethods()) {
                        if (!sameSignature(resolved, candidate)) continue;
                        add(relationships, new SemanticModels.Relationship(from, methodKey(candidate), OVERRIDES,
                                unit.path(), begin(method), 1.0, true));
                    }
                }
            } catch (RuntimeException exception) {
                if (method.getAnnotationByName("Override").isPresent()) {
                    degradations.merge("unresolved_override", 1L, Long::sum);
                }
            }
        }
    }

    private static boolean sameSignature(ResolvedMethodDeclaration method, ResolvedMethodDeclaration candidate) {
        if (!method.getName().equals(candidate.getName()) || method.getNumberOfParams() != candidate.getNumberOfParams()) return false;
        for (int index = 0; index < method.getNumberOfParams(); index++) {
            if (!method.getParam(index).getType().describe().equals(candidate.getParam(index).getType().describe())) return false;
        }
        return true;
    }

    private static void extractExceptionRelationships(Unit unit, Map<Node, String> symbolByNode,
                                                      Map<String, SemanticModels.Relationship> relationships,
                                                      Map<String, Long> degradations) {
        for (MethodDeclaration method : unit.compilationUnit().findAll(MethodDeclaration.class)) {
            String from = symbolByNode.get(method);
            if (from == null) continue;
            method.getThrownExceptions().forEach(type -> addExceptionType(from, type, THROWS, unit.path(), relationships, degradations));
        }
        for (ConstructorDeclaration constructor : unit.compilationUnit().findAll(ConstructorDeclaration.class)) {
            String from = symbolByNode.get(constructor);
            if (from == null) continue;
            constructor.getThrownExceptions().forEach(type -> addExceptionType(from, type, THROWS, unit.path(), relationships, degradations));
        }
        for (ThrowStmt statement : unit.compilationUnit().findAll(ThrowStmt.class)) {
            String from = enclosingCallable(statement, symbolByNode);
            if (from == null) continue;
            ResolvedName resolved = resolve(() -> statement.getExpression().calculateResolvedType().describe(), () -> "<unresolved>");
            add(relationships, new SemanticModels.Relationship(from, "java:type:" + resolved.name(), THROWS,
                    unit.path(), begin(statement), resolved.resolved() ? 1.0 : 0.0, resolved.resolved()));
            if (!resolved.resolved()) degradations.merge("unresolved_exception", 1L, Long::sum);
        }
        for (CatchClause clause : unit.compilationUnit().findAll(CatchClause.class)) {
            String from = enclosingCallable(clause, symbolByNode);
            if (from == null) continue;
            ResolvedName resolved = resolve(() -> clause.getParameter().getType().resolve().describe(),
                    () -> clause.getParameter().getTypeAsString());
            add(relationships, new SemanticModels.Relationship(from, "java:type:" + resolved.name(), CATCHES,
                    unit.path(), begin(clause), resolved.resolved() ? 1.0 : 0.35, resolved.resolved()));
            if (!resolved.resolved()) degradations.merge("unresolved_exception", 1L, Long::sum);
        }
    }

    private static void addExceptionType(String from, com.github.javaparser.ast.type.ReferenceType type,
                                         SemanticModels.RelationType relation, String path,
                                         Map<String, SemanticModels.Relationship> relationships,
                                         Map<String, Long> degradations) {
        ResolvedName resolved = resolve(() -> type.resolve().describe(), type::asString);
        add(relationships, new SemanticModels.Relationship(from, "java:type:" + resolved.name(), relation,
                path, begin(type), resolved.resolved() ? 1.0 : 0.35, resolved.resolved()));
        if (!resolved.resolved()) degradations.merge("unresolved_exception", 1L, Long::sum);
    }

    private static void extractCalls(Unit unit, Map<Node, String> symbolByNode,
                                     Map<String, SemanticModels.Relationship> relationships,
                                     Map<String, Long> degradations) {
        for (MethodCallExpr call : unit.compilationUnit().findAll(MethodCallExpr.class)) {
            String from = enclosingCallable(call, symbolByNode);
            if (from == null) continue;
            ResolvedName target = resolve(() -> methodKey(call.resolve()),
                    () -> "unresolved:method:" + call.getNameAsString() + "/" + call.getArguments().size());
            add(relationships, new SemanticModels.Relationship(from, target.name(), CALLS, unit.path(), begin(call),
                    target.resolved() ? 1.0 : 0.0, target.resolved()));
            if (!target.resolved()) degradations.merge("unresolved_call", 1L, Long::sum);
        }
        for (ObjectCreationExpr call : unit.compilationUnit().findAll(ObjectCreationExpr.class)) {
            String from = enclosingCallable(call, symbolByNode);
            if (from == null) continue;
            ResolvedName target = resolveConstructorCall(call);
            add(relationships, new SemanticModels.Relationship(from, target.name(), CALLS, unit.path(), begin(call),
                    target.resolved() ? 1.0 : 0.0, target.resolved()));
            if (!target.resolved()) degradations.merge("unresolved_call", 1L, Long::sum);
        }
    }

    private static void extractFieldAccess(Unit unit, Map<Node, String> symbolByNode,
                                           Map<String, SemanticModels.Relationship> relationships,
                                           Map<String, Long> degradations) {
        Set<Node> handled = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<Node> candidates = new ArrayList<>(unit.compilationUnit().findAll(FieldAccessExpr.class));
        candidates.addAll(unit.compilationUnit().findAll(NameExpr.class));
        for (Node node : candidates) {
            if (!handled.add(node)) continue;
            String from = enclosingCallable(node, symbolByNode);
            if (from == null) continue;
            ResolvedName target = resolve(() -> {
                ResolvedValueDeclaration value = node instanceof FieldAccessExpr field ? field.resolve() : ((NameExpr) node).resolve();
                if (!value.isField()) throw new IllegalStateException("Not a field");
                ResolvedFieldDeclaration declaration = value.asField();
                return "java:field:" + declaration.declaringType().getQualifiedName() + "#" + declaration.getName();
            }, () -> "");
            if (!target.resolved()) continue;
            SemanticModels.RelationType relation = isWrite(node) ? WRITES : READS;
            add(relationships, new SemanticModels.Relationship(from, target.name(), relation, unit.path(), begin(node), 1.0, true));
        }
    }

    private static boolean isWrite(Node node) {
        boolean assignment = node.findAncestor(AssignExpr.class)
                .map(assign -> assign.getTarget() == node || assign.getTarget().isAncestorOf(node))
                .orElse(false);
        if (assignment) return true;
        return node.findAncestor(UnaryExpr.class)
                .filter(unary -> unary.getExpression() == node || unary.getExpression().isAncestorOf(node))
                .map(unary -> Set.of(UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT,
                        UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT).contains(unary.getOperator()))
                .orElse(false);
    }

    private static void addTestRelationships(Map<String, SemanticModels.Relationship> relationships,
                                             Map<String, SemanticModels.Symbol> symbols) {
        List<SemanticModels.Relationship> calls = relationships.values().stream().filter(edge -> edge.type() == CALLS).toList();
        for (SemanticModels.Relationship call : calls) {
            SemanticModels.Symbol source = symbols.get(call.fromStableKey());
            SemanticModels.Symbol target = symbols.get(call.toStableKey());
            if (source == null || target == null || !source.testSource() || target.testSource()) continue;
            add(relationships, new SemanticModels.Relationship(call.fromStableKey(), call.toStableKey(), TESTS,
                    call.sourcePath(), call.sourceLine(), call.confidence(), call.typeResolved()));
        }
    }

    private static Set<String> unresolvedTargetCandidates(List<SemanticModels.Symbol> symbols) {
        Set<String> candidates = new LinkedHashSet<>();
        for (SemanticModels.Symbol symbol : symbols) {
            String key = symbol.stableKey();
            int open = key.lastIndexOf('(');
            int close = key.lastIndexOf(')');
            if (open < 0 || close < open) continue;
            int arity = key.substring(open + 1, close).isBlank() ? 0 : key.substring(open + 1, close).split(",", -1).length;
            if (symbol.kind() == SemanticModels.SymbolKind.METHOD) {
                int hash = key.lastIndexOf('#', open);
                if (hash >= 0) candidates.add("unresolved:method:" + key.substring(hash + 1, open) + "/" + arity);
            } else if (symbol.kind() == SemanticModels.SymbolKind.CONSTRUCTOR) {
                candidates.add("unresolved:constructor:" + symbol.qualifiedName().replaceFirst("\\.<init>$", "") + "/" + arity);
            }
        }
        return candidates;
    }

    private static String enclosingCallable(Node node, Map<Node, String> symbolByNode) {
        return node.findAncestor(CallableDeclaration.class).map(symbolByNode::get).orElse(null);
    }

    private static void putSymbol(SemanticModels.Symbol symbol, Node node, List<SemanticModels.Symbol> symbols,
                                  Map<Node, String> symbolByNode, Map<String, SemanticModels.Symbol> symbolByKey) {
        symbolByNode.put(node, symbol.stableKey());
        if (symbolByKey.putIfAbsent(symbol.stableKey(), symbol) == null) symbols.add(symbol);
    }

    private static void add(Map<String, SemanticModels.Relationship> relationships, SemanticModels.Relationship relationship) {
        String key = relationship.fromStableKey() + "\n" + relationship.toStableKey() + "\n" + relationship.type()
                + "\n" + relationship.sourcePath() + "\n" + relationship.sourceLine();
        relationships.putIfAbsent(key, relationship);
    }

    private static ResolvedMethod resolveMethod(MethodDeclaration method) {
        try {
            ResolvedMethodDeclaration resolved = method.resolve();
            return new ResolvedMethod(methodKey(resolved), resolved.declaringType().getQualifiedName(), true);
        } catch (RuntimeException exception) {
            String owner = method.findAncestor(TypeDeclaration.class).map(JavaSemanticAdapter::astTypeName).orElse("<unknown>");
            String params = method.getParameters().stream().map(parameter -> parameter.getTypeAsString()).collect(java.util.stream.Collectors.joining(","));
            return new ResolvedMethod("java:method:" + owner + "#" + method.getNameAsString() + "(" + params + ")", owner, false);
        }
    }

    private static ResolvedConstructor resolveConstructor(ConstructorDeclaration constructor) {
        try {
            ResolvedConstructorDeclaration resolved = constructor.resolve();
            return new ResolvedConstructor(constructorKey(resolved), resolved.declaringType().getQualifiedName(), true);
        } catch (RuntimeException exception) {
            String owner = constructor.findAncestor(TypeDeclaration.class).map(JavaSemanticAdapter::astTypeName).orElse("<unknown>");
            String params = constructor.getParameters().stream().map(parameter -> parameter.getTypeAsString()).collect(java.util.stream.Collectors.joining(","));
            return new ResolvedConstructor("java:constructor:" + owner + "#<init>(" + params + ")", owner, false);
        }
    }

    private static ResolvedName resolveConstructorCall(ObjectCreationExpr call) {
        try {
            return new ResolvedName(constructorKey(call.resolve()), true);
        } catch (RuntimeException directResolutionFailure) {
            try {
                String owner = call.getType().resolve().asReferenceType().getQualifiedName();
                List<String> parameters = new ArrayList<>();
                call.getArguments().forEach(argument -> parameters.add(argument.calculateResolvedType().describe()));
                return new ResolvedName("java:constructor:" + owner + "#<init>(" + String.join(",", parameters) + ")", true);
            } catch (RuntimeException typeResolutionFailure) {
                return new ResolvedName("unresolved:constructor:" + call.getTypeAsString() + "/" + call.getArguments().size(), false);
            }
        }
    }

    private static String methodKey(ResolvedMethodDeclaration method) {
        return "java:method:" + method.declaringType().getQualifiedName() + "#" + method.getName() + "(" + parameters(method) + ")";
    }

    private static String constructorKey(ResolvedConstructorDeclaration constructor) {
        return "java:constructor:" + constructor.declaringType().getQualifiedName() + "#<init>(" + parameters(constructor) + ")";
    }

    private static String parameters(ResolvedMethodDeclaration method) {
        List<String> parameters = new ArrayList<>();
        for (int index = 0; index < method.getNumberOfParams(); index++) parameters.add(method.getParam(index).getType().describe());
        return String.join(",", parameters);
    }

    private static String parameters(ResolvedConstructorDeclaration constructor) {
        List<String> parameters = new ArrayList<>();
        for (int index = 0; index < constructor.getNumberOfParams(); index++) parameters.add(constructor.getParam(index).getType().describe());
        return String.join(",", parameters);
    }

    private static String astTypeName(TypeDeclaration<?> type) {
        String packageName = type.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(declaration -> declaration.getNameAsString() + ".").orElse("");
        List<String> names = new ArrayList<>();
        Node current = type;
        while (current instanceof TypeDeclaration<?> declaration) {
            names.add(0, declaration.getNameAsString());
            current = declaration.getParentNode().orElse(null);
        }
        return packageName + String.join(".", names);
    }

    private static ResolvedName resolve(Supplier<String> resolved, Supplier<String> fallback) {
        try {
            String value = resolved.get();
            if (value != null && !value.isBlank()) return new ResolvedName(value, true);
        } catch (RuntimeException ignored) {
            // Coverage records every unresolved declaration or relationship.
        }
        return new ResolvedName(fallback.get(), false);
    }

    private static List<Path> sourceRoots(Path root, BuildModel model) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (BuildModel.Module module : model.modules()) {
            module.mainSourceRoots().forEach(path -> addSafeRoot(root, path, roots));
            module.testSourceRoots().forEach(path -> addSafeRoot(root, path, roots));
        }
        if (roots.isEmpty()) roots.add(root);
        return List.copyOf(roots);
    }

    private static List<Path> testRoots(Path root, BuildModel model) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (BuildModel.Module module : model.modules()) module.testSourceRoots().forEach(path -> addSafeRoot(root, path, roots));
        return List.copyOf(roots);
    }

    private static void addSafeRoot(Path root, String relative, Set<Path> roots) {
        Path candidate = root.resolve(relative).normalize();
        if (candidate.startsWith(root) && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) roots.add(candidate);
    }

    private static List<Path> javaFiles(Path root, List<Path> sourceRoots) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !isExcluded(root, path))
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to enumerate Java sources", exception);
            }
        }
        return files.stream().sorted(Comparator.comparing(path -> relative(root, path))).toList();
    }

    private static boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) if (EXCLUDED_DIRECTORIES.contains(part.toString())) return true;
        return false;
    }

    private static boolean isUnderAny(Path path, List<Path> roots) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "");
    }

    private static int begin(Node node) { return node.getBegin().map(position -> position.line).orElse(1); }
    private static int end(Node node) { return node.getEnd().map(position -> position.line).orElse(begin(node)); }

    private static String digest(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Unit(String path, CompilationUnit compilationUnit, boolean testSource) {}
    private record ResolvedName(String name, boolean resolved) {}
    private record ResolvedMethod(String key, String owner, boolean resolved) {}
    private record ResolvedConstructor(String key, String owner, boolean resolved) {}
}
