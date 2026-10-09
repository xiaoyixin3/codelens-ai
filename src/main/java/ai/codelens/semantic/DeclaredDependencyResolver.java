package ai.codelens.semantic;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads literal dependency declarations without evaluating repository build code. */
final class DeclaredDependencyResolver {
    private static final long MAX_DESCRIPTOR_BYTES = 2L * 1024 * 1024;
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "(?m)^\\s*(api|implementation|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|annotationProcessor)"
                    + "\\s*\\(?\\s*['\"]([^:'\"]+):([^:'\"]+):([^'\"]+)['\"]"
    );
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private final Path cacheRoot;
    private final int maxJars;
    private final long maxJarBytes;

    DeclaredDependencyResolver(Path cacheRoot, int maxJars, long maxJarBytes) {
        this.cacheRoot = cacheRoot == null ? null : cacheRoot.toAbsolutePath().normalize();
        this.maxJars = maxJars;
        this.maxJarBytes = maxJarBytes;
    }

    Result resolve(Path repositoryRoot, List<Path> descriptors) {
        Map<String, String> degradations = new LinkedHashMap<>();
        Map<String, BuildModel.Dependency> dependencies = new LinkedHashMap<>();
        for (Path descriptor : descriptors) {
            String name = descriptor.getFileName().toString();
            try {
                if (Files.size(descriptor) > MAX_DESCRIPTOR_BYTES) {
                    degradations.put(relative(repositoryRoot, descriptor), "dependency_descriptor_too_large");
                    continue;
                }
                List<BuildModel.Dependency> parsed = name.equals("pom.xml")
                        ? parseMaven(repositoryRoot, descriptor, degradations)
                        : name.startsWith("build.gradle")
                        ? parseGradle(repositoryRoot, descriptor, degradations)
                        : List.of();
                for (BuildModel.Dependency dependency : parsed) {
                    dependencies.putIfAbsent(dependency.coordinate() + ":" + dependency.scope(), dependency);
                }
            } catch (Exception exception) {
                degradations.put(relative(repositoryRoot, descriptor), "dependency_descriptor_unparseable");
            }
        }

        List<BuildModel.Dependency> sortedDependencies = dependencies.values().stream()
                .sorted(Comparator.comparing(BuildModel.Dependency::coordinate)
                        .thenComparing(BuildModel.Dependency::scope)
                        .thenComparing(BuildModel.Dependency::descriptor))
                .toList();
        List<BuildModel.ClasspathEntry> classpath = resolveClasspath(repositoryRoot, sortedDependencies, degradations);
        if (!sortedDependencies.isEmpty()) degradations.put("dependency_resolution_scope", "dependency_direct_declarations_only");
        return new Result(sortedDependencies, classpath, degradations);
    }

    private List<BuildModel.Dependency> parseMaven(
            Path repositoryRoot, Path descriptor, Map<String, String> degradations
    ) throws Exception {
        Document document = SafeBuildXml.parse(descriptor);
        Element project = document.getDocumentElement();
        Map<String, String> properties = mavenProperties(project);
        String projectGroup = directText(project, "groupId");
        String projectVersion = directText(project, "version");
        Element parent = directChild(project, "parent");
        if (projectGroup.isBlank() && parent != null) projectGroup = directText(parent, "groupId");
        if (projectVersion.isBlank() && parent != null) projectVersion = directText(parent, "version");
        properties.put("project.groupId", projectGroup);
        properties.put("project.version", projectVersion);
        properties.put("pom.groupId", projectGroup);
        properties.put("pom.version", projectVersion);

        List<BuildModel.Dependency> result = new ArrayList<>();
        NodeList nodes = project.getElementsByTagName("dependency");
        int unresolved = 0;
        for (int index = 0; index < nodes.getLength(); index++) {
            Element dependency = (Element) nodes.item(index);
            if (!isDirectProjectDependency(dependency)) continue;
            String type = resolveProperties(directText(dependency, "type"), properties);
            if (!type.isBlank() && !type.equals("jar")) continue;
            String group = resolveProperties(directText(dependency, "groupId"), properties);
            String artifact = resolveProperties(directText(dependency, "artifactId"), properties);
            String version = resolveProperties(directText(dependency, "version"), properties);
            String classifier = resolveProperties(directText(dependency, "classifier"), properties);
            String scope = resolveProperties(directText(dependency, "scope"), properties);
            if (scope.isBlank()) scope = "compile";
            if (!safeCoordinate(group, artifact, version, classifier)) {
                unresolved++;
                continue;
            }
            result.add(new BuildModel.Dependency(group, artifact, version, classifier, scope,
                    relative(repositoryRoot, descriptor)));
        }
        if (unresolved > 0) degradations.put(relative(repositoryRoot, descriptor) + "#maven", "dependency_declaration_unresolved:" + unresolved);
        return result;
    }

    private List<BuildModel.Dependency> parseGradle(
            Path repositoryRoot, Path descriptor, Map<String, String> degradations
    ) throws IOException {
        String content = Files.readString(descriptor, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Matcher matcher = GRADLE_DEPENDENCY.matcher(content);
        List<BuildModel.Dependency> result = new ArrayList<>();
        while (matcher.find()) {
            String group = matcher.group(2).trim();
            String artifact = matcher.group(3).trim();
            String version = matcher.group(4).trim();
            if (!safeCoordinate(group, artifact, version, "")) continue;
            result.add(new BuildModel.Dependency(group, artifact, version, "", matcher.group(1),
                    relative(repositoryRoot, descriptor)));
        }
        if (content.contains("libs.") || content.contains("platform(") || content.contains("project(")) {
            degradations.put(relative(repositoryRoot, descriptor) + "#gradle", "dependency_declaration_dynamic_or_catalog");
        }
        return result;
    }

    private List<BuildModel.ClasspathEntry> resolveClasspath(
            Path repositoryRoot,
            List<BuildModel.Dependency> dependencies,
            Map<String, String> degradations
    ) {
        if (dependencies.isEmpty()) return List.of();
        if (cacheRoot == null) {
            degradations.put("dependency_cache", "dependency_cache_not_configured");
            return List.of();
        }
        final Path trustedRoot;
        try {
            trustedRoot = cacheRoot.toRealPath();
        } catch (IOException exception) {
            degradations.put("dependency_cache", "dependency_cache_untrusted_or_unavailable");
            return List.of();
        }
        final Path repository;
        try {
            repository = repositoryRoot.toRealPath();
        } catch (IOException exception) {
            degradations.put("dependency_cache", "dependency_cache_untrusted_or_unavailable");
            return List.of();
        }
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cacheRoot)
                || trustedRoot.startsWith(repository) || repository.startsWith(trustedRoot)) {
            degradations.put("dependency_cache", "dependency_cache_untrusted_or_unavailable");
            return List.of();
        }

        Map<String, BuildModel.ClasspathEntry> entries = new LinkedHashMap<>();
        long totalBytes = 0;
        for (BuildModel.Dependency dependency : dependencies) {
            if (entries.size() >= maxJars) {
                degradations.put("dependency_cache_limit", "dependency_jar_count_limit");
                break;
            }
            Path jar = cacheRoot;
            for (String segment : dependency.groupId().split("\\.")) jar = jar.resolve(segment);
            jar = jar.resolve(dependency.artifactId()).resolve(dependency.version()).resolve(
                    dependency.artifactId() + "-" + dependency.version()
                            + (dependency.classifier().isBlank() ? "" : "-" + dependency.classifier()) + ".jar"
            ).normalize();
            if (!jar.startsWith(cacheRoot) || Files.isSymbolicLink(jar)
                    || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                degradations.put("dependency:" + dependency.coordinate(), "dependency_jar_missing");
                continue;
            }
            try {
                Path realJar = jar.toRealPath();
                if (!realJar.startsWith(trustedRoot)) {
                    degradations.put("dependency:" + dependency.coordinate(), "dependency_jar_outside_cache");
                    continue;
                }
                long size = Files.size(realJar);
                if (size < 1 || size > maxJarBytes || totalBytes > maxJarBytes - size) {
                    degradations.put("dependency:" + dependency.coordinate(), "dependency_jar_size_limit");
                    continue;
                }
                String digest = digest(realJar);
                if (!entries.containsKey(realJar.toString())) {
                    entries.put(realJar.toString(), new BuildModel.ClasspathEntry(
                            dependency.coordinate(), realJar.toString(), size, digest));
                    totalBytes += size;
                }
            } catch (IOException exception) {
                degradations.put("dependency:" + dependency.coordinate(), "dependency_jar_unreadable");
            }
        }
        return List.copyOf(entries.values());
    }

    private static Map<String, String> mavenProperties(Element project) {
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

    private static boolean isDirectProjectDependency(Element dependency) {
        Node parent = dependency.getParentNode();
        if (!(parent instanceof Element dependencies) || !dependencies.getTagName().equals("dependencies")) return false;
        return dependencies.getParentNode() instanceof Element project && project.getTagName().equals("project");
    }

    private static String resolveProperties(String value, Map<String, String> properties) {
        String resolved = value;
        for (int pass = 0; pass < 5; pass++) {
            Matcher matcher = PROPERTY.matcher(resolved);
            if (!matcher.find()) return resolved.trim();
            String replacement = properties.get(matcher.group(1));
            if (replacement == null) return "";
            resolved = resolved.substring(0, matcher.start()) + replacement + resolved.substring(matcher.end());
        }
        return "";
    }

    private static boolean safeCoordinate(String group, String artifact, String version, String classifier) {
        if (group.isBlank() || artifact.isBlank() || version.isBlank()) return false;
        if (!SAFE_SEGMENT.matcher(artifact).matches() || !SAFE_SEGMENT.matcher(version).matches()) return false;
        if (!classifier.isBlank() && !SAFE_SEGMENT.matcher(classifier).matches()) return false;
        for (String segment : group.split("\\.")) if (!SAFE_SEGMENT.matcher(segment).matches() || segment.equals("..")) return false;
        return !artifact.equals(".") && !artifact.equals("..")
                && !version.equals(".") && !version.equals("..")
                && !classifier.equals(".") && !classifier.equals("..");
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
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record Result(
            List<BuildModel.Dependency> dependencies,
            List<BuildModel.ClasspathEntry> classpath,
            Map<String, String> degradations
    ) {}
}
