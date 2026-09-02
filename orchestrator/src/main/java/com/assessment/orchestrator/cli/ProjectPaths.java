package com.assessment.orchestrator.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the sibling url-shortener module regardless of which directory the CLI was launched from. */
final class ProjectPaths {

    private ProjectPaths() {
    }

    static Path resolveProjectRoot(Path override) {
        if (override != null) {
            return override.toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("url-shortener").resolve("pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not auto-locate the project root (a directory containing url-shortener/pom.xml) "
                        + "starting from " + cwd + ". Pass --project-root explicitly.");
    }

    static Path urlShortenerModuleDir(Path projectRoot) {
        return projectRoot.resolve("url-shortener");
    }

    static Path urlShortenerSrcMainJava(Path projectRoot) {
        return urlShortenerModuleDir(projectRoot).resolve("src/main/java");
    }

    static Path runsOutputDir(Path projectRoot) {
        return projectRoot.resolve("orchestrator").resolve("runs");
    }

    static Path changelogFile(Path projectRoot) {
        return projectRoot.resolve("docs").resolve("CHANGELOG.md");
    }
}
