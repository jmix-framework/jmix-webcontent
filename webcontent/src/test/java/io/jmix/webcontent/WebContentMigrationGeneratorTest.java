package io.jmix.webcontent;

import io.jmix.webcontent.tools.WebContentMigrationGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebContentMigrationGeneratorTest {

    private static final String PREFIX = "com/example/webcontent/docs";

    @TempDir
    Path tempDir;

    @Test
    void generates_an_upsert_changeset_per_document(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("source-triggers.md"), "# Source triggers\n\nBody.\n");

        String changelog = generate(docs);

        assertTrue(changelog.contains("<delete tableName=\"WEB_CONTENT\">"), changelog);
        assertTrue(changelog.contains("SLUG = 'source-triggers' AND LANG = 'en'"), changelog);
        assertTrue(changelog.contains("<column name=\"TYPE\" value=\"MD\"/>"), changelog);
        // Both UUID shapes, so the migration is not Postgres-only.
        assertTrue(changelog.contains("dbms=\"postgresql, mssql, hsqldb, h2\""), changelog);
        assertTrue(changelog.contains("dbms=\"oracle, mysql, mariadb\""), changelog);
        assertTrue(changelog.contains(PREFIX + "/source-triggers.en.md"), changelog);
        assertTrue(changelog.contains(PREFIX + "/source-triggers.en.html"), changelog);
    }

    /**
     * A clob file in the insert forces Liquibase onto the PreparedStatement path, where binding the id as a
     * string breaks on a uuid column (PostgreSQL). The CLOBs must live in a separate, id-free update.
     */
    @Test
    void keeps_clob_columns_out_of_the_insert(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("doc.md"), "# Doc\n");

        String changelog = generate(docs);

        String insertBlock = between(changelog, "<insert ", "</insert>");
        assertFalse(insertBlock.contains("valueClobFile"), insertBlock);
        assertFalse(insertBlock.contains("SOURCE"), insertBlock);

        String updateBlock = between(changelog, "<update ", "</update>");
        assertTrue(updateBlock.contains("name=\"SOURCE\" valueClobFile"), updateBlock);
        assertTrue(updateBlock.contains("name=\"CONTENTS\" valueClobFile"), updateBlock);
        assertFalse(updateBlock.contains("name=\"ID\""), updateBlock);
        assertTrue(updateBlock.contains("SLUG = 'doc' AND LANG = 'en'"), updateBlock);
    }

    @Test
    void writes_markdown_source_and_rendered_html_side_by_side(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("guide.md"), "# Guide\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n");

        generate(docs);

        Path contentDir = tempDir.resolve("content");
        assertEquals("# Guide\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n",
                Files.readString(contentDir.resolve("guide.en.md"), StandardCharsets.UTF_8));
        String html = Files.readString(contentDir.resolve("guide.en.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<table>"), html);
    }

    @Test
    void takes_the_title_from_the_first_h1_and_falls_back_to_the_slug(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("titled.md"), "# Real Title\n\nBody\n");
        Files.writeString(docs.resolve("untitled.md"), "Just a paragraph\n");

        String changelog = generate(docs);

        assertTrue(changelog.contains("Documentation 'Real Title' (titled, en)"), changelog);
        assertTrue(changelog.contains("Documentation 'untitled' (untitled, en)"), changelog);
    }

    @Test
    void regenerating_unchanged_docs_produces_identical_output(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("stable.md"), "# Stable\n\nBody.\n");

        String first = generate(docs);
        String second = generate(docs);

        assertEquals(first, second, "unchanged docs must not churn the changelog");
    }

    @Test
    void editing_a_doc_yields_a_new_changeset_id_so_liquibase_re_runs_it(@TempDir Path docs) throws IOException {
        Path doc = docs.resolve("evolving.md");
        Files.writeString(doc, "# Evolving\n\nFirst revision.\n");
        String before = changeSetId(generate(docs));

        Files.writeString(doc, "# Evolving\n\nSecond revision.\n");
        String after = changeSetId(generate(docs));

        assertNotEquals(before, after);
        assertTrue(before.startsWith("evolving-en-"), before);
        assertTrue(after.startsWith("evolving-en-"), after);
    }

    /**
     * The changeset id must depend on the emitted XML shape as well as the content, so that changing the
     * generator cannot re-emit different XML under an id Liquibase has already checksummed.
     */
    @Test
    void changeset_id_depends_on_the_output_format_version(@TempDir Path docs) throws IOException {
        String markdown = "# Doc\n";
        Files.writeString(docs.resolve("doc.md"), markdown);

        String id = changeSetId(generate(docs));

        // Hash of the document text alone. If the id ended with this, the format version would not be
        // participating and changing the generator could collide with an already-checksummed changeset.
        String contentOnlyHash = sha256Prefix(markdown);
        assertTrue(id.startsWith("doc-en-"), id);
        assertFalse(id.endsWith(contentOnlyHash), id + " must not be keyed on content alone");
    }

    private static String sha256Prefix(String content) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Test
    void row_id_is_stable_across_regenerations_even_as_content_changes(@TempDir Path docs) throws IOException {
        Path doc = docs.resolve("stable-id.md");
        Files.writeString(doc, "# One\n");
        String firstId = insertedId(generate(docs));

        Files.writeString(doc, "# Two, entirely different\n");
        String secondId = insertedId(generate(docs));

        assertEquals(firstId, secondId);
    }

    /**
     * Dropping a deleted document's changeset is not enough — Liquibase never un-applies an applied changeset,
     * so a prune step has to remove the row explicitly.
     */
    @Test
    void prunes_rows_for_documents_that_no_longer_exist(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("kept.md"), "# Kept\n");
        Files.writeString(docs.resolve("also-kept.md"), "# Also kept\n");

        String changelog = generateWithPrefix(docs, "docs-");

        String prune = between(changelog, "<delete ", "</delete>");
        assertTrue(prune.contains("SLUG LIKE 'docs-%'"), prune);
        assertTrue(prune.contains("LANG = 'en'"), prune);
        assertTrue(prune.contains("SLUG NOT IN ('docs-also-kept', 'docs-kept')"), prune);
    }

    @Test
    void prune_changeset_id_changes_only_when_the_document_set_changes(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("a.md"), "# A\n");
        String first = changeSetId(generateWithPrefix(docs, "docs-"));

        // Editing a document leaves the set intact, so the prune step must not re-run.
        Files.writeString(docs.resolve("a.md"), "# A, revised\n");
        String afterEdit = changeSetId(generateWithPrefix(docs, "docs-"));

        // Adding one changes the set, so it must.
        Files.writeString(docs.resolve("b.md"), "# B\n");
        String afterAdd = changeSetId(generateWithPrefix(docs, "docs-"));

        assertTrue(first.startsWith("prune-docs-en-"), first);
        assertEquals(first, afterEdit);
        assertNotEquals(first, afterAdd);
    }

    /** An unscoped prune would delete hand-authored content, so it must not be emitted at all. */
    @Test
    void does_not_prune_without_a_slug_prefix(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("doc.md"), "# Doc\n");

        String changelog = generate(docs);

        assertFalse(changelog.contains("SLUG LIKE"), changelog);
        assertFalse(changelog.contains("prune-"), changelog);
        assertTrue(changelog.contains("stale rows are NOT pruned"), changelog);
    }

    @Test
    void prunes_everything_under_the_prefix_when_the_source_directory_is_empty(@TempDir Path docs)
            throws IOException {
        String changelog = generateWithPrefix(docs, "docs-");

        String prune = between(changelog, "<delete ", "</delete>");
        assertTrue(prune.contains("SLUG LIKE 'docs-%'"), prune);
        assertFalse(prune.contains("NOT IN"), prune);
    }

    @Test
    void removing_a_doc_removes_its_generated_content_files(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("keep.md"), "# Keep\n");
        Path gone = docs.resolve("gone.md");
        Files.writeString(gone, "# Gone\n");
        generate(docs);
        assertTrue(Files.exists(tempDir.resolve("content").resolve("gone.en.html")));

        Files.delete(gone);
        String changelog = generate(docs);

        assertFalse(Files.exists(tempDir.resolve("content").resolve("gone.en.html")));
        assertFalse(changelog.contains("gone"), changelog);
        assertTrue(Files.exists(tempDir.resolve("content").resolve("keep.en.html")));
    }

    @Test
    void applies_a_slug_prefix_when_given(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("triggers.md"), "# Triggers\n");

        int count = new WebContentMigrationGenerator().generate(
                docs, tempDir.resolve("changelog.xml"), tempDir.resolve("content"), PREFIX, "en", "docs-");

        assertEquals(1, count);
        String changelog = Files.readString(tempDir.resolve("changelog.xml"), StandardCharsets.UTF_8);
        assertTrue(changelog.contains("SLUG = 'docs-triggers'"), changelog);
    }

    private String generate(Path docs) throws IOException {
        return generateWithPrefix(docs, "");
    }

    private String generateWithPrefix(Path docs, String slugPrefix) throws IOException {
        new WebContentMigrationGenerator().generate(
                docs, tempDir.resolve("changelog.xml"), tempDir.resolve("content"), PREFIX, "en", slugPrefix);
        return Files.readString(tempDir.resolve("changelog.xml"), StandardCharsets.UTF_8);
    }

    private static String changeSetId(String changelog) {
        return between(changelog, "<changeSet id=\"", "\"");
    }

    private static String insertedId(String changelog) {
        return between(changelog, "<column name=\"ID\" value=\"", "\"");
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start) + start.length();
        return text.substring(from, text.indexOf(end, from));
    }
}
