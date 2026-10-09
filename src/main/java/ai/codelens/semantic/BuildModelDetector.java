package ai.codelens.semantic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class BuildModelDetector {
    private static final Set<String> DESCRIPTORS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "target", "build", "node_modules", "dist", "out"
    );
    private static final long MAX_DESCRIPTOR_BYTES = 2L * 1024 * 1024;
    private final DeclaredDependencyResolver dependencies;
    private final DeclaredSourceLayoutResolver sourceLayouts = new DeclaredSourceLayoutResolver();

    public BuildModelDetector() {
        this(null, 512, 128L * 1024 * 1024);
    }

    public BuildModelDetector(Path dependencyCacheRoot, int maxDependencyJars, long maxDependencyJarBytes) {
        if (maxDependencyJars < 1 || maxDependencyJarBytes < 1) {
            throw new IllegalArgumentException("Dependency limits must be positive");
        }
        dependencies = new DeclaredDependencyResolver(dependencyCacheRoot, maxDependencyJars, maxDependencyJarBytes);
    }

    public BuildModel detect(Path repositoryRoot) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Repository root does not exist: " + root);

        List<Path> descriptors = new ArrayList<>();
        Map<String, String> degradations = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !isExcluded(root, path))
                    .filter(path -> DESCRIPTORS.contains(path.getFileName().toString()))
                    .forEach(descriptors::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect build descriptors", exception);
        }
        descriptors.sort(Comparator.comparing(path -> relative(root, path)));

        boolean maven = descriptors.stream().anyMatch(path -> path.getFileName().toString().equals("pom.xml"));
        boolean gradle = descriptors.stream().anyMatch(path -> path.getFileName().toString().startsWith("build.gradle")
                || path.getFileName().toString().startsWith("settings.gradle"));
        BuildModel.BuildSystem system = maven && gradle ? BuildModel.BuildSystem.MIXED
                : maven ? BuildModel.BuildSystem.MAVEN
                : gradle ? BuildModel.BuildSystem.GRADLE
                : BuildModel.BuildSystem.UNKNOWN;

        Set<Path> moduleRoots = new LinkedHashSet<>();
        descriptors.stream()
                .filter(path -> path.getFileName().toString().equals("pom.xml")
                        || path.getFileName().toString().startsWith("build.gradle"))
                .map(Path::getParent)
                .forEach(moduleRoots::add);
        if (moduleRoots.isEmpty()) moduleRoots.add(root);

        List<BuildModel.Module> modules = new ArrayList<>();
        for (Path moduleRoot : moduleRoots.stream().sorted(Comparator.comparing(path -> relative(root, path))).toList()) {
            List<Path> owned = descriptors.stream().filter(path -> path.getParent().equals(moduleRoot)).toList();
            DeclaredSourceLayoutResolver.Result layout = sourceLayouts.resolve(root, moduleRoot, owned);
            degradations.putAll(layout.degradations());
            modules.add(module(root, moduleRoot, owned, layout));
        }
        boolean hasJavaSources = modules.stream().anyMatch(module -> !module.mainSourceRoots().isEmpty() || !module.testSourceRoots().isEmpty());
        if (!hasJavaSources) degradations.put("java_sources", "no_declared_or_conventional_java_source_roots");
        if (system == BuildModel.BuildSystem.UNKNOWN) degradations.put("build_system", "no_maven_or_gradle_descriptor");

        DeclaredDependencyResolver.Result resolved = dependencies.resolve(root, descriptors);
        degradations.putAll(resolved.degradations());
        return new BuildModel(system, modules, resolved.dependencies(), resolved.classpath(),
                hash(root, descriptors, resolved.dependencies(), resolved.classpath(), degradations), degradations);
    }

    private static BuildModel.Module module(
            Path repositoryRoot,
            Path moduleRoot,
            List<Path> descriptors,
            DeclaredSourceLayoutResolver.Result layout
    ) {
        String root = relative(repositoryRoot, moduleRoot);
        String name = root.isBlank() ? repositoryRoot.getFileName().toString() : moduleRoot.getFileName().toString();
        List<String> ownedDescriptors = descriptors.stream()
                .map(path -> relative(repositoryRoot, path))
                .toList();
        return new BuildModel.Module(name, root, ownedDescriptors, layout.mainSourceRoots(), layout.testSourceRoots());
    }

    private static boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) if (EXCLUDED_DIRECTORIES.contains(part.toString())) return true;
        return false;
    }

    private static String relative(Path root, Path path) {
        String value = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return value.equals(".") ? "" : value;
    }

    private static String hash(
            Path root,
            List<Path> descriptors,
            List<BuildModel.Dependency> dependencies,
            List<BuildModel.ClasspathEntry> classpath,
            Map<String, String> degradations
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path descriptor : descriptors) {
                digest.update(relative(root, descriptor).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                long size = Files.size(descriptor);
                if (size > MAX_DESCRIPTOR_BYTES) {
                    digest.update(("oversize:" + size).getBytes(StandardCharsets.UTF_8));
                    degradations.put(relative(root, descriptor), "descriptor_too_large");
                } else {
                    digest.update(Files.readAllBytes(descriptor));
                }
                digest.update((byte) 0);
            }
            if (descriptors.isEmpty()) digest.update("no-build-descriptor".getBytes(StandardCharsets.UTF_8));
            for (BuildModel.Dependency dependency : dependencies) {
                digest.update((dependency.coordinate() + ":" + dependency.scope()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            for (BuildModel.ClasspathEntry entry : classpath) {
                digest.update((entry.coordinate() + ":" + entry.size() + ":" + entry.sha256()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            degradations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update((entry.getKey() + "=" + entry.getValue()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash build model", exception);
        }
    }
}
