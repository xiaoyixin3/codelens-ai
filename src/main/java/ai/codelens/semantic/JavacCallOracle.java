package ai.codelens.semantic;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A JDK-compiler-based development oracle that is independent of JavaParser.
 * It performs parse/type attribution only: annotation processing and class generation are disabled.
 */
public final class JavacCallOracle {
    private static final int MAX_FILES = 20_000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_RETAINED_DIAGNOSTICS = 2_000;

    public Result analyze(
            Path repositoryRoot,
            BuildModel buildModel,
            Set<String> sourcePaths,
            List<String> targetPrefixes
    ) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Repository root does not exist: " + root);
        if (sourcePaths == null || sourcePaths.isEmpty() || targetPrefixes == null || targetPrefixes.isEmpty()) {
            throw new IllegalArgumentException("Oracle scope must include source paths and target prefixes");
        }
        if (sourcePaths.stream().anyMatch(JavacCallOracle::unsafeRelativePath)
                || targetPrefixes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Oracle scope contains an invalid source path or target prefix");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required for the compiler oracle");

        List<Path> roots = sourceRoots(root, buildModel, sourcePaths);
        List<Path> javaFiles = javaFiles(root, roots);
        BoundedDiagnostics diagnostics = new BoundedDiagnostics();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, roots);
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-implicit:none", "-Xlint:none", "--release", "17"), null, units);
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            task.analyze();

            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            Set<SemanticTruthSetEvaluator.CallFact> facts = new LinkedHashSet<>();
            for (CompilationUnitTree unit : parsed) {
                String sourcePath = relative(root, Path.of(unit.getSourceFile().toUri()));
                if (!sourcePaths.contains(sourcePath)) continue;
                new Scanner(trees, types, unit, sourcePath, targetPrefixes, facts).scan(unit, null);
            }
            List<OracleDiagnostic> reported = diagnostics.retained().stream()
                    .map(diagnostic -> new OracleDiagnostic(
                            diagnostic.getKind().name(),
                            diagnostic.getSource() == null ? "" : relative(root, Path.of(diagnostic.getSource().toUri())),
                            Math.max(0, diagnostic.getLineNumber()),
                            diagnostic.getMessage(Locale.ROOT)))
                    .toList();
            return new Result(List.copyOf(facts), javaFiles.size(), diagnostics.errors(), reported);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to run JDK compiler oracle", exception);
        }
    }

    public Comparison compare(
            Result oracle,
            SemanticModels.Index adapterIndex,
            Set<String> sourcePaths,
            List<String> targetPrefixes
    ) {
        Set<SemanticTruthSetEvaluator.CallFact> adapter = adapterIndex.relationships().stream()
                .filter(edge -> edge.type() == SemanticModels.RelationType.CALLS && edge.typeResolved())
                .filter(edge -> sourcePaths.contains(edge.sourcePath()))
                .filter(edge -> targetPrefixes.stream().anyMatch(edge.toStableKey()::startsWith))
                .map(edge -> new SemanticTruthSetEvaluator.CallFact(
                        edge.sourcePath(), edge.sourceLine(), edge.fromStableKey(), edge.toStableKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<SemanticTruthSetEvaluator.CallFact> attributed = Set.copyOf(oracle.calls());
        Set<SemanticTruthSetEvaluator.CallFact> agreements = intersection(adapter, attributed);
        Set<SemanticTruthSetEvaluator.CallFact> adapterOnly = difference(adapter, attributed);
        Set<SemanticTruthSetEvaluator.CallFact> oracleOnly = difference(attributed, adapter);
        int union = agreements.size() + adapterOnly.size() + oracleOnly.size();
        double agreementRate = union == 0 ? 1.0 : (double) agreements.size() / union;
        return new Comparison("silver/compiler-oracle", agreements, adapterOnly, oracleOnly, agreementRate,
                oracle.compilerErrors(), oracle.diagnostics());
    }

    private static List<Path> sourceRoots(Path root, BuildModel model, Set<String> sourcePaths) {
        LinkedHashSet<Path> available = new LinkedHashSet<>();
        for (BuildModel.Module module : model.modules()) {
            Stream.concat(module.mainSourceRoots().stream(), module.testSourceRoots().stream())
                    .map(root::resolve)
                    .map(Path::normalize)
                    .filter(path -> path.startsWith(root) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(available::add);
        }
        if (available.isEmpty()) throw new IllegalArgumentException("Build model has no Java source roots");
        List<Path> selected = available.stream().filter(sourceRoot -> sourcePaths.stream()
                .map(root::resolve).map(Path::normalize)
                .anyMatch(source -> source.startsWith(sourceRoot))).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("Oracle source paths are outside detected Java source roots");
        return selected;
    }

    private static List<Path> javaFiles(Path repositoryRoot, List<Path> roots) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted(Comparator.comparing(path -> relative(repositoryRoot, path)))
                        .forEach(path -> {
                            if (files.size() >= MAX_FILES) throw new IllegalStateException("Compiler oracle file limit exceeded");
                            try {
                                if (Files.size(path) > MAX_FILE_BYTES) {
                                    throw new IllegalStateException("Compiler oracle file size limit exceeded: " + relative(repositoryRoot, path));
                                }
                            } catch (IOException exception) {
                                throw new IllegalStateException("Unable to inspect compiler oracle input", exception);
                            }
                            files.add(path);
                        });
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to enumerate compiler oracle input", exception);
            }
        }
        return List.copyOf(files);
    }

    private static String stableKey(ExecutableElement executable, Types types) {
        Element owner = executable.getEnclosingElement();
        if (!(owner instanceof TypeElement type)) return "";
        String parameters = executable.getParameters().stream()
                .map(VariableElement::asType)
                .map(types::erasure)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.joining(","));
        if (executable.getKind() == ElementKind.CONSTRUCTOR) {
            return "java:constructor:" + type.getQualifiedName() + "#<init>(" + parameters + ")";
        }
        return "java:method:" + type.getQualifiedName() + "#" + executable.getSimpleName() + "(" + parameters + ")";
    }

    private static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return normalized.toString().replace('\\', '/');
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private static boolean unsafeRelativePath(String value) {
        if (value == null || value.isBlank()) return true;
        Path path = Path.of(value);
        if (path.isAbsolute() || value.contains("\\")) return true;
        for (Path part : path) if (part.toString().equals("..")) return true;
        return false;
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        return left.stream().filter(right::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static <T> Set<T> difference(Set<T> left, Set<T> right) {
        return left.stream().filter(value -> !right.contains(value)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree unit;
        private final String sourcePath;
        private final List<String> targetPrefixes;
        private final Set<SemanticTruthSetEvaluator.CallFact> facts;

        private Scanner(Trees trees, Types types, CompilationUnitTree unit, String sourcePath,
                        List<String> targetPrefixes, Set<SemanticTruthSetEvaluator.CallFact> facts) {
            this.trees = trees;
            this.types = types;
            this.unit = unit;
            this.sourcePath = sourcePath;
            this.targetPrefixes = targetPrefixes;
            this.facts = facts;
        }

        @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            add(getCurrentPath());
            return super.visitMethodInvocation(node, unused);
        }

        @Override public Void visitNewClass(NewClassTree node, Void unused) {
            add(getCurrentPath());
            return super.visitNewClass(node, unused);
        }

        private void add(TreePath callPath) {
            Element targetElement = trees.getElement(callPath);
            ExecutableElement caller = enclosingExecutable(callPath);
            if (!(targetElement instanceof ExecutableElement target) || caller == null) return;
            String from = stableKey(caller, types);
            String to = stableKey(target, types);
            if (from.isBlank() || to.isBlank() || targetPrefixes.stream().noneMatch(to::startsWith)) return;
            long position = trees.getSourcePositions().getStartPosition(unit, callPath.getLeaf());
            long line = position < 0 ? 0 : unit.getLineMap().getLineNumber(position);
            if (line < 1 || line > Integer.MAX_VALUE) return;
            facts.add(new SemanticTruthSetEvaluator.CallFact(sourcePath, (int) line, from, to));
        }

        private ExecutableElement enclosingExecutable(TreePath path) {
            TreePath current = path.getParentPath();
            while (current != null) {
                if (current.getLeaf() instanceof MethodTree) {
                    Element element = trees.getElement(current);
                    return element instanceof ExecutableElement executable ? executable : null;
                }
                current = current.getParentPath();
            }
            return null;
        }
    }

    private static final class BoundedDiagnostics implements DiagnosticListener<JavaFileObject> {
        private final List<Diagnostic<? extends JavaFileObject>> retained = new ArrayList<>();
        private int errors;

        @Override public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) errors++;
            if (retained.size() < MAX_RETAINED_DIAGNOSTICS) retained.add(diagnostic);
        }

        private List<Diagnostic<? extends JavaFileObject>> retained() { return retained; }
        private int errors() { return errors; }
    }

    public record OracleDiagnostic(String kind, String sourcePath, long line, String message) {}

    public record Result(
            List<SemanticTruthSetEvaluator.CallFact> calls,
            int compiledSourceFiles,
            int compilerErrors,
            List<OracleDiagnostic> diagnostics
    ) {
        public Result {
            calls = List.copyOf(calls);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Comparison(
            String evidenceTier,
            Set<SemanticTruthSetEvaluator.CallFact> agreements,
            Set<SemanticTruthSetEvaluator.CallFact> adapterOnly,
            Set<SemanticTruthSetEvaluator.CallFact> oracleOnly,
            double agreementRate,
            int compilerErrors,
            List<OracleDiagnostic> diagnostics
    ) {
        public Comparison {
            agreements = Set.copyOf(agreements);
            adapterOnly = Set.copyOf(adapterOnly);
            oracleOnly = Set.copyOf(oracleOnly);
            diagnostics = List.copyOf(diagnostics);
        }

        public List<SemanticTruthSetEvaluator.CallFact> reviewQueue(int sampleEveryAgreement) {
            if (sampleEveryAgreement < 1) throw new IllegalArgumentException("Agreement sample interval must be positive");
            LinkedHashSet<SemanticTruthSetEvaluator.CallFact> queue = new LinkedHashSet<>();
            queue.addAll(adapterOnly);
            queue.addAll(oracleOnly);
            List<SemanticTruthSetEvaluator.CallFact> orderedAgreements = agreements.stream()
                    .sorted(Comparator.comparing(SemanticTruthSetEvaluator.CallFact::sourcePath)
                            .thenComparingInt(SemanticTruthSetEvaluator.CallFact::line)
                            .thenComparing(SemanticTruthSetEvaluator.CallFact::fromStableKey)
                            .thenComparing(SemanticTruthSetEvaluator.CallFact::toStableKey))
                    .toList();
            for (int index = 0; index < orderedAgreements.size(); index += sampleEveryAgreement) {
                queue.add(orderedAgreements.get(index));
            }
            return List.copyOf(queue);
        }
    }
}
