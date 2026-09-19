package com.nocobase.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict encoding gate (Phase-21 Agent D).
 *
 * <p>Scans product source/config/docs for accidental mojibake — the UTF-8
 * replacement char (U+FFFD), Latin-1-misread-as-UTF-8 artifacts
 * ({@code Ã¢}/{@code Ã£}/{@code Ã©}/{@code Â§}…), and GBK-misread fragments
 * ({@code 鈥}/{@code 锛}/{@code 鈹}…) — and fails if any are present.
 *
 * <p>Planning/task documents ({@code NEXT_PHASE*}, {@code PHASE*},
 * {@code docs/superpowers/plans/}) are excluded because they intentionally
 * contain self-referential {@code rg} scan-command examples that list these
 * markers. Product surfaces scanned: {@code src/}, {@code scripts/},
 * {@code .github/}, and root product Markdown files.
 *
 * <p>A known-mojibake fixture proves the scanner actually catches corruption.
 */
class EncodingGateTest {

    /** Marker strings that indicate accidental mojibake. */
    private static final String[] MOJIBAKE_MARKERS = {
            "�",          // replacement char (EF BF BD)
            // Latin-1-misread-as-UTF-8 artifacts
            "Ã¢", "Ã£", "Ã©", "Ã¨", "Ã«", "Ã¹", "Ã¦", "Ã§", "Ã¡", "Ã­", "Ã³", "Ãº", "Ã±",
            "Â§", "Â¥", "â€™", "â€œ", "â€", "â€”", "â€“", "ï¿½",
            // GBK-misread fragments
            "鈥", "锛", "鈹", "鍚", "瀹", "涓", "骞", "绁", "濈", "煎", "叩",
            "鏃", "娴", "缁", "鐩", "鐨", "诲", "粨", "骇", "鍥", "淇", "敼", "鍙"
    };

    /** Product root Markdown files scanned (planning/task docs excluded). */
    private static final Set<String> SCANNED_ROOT_MDS = Set.of(
            "README.md", "BACKEND_OPERATION_GUIDE.md", "RELEASE_READINESS_CHECKLIST.md",
            "RELEASE_GATE_RESULT.md", "AUDIT_COVERAGE_MATRIX.md",
            "SYSTEM_MODULE_API_GAP_ANALYSIS.md"
    );

    private static final Path ROOT = Paths.get("");

    /**
     * Scan a single file's content for mojibake markers. Returns the list of
     * markers found (empty if clean). Package-visible for unit testing.
     */
    static List<String> findMojibake(String content) {
        List<String> hits = new ArrayList<>();
        if (content == null) {
            return hits;
        }
        for (String marker : MOJIBAKE_MARKERS) {
            if (content.contains(marker)) {
                hits.add(marker);
            }
        }
        return hits;
    }

    private boolean isScannedText(Path path) {
        String s = path.toString().replace('\\', '/');
        if (s.startsWith("src/") || s.startsWith("scripts/") || s.startsWith(".github/")) {
            return s.endsWith(".java") || s.endsWith(".yml") || s.endsWith(".yaml")
                    || s.endsWith(".ps1") || s.endsWith(".sh") || s.endsWith(".md")
                    || s.endsWith(".properties") || s.endsWith(".sql") || s.endsWith(".xml");
        }
        return false;
    }

    private boolean isPlanningDoc(Path path) {
        String s = path.toString().replace('\\', '/');
        String name = path.getFileName().toString();
        return name.startsWith("NEXT_PHASE") || name.startsWith("PHASE")
                || s.contains("docs/superpowers/plans/")
                || s.contains("docs/superpowers/")
                // This test file legitimately contains mojibake markers as test data.
                || s.endsWith("EncodingGateTest.java");
    }

    @Test
    @DisplayName("No accidental mojibake in product source, scripts, CI, or root docs")
    void noMojibakeInProductFiles() throws IOException {
        List<String> failures = new ArrayList<>();

        // Walk src/, scripts/, .github/
        for (String dir : new String[]{"src", "scripts", ".github"}) {
            Path base = ROOT.resolve(dir);
            if (!Files.isDirectory(base)) continue;
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isScannedText(file)) {
                        scanFile(file, failures);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Root product Markdown files
        for (String md : SCANNED_ROOT_MDS) {
            Path p = ROOT.resolve(md);
            if (Files.isRegularFile(p)) {
                scanFile(p, failures);
            }
        }

        assertTrue(failures.isEmpty(),
                "Accidental mojibake detected in product files:\n  "
                        + String.join("\n  ", failures));
    }

    private void scanFile(Path file, List<String> failures) {
        if (isPlanningDoc(file)) return;
        try {
            String content = Files.readString(file);
            List<String> hits = findMojibake(content);
            if (!hits.isEmpty()) {
                failures.add(file + " -> " + hits);
            }
        } catch (IOException e) {
            failures.add(file + " -> (read error: " + e.getMessage() + ")");
        }
    }

    @Test
    @DisplayName("Scanner catches known mojibake in a fixture file (negative control)")
    void scannerCatchesKnownMojibake(@TempDir Path tempDir) throws IOException {
        Path bad = tempDir.resolve("bad.md");
        // Latin-1-misread artifact Ã¢ + replacement char + GBK fragment 鈥
        Files.writeString(bad, "clean line\nbad: Ã¢ here � and 鈥 end\n");
        List<String> hits = findMojibake(Files.readString(bad));
        assertFalse(hits.isEmpty(), "scanner must detect known mojibake markers");
        // Should report at least the three distinct markers.
        assertTrue(hits.contains("Ã¢"), "must detect Latin-1 artifact: " + hits);
        assertTrue(hits.contains("�"), "must detect replacement char: " + hits);
        assertTrue(hits.contains("鈥"), "must detect GBK fragment: " + hits);
    }

    @Test
    @DisplayName("Clean UTF-8 text passes the scanner")
    void cleanTextPasses() {
        assertTrue(findMojibake("正常的中文文档\nEnglish text\n— em dash —\n(全角括号)\n").isEmpty(),
                "legitimate CJK and punctuation must not be flagged");
        assertTrue(findMojibake("plain ASCII only\n").isEmpty());
    }
}
