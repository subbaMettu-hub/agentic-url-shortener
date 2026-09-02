package com.assessment.orchestrator.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ArtifactWriter {

    private ArtifactWriter() {
    }

    public static Path write(Path dir, String filename, String content) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
