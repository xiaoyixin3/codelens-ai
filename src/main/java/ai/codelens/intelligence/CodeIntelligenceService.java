package ai.codelens.intelligence;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.store.JdbcStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("worker")
public class CodeIntelligenceService {
    public static final String PARSER_VERSION = "java-native-multilang-v1";
    private static final Pattern GO_FUNCTION = Pattern.compile("^\\s*func\\s+(?:\\(\\s*\\w*\\s*\\*?([A-Za-z_]\\w*)[^)]*\\)\\s*)?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern GO_TYPE = Pattern.compile("^\\s*type\\s+([A-Za-z_]\\w*)\\s+(?:struct|interface)\\b");
    private static final Pattern TS_DECLARATION = Pattern.compile("^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:async\\s+)?(class|interface|type|enum|function)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern TS_VARIABLE = Pattern.compile("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
    private static final Pattern JVM_TYPE = Pattern.compile("^\\s*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|open|data|value|internal)\\s+)*(class|interface|enum|record|object)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile("^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*(?:<[^>]+>\\s*)?[A-Za-z_$][\\w$<>,.?\\[\\]]*\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern KOTLIN_METHOD = Pattern.compile("^\\s*(?:(?:public|protected|private|internal|open|final|abstract|override|suspend|inline|tailrec|operator|infix|external)\\s+)*fun\\s+(?:<[^>]+>\\s*)?([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern PYTHON_TYPE = Pattern.compile("^\\s*class\\s+([A-Za-z_]\\w*)\\b");
    private static final Pattern PYTHON_FUNCTION = Pattern.compile("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern C_TYPE = Pattern.compile("^\\s*(?:(?:public|protected|private|internal|abstract|sealed|static|partial|readonly|ref|unsafe|final)\\s+)*(class|interface|struct|enum|union|namespace)\\s+([A-Za-z_]\\w*)");
    private static final Pattern C_FUNCTION = Pattern.compile("^\\s*(?:(?:public|protected|private|internal|static|virtual|override|abstract|sealed|async|extern|inline|constexpr|consteval|friend|unsafe)\\s+)*(?:[A-Za-z_~][\\w:<>,.?*&\\[\\]\\s]+\\s+)([A-Za-z_~]\\w*)\\s*\\(");
    private static final Pattern RUST_TYPE = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(struct|enum|trait|union|type|mod)\\s+([A-Za-z_]\\w*)");
    private static final Pattern RUST_IMPL = Pattern.compile("^\\s*impl(?:<[^>]+>)?\\s+(?:[^\\s]+\\s+for\\s+)?([A-Za-z_]\\w*)");
    private static final Pattern RUST_FUNCTION = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:extern\\s+\"[^\"]+\"\\s+)?fn\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern PHP_TYPE = Pattern.compile("^\\s*(?:(?:abstract|final|readonly)\\s+)*(class|interface|trait|enum)\\s+([A-Za-z_]\\w*)");
    private static final Pattern PHP_FUNCTION = Pattern.compile("^\\s*(?:(?:public|protected|private|static|final|abstract|readonly)\\s+)*function\\s+&?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern RUBY_TYPE = Pattern.compile("^\\s*(class|module)\\s+([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*)");
    private static final Pattern RUBY_FUNCTION = Pattern.compile("^\\s*def\\s+(?:self\\.)?([A-Za-z_]\\w*[!?=]?)");
    private static final Pattern SWIFT_TYPE = Pattern.compile("^\\s*(?:(?:public|open|internal|private|fileprivate|final|indirect)\\s+)*(class|struct|protocol|enum|actor|extension)\\s+([A-Za-z_]\\w*)");
    private static final Pattern SWIFT_FUNCTION = Pattern.compile("^\\s*(?:(?:public|open|internal|private|fileprivate|static|class|final|override|mutating|nonmutating|required|convenience)\\s+)*(?:async\\s+)?func\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch", "return", "new", "sizeof", "typeof", "func", "function");

    private final GitHubClient github;
    private final JdbcStore store;
    private final RuntimeConfig config;

    public CodeIntelligenceService(GitHubClient github, JdbcStore store, RuntimeConfig config) {
        this.github = github;
        this.store = store;
        this.config = config;
    }

    public Result analyze(String reviewRunId, long repositoryId, Models.ReviewJob job, Models.PullRequest pull) {
        List<Models.ChangedFile> bounded = pull.files().subList(0, Math.min(pull.files().size(), config.maxChangedFiles()));
        Snapshot base = indexSide(job, bounded, pull.baseSha(), "base");
        Snapshot head = indexSide(job, bounded, pull.headSha(), "head");
        base = resolveEdges(base);
        head = resolveEdges(head);
        List<Change> changes = compare(base.symbols(), head.symbols());
        List<Impact> impacts = trace(changes, base, head, 2, 12);
        Models.ImpactSummary summary = summarize(changes, impacts, base, head);
        Result result = new Result(base, head, changes, impacts, summary);
        store.saveIntelligence(reviewRunId, repositoryId, pull.baseSha(), result);
        return result;
    }

    private Snapshot indexSide(Models.ReviewJob job, List<Models.ChangedFile> files, String commit, String side) {
        List<IndexedFile> indexed = new ArrayList<>();
        List<Symbol> symbols = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (Models.ChangedFile file : files) {
            String path = side.equals("base") && !file.previousPath().isBlank() ? file.previousPath() : file.path();
            String language = language(path);
            if (language == null) { indexed.add(new IndexedFile(path, "", "", "skipped", "unsupported_language")); continue; }
            try {
                String content = github.getFileContent(job.installationId(), job.owner(), job.repo(), path, commit);
                if (content.getBytes(StandardCharsets.UTF_8).length > config.maxIndexFileBytes()) {
                    indexed.add(new IndexedFile(path, language, digest(content), "skipped", "file_too_large"));
                    continue;
                }
                Parsed parsed = parse(path, language, content);
                indexed.add(new IndexedFile(path, language, digest(content), "indexed", ""));
                symbols.addAll(parsed.symbols()); edges.addAll(parsed.edges());
            } catch (RuntimeException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("status 404")) indexed.add(new IndexedFile(path, language, "", "absent", ""));
                else indexed.add(new IndexedFile(path, language, "", "failed", "content_unavailable"));
            }
        }
        String scopeHash = digest(files.stream().map(Models.ChangedFile::path).sorted().reduce("", (a, b) -> a + "\n" + b));
        return new Snapshot(UUID.randomUUID().toString(), commit, pullBase(files, side, commit), scopeHash, indexed, uniqueSymbols(symbols), uniqueEdges(edges));
    }

    private static String pullBase(List<Models.ChangedFile> ignored, String side, String commit) { return commit; }

    public static Parsed parse(String path, String language, String content) {
        String[] lines = content.split("\\R", -1);
        List<Symbol> symbols = new ArrayList<>();
        String module = language + ":" + path + ":file:$module";
        symbols.add(new Symbol(module, path, "file", Path.of(path).getFileName().toString(), "$module", 1, Math.max(1, lines.length), path, digest(content), true));
        List<Declaration> declarations = new ArrayList<>();
        Map<String, List<String>> byName = new HashMap<>();
        for (int index = 0; index < lines.length; index++) {
            Declaration declaration = declaration(language, lines, index);
            if (declaration == null) continue;
            String qualified = declaration.qualifiedName();
            for (int parentIndex = declarations.size() - 1; parentIndex >= 0; parentIndex--) {
                Declaration parent = declarations.get(parentIndex);
                if (parent.container() && parent.start() < index + 1 && parent.end() >= index + 1) {
                    qualified = parent.qualifiedName() + "." + declaration.name(); break;
                }
            }
            String kind = !qualified.equals(declaration.name()) && declaration.kind().equals("function") ? "method" : declaration.kind();
            declaration = new Declaration(kind, declaration.name(), qualified, declaration.start(), declaration.end(), declaration.signature(), declaration.exported(), declaration.container(), declaration.arity());
            declarations.add(declaration);
            String stable = language + ":" + path + ":" + kind + ":" + qualified + (kind.equals("function") || kind.equals("method") ? "/" + declaration.arity() : "");
            String body = String.join("\n", java.util.Arrays.copyOfRange(lines, index, Math.min(lines.length, declaration.end())));
            symbols.add(new Symbol(stable, path, kind, declaration.name(), qualified, index + 1, declaration.end(), declaration.signature(), digest(body), declaration.exported()));
            byName.computeIfAbsent(declaration.name(), ignored -> new ArrayList<>()).add(stable);
        }
        List<Edge> edges = new ArrayList<>();
        for (Symbol symbol : symbols.subList(1, symbols.size())) {
            for (int line = symbol.startLine() - 1; line < symbol.endLine() && line < lines.length; line++) {
                Matcher calls = CALL.matcher(stripComment(language, lines[line]));
                while (calls.find()) {
                    String name = calls.group(1);
                    if (name.equals(symbol.name()) || CONTROL.contains(name)) continue;
                    List<String> candidates = byName.getOrDefault(name, List.of());
                    String target = candidates.size() == 1 ? candidates.get(0) : "unresolved:" + name;
                    edges.add(new Edge(symbol.stableKey(), target, "CALLS", candidates.size() == 1 ? .9 : .4, path, line + 1));
                }
            }
        }
        return new Parsed(uniqueSymbols(symbols), uniqueEdges(edges));
    }

    private static Declaration declaration(String language, String[] lines, int index) {
        String line = lines[index]; Matcher matcher;
        if (language.equals("go")) {
            matcher = GO_FUNCTION.matcher(line);
            if (matcher.find()) return build("function", matcher.group(2), matcher.group(1) == null ? matcher.group(2) : matcher.group(1) + "." + matcher.group(2), line, index, lines, startsUpper(matcher.group(2)), false);
            matcher = GO_TYPE.matcher(line);
            if (matcher.find()) return build("type", matcher.group(1), matcher.group(1), line, index, lines, startsUpper(matcher.group(1)), true);
        } else if (language.equals("typescript") || language.equals("javascript")) {
            matcher = TS_DECLARATION.matcher(line);
            if (matcher.find()) return build(matcher.group(1), matcher.group(2), matcher.group(2), line, index, lines, line.contains("export"), !matcher.group(1).matches("function|type"));
            matcher = TS_VARIABLE.matcher(line);
            if (matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, line.contains("export"), false);
        } else if (language.equals("java") || language.equals("kotlin")) {
            matcher = JVM_TYPE.matcher(line);
            if (matcher.find()) return build(matcher.group(1), matcher.group(2), matcher.group(2), line, index, lines, line.contains("public") || language.equals("kotlin"), true);
            matcher = (language.equals("java") ? JAVA_METHOD : KOTLIN_METHOD).matcher(line);
            if (matcher.find() && !line.matches(".*\\b(if|for|while|switch|catch)\\s*\\(.*")) return build("function", matcher.group(1), matcher.group(1), line, index, lines, line.contains("public"), false);
        } else if (language.equals("python")) {
            matcher = PYTHON_TYPE.matcher(line);
            if (matcher.find()) return build("class", matcher.group(1), matcher.group(1), line, index, lines, !matcher.group(1).startsWith("_"), true);
            matcher = PYTHON_FUNCTION.matcher(line);
            if (matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, !matcher.group(1).startsWith("_"), false);
        } else if (Set.of("c", "cpp", "csharp").contains(language)) {
            matcher = C_TYPE.matcher(line);
            if (matcher.find()) return build(normalizeKind(matcher.group(1)), matcher.group(2), matcher.group(2), line, index, lines, line.contains("public") || !language.equals("csharp"), true);
            matcher = C_FUNCTION.matcher(line);
            if (!rejected(line) && matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, line.contains("public") || !language.equals("csharp"), false);
        } else if (language.equals("rust")) {
            matcher = RUST_TYPE.matcher(line);
            if (matcher.find()) return build(normalizeKind(matcher.group(1)), matcher.group(2), matcher.group(2), line, index, lines, line.contains("pub"), true);
            matcher = RUST_IMPL.matcher(line);
            if (matcher.find()) return build("implementation", matcher.group(1), matcher.group(1), line, index, lines, false, true);
            matcher = RUST_FUNCTION.matcher(line);
            if (matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, line.contains("pub"), false);
        } else if (language.equals("php")) {
            matcher = PHP_TYPE.matcher(line);
            if (matcher.find()) return build(normalizeKind(matcher.group(1)), matcher.group(2), matcher.group(2), line, index, lines, true, true);
            matcher = PHP_FUNCTION.matcher(line);
            if (matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, !line.contains("private") && !line.contains("protected"), false);
        } else if (language.equals("ruby")) {
            matcher = RUBY_TYPE.matcher(line);
            if (matcher.find()) return build(matcher.group(1), matcher.group(2), matcher.group(2), line, index, lines, true, true);
            matcher = RUBY_FUNCTION.matcher(line);
            if (matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, true, false);
        } else if (language.equals("swift")) {
            matcher = SWIFT_TYPE.matcher(line);
            if (matcher.find()) return build(normalizeKind(matcher.group(1)), matcher.group(2), matcher.group(2), line, index, lines, line.contains("public") || line.contains("open"), true);
            matcher = SWIFT_FUNCTION.matcher(line);
            if (matcher.find()) return build("function", matcher.group(1), matcher.group(1), line, index, lines, line.contains("public") || line.contains("open"), false);
        }
        return null;
    }

    private static Declaration build(String kind, String name, String qualified, String signature, int index, String[] lines, boolean exported, boolean container) {
        int end = languageBlockEnd(lines, index, kind, signature);
        return new Declaration(kind, name, qualified, index + 1, end, signature.trim(), exported, container, arity(signature));
    }

    private static int languageBlockEnd(String[] lines, int start, String kind, String signature) {
        String trimmed = signature.trim();
        if (trimmed.startsWith("def ") || trimmed.startsWith("async def ") || trimmed.startsWith("class ") && signature.endsWith(":")) return indentationEnd(lines, start);
        if (trimmed.startsWith("def ") || trimmed.startsWith("class ") || trimmed.startsWith("module ")) return rubyEnd(lines, start);
        return blockEnd(lines, start);
    }

    private static int indentationEnd(String[] lines, int start) {
        int base = leadingWhitespace(lines[start]);
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].isBlank() || lines[i].stripLeading().startsWith("#")) continue;
            if (leadingWhitespace(lines[i]) <= base) return i;
        }
        return lines.length;
    }

    private static int rubyEnd(String[] lines, int start) {
        int depth = 0;
        Pattern starter = Pattern.compile("^\\s*(?:class|module|def|if|unless|case|begin|while|until|for)\\b|\\bdo\\s*(?:\\|[^|]*\\|)?\\s*$");
        for (int i = start; i < Math.min(lines.length, start + 500); i++) {
            String line = stripComment("ruby", lines[i]);
            if (starter.matcher(line).find()) depth++;
            if (line.stripLeading().startsWith("end") && --depth <= 0) return i + 1;
        }
        return Math.min(lines.length, start + 200);
    }

    private static int blockEnd(String[] lines, int start) {
        int depth = 0; boolean opened = false;
        for (int i = start; i < Math.min(lines.length, start + 1000); i++) {
            String line = lines[i];
            for (char c : line.toCharArray()) { if (c == '{') { depth++; opened = true; } else if (c == '}') depth--; }
            if (opened && depth <= 0) return i + 1;
        }
        return Math.min(lines.length, start + 200);
    }

    private static int arity(String line) {
        int open = line.indexOf('('), close = line.lastIndexOf(')');
        if (open < 0 || close <= open || line.substring(open + 1, close).isBlank()) return 0;
        int depth = 0, count = 1;
        for (char c : line.substring(open + 1, close).toCharArray()) {
            if ("<[(".indexOf(c) >= 0) depth++; else if (">])".indexOf(c) >= 0) depth = Math.max(0, depth - 1); else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private static Snapshot resolveEdges(Snapshot snapshot) {
        Map<String, List<Symbol>> byName = new HashMap<>();
        snapshot.symbols().forEach(symbol -> byName.computeIfAbsent(symbol.name(), ignored -> new ArrayList<>()).add(symbol));
        List<Edge> resolved = snapshot.edges().stream().map(edge -> {
            if (!edge.toStableKey().startsWith("unresolved:")) return edge;
            String name = edge.toStableKey().substring("unresolved:".length());
            List<Symbol> candidates = byName.getOrDefault(name, List.of());
            if (candidates.size() == 1) return new Edge(edge.fromStableKey(), candidates.get(0).stableKey(), edge.type(), .7, edge.sourcePath(), edge.sourceLine());
            return edge;
        }).toList();
        return new Snapshot(snapshot.id(), snapshot.commitSha(), snapshot.baseSha(), snapshot.scopeHash(), snapshot.files(), snapshot.symbols(), uniqueEdges(resolved));
    }

    private static List<Change> compare(List<Symbol> base, List<Symbol> head) {
        Map<String, Symbol> before = base.stream().collect(java.util.stream.Collectors.toMap(Symbol::stableKey, value -> value, (a,b) -> a));
        Map<String, Symbol> after = head.stream().collect(java.util.stream.Collectors.toMap(Symbol::stableKey, value -> value, (a,b) -> a));
        List<Change> changes = new ArrayList<>();
        for (Symbol current : head) {
            Symbol old = before.get(current.stableKey());
            if (old == null) changes.add(new Change("ADDED", current.kind(), current.qualifiedName(), null, current, true, true));
            else if (!old.contentHash().equals(current.contentHash()) || !old.signature().equals(current.signature()))
                changes.add(new Change("MODIFIED", current.kind(), current.qualifiedName(), old, current,
                        !old.contentHash().equals(current.contentHash()), !old.signature().equals(current.signature())));
        }
        for (Symbol old : base) if (!after.containsKey(old.stableKey())) changes.add(new Change("DELETED", old.kind(), old.qualifiedName(), old, null, true, true));
        return changes.stream().filter(change -> !change.kind().equals("file") || change.before() == null || change.after() == null).toList();
    }

    private static List<Impact> trace(List<Change> changes, Snapshot base, Snapshot head, int maxDepth, int maxPaths) {
        List<Impact> results = new ArrayList<>();
        for (Change change : changes) {
            Snapshot snapshot = change.after() != null ? head : base;
            String changed = change.after() != null ? change.after().stableKey() : change.before().stableKey();
            Map<String, List<Edge>> incoming = new HashMap<>();
            snapshot.edges().forEach(edge -> incoming.computeIfAbsent(edge.toStableKey(), ignored -> new ArrayList<>()).add(edge));
            Map<String, Symbol> symbols = snapshot.symbols().stream().collect(java.util.stream.Collectors.toMap(Symbol::stableKey, value -> value, (a,b)->a));
            Deque<Walk> queue = new ArrayDeque<>(); queue.add(new Walk(changed, 0, 1, List.of(changed)));
            Set<String> visited = new HashSet<>(); visited.add(changed);
            while (!queue.isEmpty() && results.size() < maxPaths) {
                Walk current = queue.removeFirst();
                if (current.depth() >= maxDepth) continue;
                for (Edge edge : incoming.getOrDefault(current.key(), List.of())) {
                    if (!visited.add(edge.fromStableKey())) continue;
                    Symbol impacted = symbols.get(edge.fromStableKey()); if (impacted == null) continue;
                    int depth = current.depth() + 1;
                    double score = current.score() * edge.confidence() * (depth == 1 ? 1 : .55);
                    List<String> path = new ArrayList<>(current.path()); path.add(edge.fromStableKey());
                    results.add(new Impact(changed, impacted, depth, Math.round(score * 1000d) / 1000d, path,
                            List.of(Map.of("path", edge.sourcePath(), "line", edge.sourceLine(), "confidence", edge.confidence()))));
                    queue.addLast(new Walk(edge.fromStableKey(), depth, score, path));
                }
            }
        }
        return results.stream().sorted(Comparator.comparingDouble(Impact::score).reversed()).limit(maxPaths).toList();
    }

    private static Models.ImpactSummary summarize(List<Change> changes, List<Impact> paths, Snapshot base, Snapshot head) {
        int impacted = (int) paths.stream().map(path -> path.impacted().stableKey()).distinct().count();
        int score = Math.min(100, changes.size() * 5 + impacted * 8 + paths.stream().mapToInt(path -> path.depth() == 1 ? 6 : 3).sum());
        String level = score >= 60 ? "high" : score >= 25 ? "medium" : "low";
        List<Models.ImpactPath> top = paths.stream().limit(5).map(path -> new Models.ImpactPath(shortName(path.changedStableKey()), path.impacted().qualifiedName(), path.depth(), path.score())).toList();
        long indexed = head.files().stream().filter(file -> file.status().equals("indexed")).count();
        String warning = "PR-delta coverage: " + indexed + "/" + head.files().size() + " changed files indexed; callers in unchanged files are not visible.";
        return new Models.ImpactSummary(level, score, changes.size(), impacted, top, warning);
    }

    public static String language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".go")) return "go";
        if (lower.matches(".*\\.(ts|tsx|mts|cts)$")) return "typescript";
        if (lower.matches(".*\\.(js|jsx|mjs|cjs)$")) return "javascript";
        if (lower.endsWith(".java")) return "java";
        if (lower.matches(".*\\.(kt|kts)$")) return "kotlin";
        if (lower.matches(".*\\.(py|pyi|pyw)$")) return "python";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.matches(".*\\.(c|h)$")) return "c";
        if (lower.matches(".*\\.(cc|cpp|cxx|hh|hpp|hxx)$")) return "cpp";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.matches(".*\\.(php|phtml)$")) return "php";
        if (lower.matches(".*\\.(rb|rake)$")) return "ruby";
        if (lower.endsWith(".swift")) return "swift";
        return null;
    }

    private static String stripComment(String language, String line) {
        String marker = language.equals("python") || language.equals("ruby") ? "#" : "//";
        if (language.equals("php")) { int hash = line.indexOf('#'); if (hash >= 0) line = line.substring(0, hash); }
        int index = line.indexOf(marker); return index < 0 ? line : line.substring(0, index);
    }
    private static int leadingWhitespace(String value) {
        int count = 0; for (char c : value.toCharArray()) { if (c == ' ') count++; else if (c == '\t') count += 4; else break; } return count;
    }
    private static boolean rejected(String line) {
        String value = line.stripLeading();
        return List.of("return ","throw ","if ","if(","for ","for(","while ","while(","switch ","switch(","catch ","catch(","case ","else ","new ","delete ").stream().anyMatch(value::startsWith);
    }
    private static String normalizeKind(String kind) {
        return Set.of("struct","union","record","type","object","actor","extension").contains(kind) ? "type" : kind;
    }
    private static boolean startsUpper(String value) { return !value.isEmpty() && Character.isUpperCase(value.charAt(0)); }
    private static String shortName(String stable) { int index = stable.lastIndexOf(':'); return index < 0 ? stable : stable.substring(index + 1); }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static List<Symbol> uniqueSymbols(List<Symbol> input) { return new ArrayList<>(input.stream().collect(java.util.stream.Collectors.toMap(Symbol::stableKey, v -> v, (a,b)->a, LinkedHashMap::new)).values()); }
    private static List<Edge> uniqueEdges(List<Edge> input) { return new ArrayList<>(input.stream().collect(java.util.stream.Collectors.toMap(e -> e.fromStableKey()+"|"+e.toStableKey()+"|"+e.sourcePath()+"|"+e.sourceLine(), v -> v, (a,b)->a, LinkedHashMap::new)).values()); }

    public record IndexedFile(String path, String language, String contentHash, String status, String skipReason) {}
    public record Symbol(String stableKey, String path, String kind, String name, String qualifiedName, int startLine, int endLine, String signature, String contentHash, boolean exported) {}
    public record Edge(String fromStableKey, String toStableKey, String type, double confidence, String sourcePath, int sourceLine) {}
    public record Snapshot(String id, String commitSha, String baseSha, String scopeHash, List<IndexedFile> files, List<Symbol> symbols, List<Edge> edges) {}
    public record Change(String type, String kind, String qualifiedName, Symbol before, Symbol after, boolean bodyChanged, boolean signatureChanged) {}
    public record Impact(String changedStableKey, Symbol impacted, int depth, double score, List<String> path, List<Map<String,Object>> evidence) {}
    public record Result(Snapshot base, Snapshot head, List<Change> changes, List<Impact> paths, Models.ImpactSummary summary) {}
    public record Parsed(List<Symbol> symbols, List<Edge> edges) {}
    private record Declaration(String kind, String name, String qualifiedName, int start, int end, String signature, boolean exported, boolean container, int arity) {}
    private record Walk(String key, int depth, double score, List<String> path) {}
}
