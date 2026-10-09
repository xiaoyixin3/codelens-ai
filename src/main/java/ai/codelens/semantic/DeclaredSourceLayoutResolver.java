package ai.codelens.semantic;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts only literal, repository-contained source roots without evaluating build files. */
final class DeclaredSourceLayoutResolver {
    private static final long MAX_DESCRIPTOR_BYTES = 2L * 1024 * 1024;
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_SOURCE = Pattern.compile(
            "(?m)sourceSets\\s*(?:\\.\\s*(main|test)|\\[\\s*['\"](main|test)['\"]\\s*])"
                    + "\\s*\\.\\s*java\\s*\\.\\s*srcDirs?\\s*(=|\\()?\\s*([^\\r\\n;}]*)"
    );
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "target", "build", "node_modules", "dist", "out"
    );

    Result resolve(Path repositoryRoot, Path moduleRoot, List<Path> descriptors) {
        Set<String> main = new LinkedHashSet<>();
        Set<String> test = new LinkedHashSet<>();
        Map<String, String> degradations = new LinkedHashMap<>();
        addCandidate(repositoryRoot, moduleRoot, moduleRoot.resolve("src/main/java"), "main", main, degradations, false);
        addCandidate(repositoryRoot, moduleRoot, moduleRoot.resolve("src/test/java"), "test", test, degradations, false);

        for (Path descriptor : descriptors) {
            try {
                if (Files.size(descriptor) > MAX_DESCRIPTOR_BYTES) {
                    degradations.put(relative(repositoryRoot, descriptor) + "#source-layout", "source_descriptor_too_large");
                    continue;
                }
                String name = descriptor.getFileName().toString();
                if (name.equals("pom.xml")) {
                    parseMaven(repositoryRoot, moduleRoot, descriptor, main, test, degradations);
                } else if (name.startsWith("build.gradle")) {
                    parseGradle(repositoryRoot, moduleRoot, descriptor, main, test, degradations);
                }
            } catch (Exception exception) {
                degradations.put(relative(repositoryRoot, descriptor) + "#source-layout", "source_descriptor_unparseable");
            }
        }
        return new Result(List.copyOf(main), List.copyOf(test), degradations);
    }

    private static void parseMaven(
            Path repositoryRoot,
            Path moduleRoot,
            Path descriptor,
            Set<String> main,
            Set<String> test,
            Map<String, String> degradations
    ) throws Exception {
        Element project = SafeBuildXml.parse(descriptor).getDocumentElement();
        Map<String, String> properties = properties(project);
        properties.put("basedir", ".");
        properties.put("project.basedir", ".");
        properties.put("pom.basedir", ".");
        Element build = directChild(project, "build");
        if (build == null) return;
        String mainDirectory = directText(build, "sourceDirectory");
        String testDirectory = directText(build, "testSourceDirectory");
        if (!mainDirectory.isBlank()) main.clear();
        if (!testDirectory.isBlank()) test.clear();
        addDeclared(repositoryRoot, moduleRoot, descriptor, "main",
                resolveProperties(mainDirectory, properties), main, degradations);
        addDeclared(repositoryRoot, moduleRoot, descriptor, "test",
                resolveProperties(testDirectory, properties), test, degradations);
    }

    private static void parseGradle(
            Path repositoryRoot,
            Path moduleRoot,
            Path descriptor,
            Set<String> main,
            Set<String> test,
            Map<String, String> degradations
    ) throws IOException {
        String content = Files.readString(descriptor, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Matcher declaration = GRADLE_SOURCE.matcher(content);
        int declarations = 0;
        int literalPaths = 0;
        while (declaration.find()) {
            declarations++;
            String kind = declaration.group(1) == null ? declaration.group(2) : declaration.group(1);
            Set<String> destination = kind.equals("main") ? main : test;
            if ("=".equals(declaration.group(3))) destination.clear();
            String arguments = declaration.group(4);
            String nonLiteral = QUOTED.matcher(arguments).replaceAll("").replaceAll("[\\s,\\[\\]()]+", "");
            if (!nonLiteral.isEmpty()) {
                degradations.put(relative(repositoryRoot, descriptor) + "#source-layout", "custom_source_layout_dynamic_or_unsupported");
                continue;
            }
            Matcher quoted = QUOTED.matcher(arguments);
            while (quoted.find()) {
                literalPaths++;
                addDeclared(repositoryRoot, moduleRoot, descriptor, kind, quoted.group(1),
                        destination, degradations);
            }
        }
        if ((declarations > 0 && literalPaths == 0)
                || (content.contains("sourceSets") && content.contains("srcDir") && declarations == 0)) {
            degradations.put(relative(repositoryRoot, descriptor) + "#source-layout", "custom_source_layout_dynamic_or_unsupported");
        }
    }

    private static void addDeclared(
            Path repositoryRoot,
            Path moduleRoot,
            Path descriptor,
            String kind,
            String declared,
            Set<String> destination,
            Map<String, String> degradations
    ) {
        if (declared == null || declared.isBlank()) return;
        String key = relative(repositoryRoot, descriptor) + "#" + kind + "-source";
        if (declared.contains("${") || declared.contains("$")) {
            degradations.put(key, "custom_source_layout_dynamic_or_unsupported");
            return;
        }
        try {
            Path value = Path.of(declared.trim());
            if (value.isAbsolute()) {
                degradations.put(key, "custom_source_root_absolute");
                return;
            }
            addCandidate(repositoryRoot, moduleRoot, moduleRoot.resolve(value).normalize(), kind,
                    destination, degradations, true);
        } catch (RuntimeException exception) {
            degradations.put(key, "custom_source_root_invalid");
        }
    }

    private static void addCandidate(
            Path repositoryRoot,
            Path moduleRoot,
            Path candidate,
            String kind,
            Set<String> destination,
            Map<String, String> degradations,
            boolean declared
    ) {
        String key = relative(repositoryRoot, moduleRoot) + "#" + kind + "-source-root";
        Path repository = repositoryRoot.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(repository)) {
            if (declared) degradations.put(key, "custom_source_root_outside_repository");
            return;
        }
        Path relative = repository.relativize(normalized);
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                if (declared) degradations.put(key, "custom_source_root_excluded");
                return;
            }
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            if (declared) degradations.put(key, "custom_source_root_missing_or_symlink");
            return;
        }
        try {
            if (!normalized.toRealPath().startsWith(repositoryRoot.toRealPath())) {
                if (declared) degradations.put(key, "custom_source_root_outside_repository");
                return;
            }
        } catch (IOException exception) {
            if (declared) degradations.put(key, "custom_source_root_unreadable");
            return;
        }
        destination.add(relative(repositoryRoot, normalized));
    }

    private static Map<String, String> properties(Element project) {
        Map<String, String> properties = new HashMap<>();
        Element element = directChild(project, "properties");
        if (element == null) return properties;
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element property) properties.put(property.getTagName(), property.getTextContent().trim());
        }
        return properties;
    }

    private static String resolveProperties(String value, Map<String, String> properties) {
        String resolved = value;
        for (int pass = 0; pass < 5; pass++) {
            Matcher matcher = PROPERTY.matcher(resolved);
            if (!matcher.find()) return resolved.trim();
            String replacement = properties.get(matcher.group(1));
            if (replacement == null) return resolved;
            resolved = resolved.substring(0, matcher.start()) + replacement + resolved.substring(matcher.end());
        }
        return resolved;
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && element.getTagName().equals(name)) return element;
        }
        return null;
    }

    private static String directText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static String relative(Path root, Path path) {
        String value = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return value.equals(".") ? "" : value;
    }

    record Result(List<String> mainSourceRoots, List<String> testSourceRoots, Map<String, String> degradations) {}
}
