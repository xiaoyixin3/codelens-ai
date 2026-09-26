package ai.codelens.semantic;

import java.util.List;
import java.util.Map;

public record BuildModel(
        BuildSystem system,
        List<Module> modules,
        String hash,
        Map<String, String> degradations
) {
    public BuildModel {
        modules = List.copyOf(modules);
        degradations = Map.copyOf(degradations);
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
}

