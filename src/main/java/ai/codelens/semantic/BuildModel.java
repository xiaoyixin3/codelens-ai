package ai.codelens.semantic;

import java.util.List;
import java.util.Map;

public record BuildModel(
        BuildSystem system,
        List<Module> modules,
        List<Dependency> dependencies,
        List<ClasspathEntry> dependencyClasspath,
        String hash,
        Map<String, String> degradations
) {
    public BuildModel {
        modules = List.copyOf(modules);
        dependencies = List.copyOf(dependencies);
        dependencyClasspath = List.copyOf(dependencyClasspath);
        degradations = Map.copyOf(degradations);
    }

    public BuildModel(BuildSystem system, List<Module> modules, String hash, Map<String, String> degradations) {
        this(system, modules, List.of(), List.of(), hash, degradations);
    }

    public enum BuildSystem { MAVEN, GRADLE, MIXED, UNKNOWN }

    public record Module(
            String name,
            String root,
            List<String> descriptors,
            List<String> mainSourceRoots,
            List<String> testSourceRoots
    ) {
        public Module {
            descriptors = List.copyOf(descriptors);
            mainSourceRoots = List.copyOf(mainSourceRoots);
            testSourceRoots = List.copyOf(testSourceRoots);
        }
    }

    public record Dependency(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String scope,
            String descriptor
    ) {
        public String coordinate() {
            return groupId + ":" + artifactId + ":" + version
                    + (classifier.isBlank() ? "" : ":" + classifier);
        }
    }

    public record ClasspathEntry(String coordinate, String path, long size, String sha256) {}
}

