package com.example.campusmarket.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SingleExperimentTreeTest {

    @Test
    void branchHasOnlyOneExperimentDirectory() throws Exception {
        Path repoRoot = gitTopLevel();
        List<String> tracked = trackedFiles(repoRoot);

        assertThat(tracked).allMatch(path ->
                path.equals(".gitignore") || path.startsWith("labs/09-ai-campus-support/"));
    }

    private static Path gitTopLevel() throws IOException, InterruptedException {
        Process rootCommand = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                .redirectErrorStream(true)
                .start();
        String root = new String(rootCommand.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(rootCommand.waitFor()).isZero();
        return Path.of(root);
    }

    private static List<String> trackedFiles(Path repoRoot) throws IOException, InterruptedException {
        Process trackedCommand = new ProcessBuilder("git", "ls-files")
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        List<String> files = new String(trackedCommand.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .toList();
        assertThat(trackedCommand.waitFor()).isZero();
        return files;
    }
}
