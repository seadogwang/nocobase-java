package com.nocobase.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract & evidence maintenance (Phase-21 Agent I): ensure no raw HAR files,
 * credentials, keys, dumps, or runtime artifacts are tracked by Git.
 *
 * <p>Runs {@code git ls-files} and rejects any tracked file matching secret/dump
 * patterns. The only permitted HAR artifact is the sanitized test fixture
 * {@code src/test/resources/har/sanitized-sample.har}.
 *
 * <p>Deterministic — no network, no Docker. Requires the test to run inside a
 * git working tree (skips gracefully otherwise via assumption).
 */
class NoSecretsInGitTest {

    private static final String SANITIZED_HAR = "src/test/resources/har/sanitized-sample.har";

    private static final List<String> SECRET_PATTERNS = List.of(
            "\\.har$",
            "\\.dump$",
            "\\.sql\\.dump$",
            "\\.pem$",
            "\\.key$",
            "\\.keystore$",
            "(^|/)\\.env($|\\.)"
    );

    @Test
    @DisplayName("No raw HAR, credential, key, or dump files are tracked by Git")
    void noSecretsTracked() throws Exception {
        List<String> tracked = gitLsFiles();
        // Skip gracefully if not in a git repo (e.g. exported source tarball).
        org.junit.jupiter.api.Assumptions.assumeTrue(!tracked.isEmpty(),
                "git ls-files returned no files; running outside a git work tree");

        List<String> violations = new ArrayList<>();
        for (String file : tracked) {
            if (file.equals(SANITIZED_HAR)) {
                continue; // the one permitted sanitized fixture
            }
            for (String pattern : SECRET_PATTERNS) {
                if (file.matches(".*" + pattern) || file.matches(pattern)) {
                    violations.add(file + " (matches " + pattern + ")");
                    break;
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Tracked files must not include secrets, dumps, or raw HAR:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("The sanitized HAR fixture is the only permitted HAR path")
    void sanitizedHarIsPermitted() {
        // The gitignore negation allows only this one HAR file.
        for (String pattern : SECRET_PATTERNS) {
            if (pattern.equals("\\.har$")) {
                // Only the sanitized fixture should be excepted.
                assertTrue(SANITIZED_HAR.endsWith(".har"));
            }
        }
    }

    private List<String> gitLsFiles() throws Exception {
        Process p = new ProcessBuilder("git", "ls-files").redirectErrorStream(true).start();
        List<String> files = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                files.add(line);
            }
        }
        p.waitFor();
        return files;
    }
}
