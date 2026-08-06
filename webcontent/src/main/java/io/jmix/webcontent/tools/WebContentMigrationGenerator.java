package io.jmix.webcontent.tools;

import io.jmix.webcontent.entity.WebContentType;
import io.jmix.webcontent.markdown.MarkdownConverter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Build-time tool that turns a directory of Markdown files into a Liquibase changelog seeding them as
 * {@link WebContentType#MD} {@code WEB_CONTENT} rows. Meant to be invoked from a consumer project's build
 * (a Gradle {@code JavaExec}), so that documentation kept as files in the repository shows up in the running
 * application without anyone pasting it into the admin UI.
 *
 * <h2>Why it renders HTML here</h2>
 * A Liquibase {@code insert} goes straight to the database and never fires {@code EntitySavingEvent}, so the
 * save-time renderer cannot fill in {@code CONTENTS}. This tool therefore renders it with the very same
 * {@link MarkdownConverter} the listener uses — build-time and save-time HTML are identical by construction,
 * and a doc re-saved in the admin UI does not suddenly change shape.
 *
 * <h2>Create and update in one shape</h2>
 * Each document yields one changeset whose id ends in a hash of its content, containing a
 * {@code delete}-then-{@code insert} on {@code (SLUG, LANG)} — an upsert. Consequences:
 * <ul>
 *   <li>First run inserts (the delete matches nothing); a later run after an edit produces a changeset with a
 *       new id, which Liquibase has not seen, so it replaces the row.</li>
 *   <li>An unchanged document regenerates a byte-identical changelog, so builds stay up to date and the
 *       working tree stays clean.</li>
 *   <li>Nothing already applied is ever rewritten, so no checksum ever breaks.</li>
 * </ul>
 * The row id is derived from {@code (slug, lang)}, so it is stable across regenerations.
 *
 * <h2>Files are the source of truth</h2>
 * The upsert overwrites the row wholesale. Editing a generated document in the admin UI works, but the next
 * build that sees a changed source file wins. Documents meant to be edited in the UI should not be generated.
 * <p>
 * Deletions propagate too: a leading <i>prune</i> changeset removes rows for documents no longer in the source
 * directory. Simply dropping a document's changeset would not do it — Liquibase never un-applies what it has
 * already run, so the row would linger. Pruning needs a slug prefix to scope its delete and is skipped without
 * one; see {@link #appendPruneChangeSet}.
 *
 * <h2>Usage</h2>
 * <pre>
 * WebContentMigrationGenerator &lt;sourceDir&gt; &lt;outputChangelog&gt; &lt;contentDir&gt; &lt;contentRefPrefix&gt;
 *                             [lang] [slugPrefix]
 * </pre>
 * Liquibase resolves {@code valueClobFile} <b>relative to the changelog file</b>, not from the classpath root,
 * so {@code contentRefPrefix} must be a path relative to {@code outputChangelog}'s own directory — typically
 * just the name of the sibling directory given as {@code contentDir}.
 * <p>
 * A consumer whose root changelog picks the changelog up with {@code includeAll} must restrict that scan to
 * XML (Liquibase 5: {@code endsWithFilter=".xml"}), otherwise it will try to parse the {@code .md}/{@code
 * .html} content files sitting next to the changelog as changelogs themselves.
 */
public final class WebContentMigrationGenerator {

    private static final String AUTHOR = "webcontent-docs";

    /**
     * Shape of the emitted changeset, folded into every changeset id. <b>Bump this whenever the generated XML
     * changes</b> for content that has not itself changed.
     * <p>
     * Without it, altering the generator would re-emit a changeset under an id Liquibase has already recorded
     * but with different XML — a checksum mismatch that hard-fails startup on every database that ran the old
     * form. Bumping mints fresh ids instead, so the new form simply applies. History stays valid: the old
     * changesets remain recorded and are never rewritten.
     */
    private static final String FORMAT_VERSION = "2";
    /** Databases whose UUID columns take the canonical dashed form; the rest get 32 hex chars. */
    private static final String DASHED_UUID_DBMS = "postgresql, mssql, hsqldb, h2";
    private static final String PLAIN_UUID_DBMS = "oracle, mysql, mariadb";

    private final MarkdownConverter markdownConverter = new MarkdownConverter();

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            throw new IllegalArgumentException("Usage: WebContentMigrationGenerator "
                    + "<sourceDir> <outputChangelog> <contentDir> <contentRefPrefix> [lang] [slugPrefix]");
        }
        Path sourceDir = Path.of(args[0]);
        Path outputChangelog = Path.of(args[1]);
        Path contentDir = Path.of(args[2]);
        String contentRefPrefix = trimSlashes(args[3]);
        String lang = args.length > 4 ? args[4] : "en";
        String slugPrefix = args.length > 5 ? args[5] : "";

        int count = new WebContentMigrationGenerator()
                .generate(sourceDir, outputChangelog, contentDir, contentRefPrefix, lang, slugPrefix);
        System.out.println("Generated " + outputChangelog + " from " + count + " Markdown file(s) in " + sourceDir);
    }

    /**
     * Writes the changelog and its content files.
     *
     * @return how many Markdown documents were included
     */
    public int generate(Path sourceDir, Path outputChangelog, Path contentDir,
                        String contentRefPrefix, String lang, String slugPrefix) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Markdown source directory does not exist: " + sourceDir.toAbsolutePath());
        }
        List<Path> markdownFiles = listMarkdownFiles(sourceDir);

        // Wipe the content directory so a renamed or deleted doc leaves no orphan file behind.
        deleteRecursively(contentDir);
        Files.createDirectories(contentDir);
        Files.createDirectories(outputChangelog.toAbsolutePath().getParent());

        StringBuilder xml = new StringBuilder();
        xml.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!--
                  GENERATED FILE - DO NOT EDIT.
                  Produced by io.jmix.webcontent.tools.WebContentMigrationGenerator from Markdown sources.
                  Edit the Markdown and re-run the generating Gradle task instead.
                -->
                <databaseChangeLog
                        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                """);

        List<String> slugs = markdownFiles.stream()
                .map(file -> slugPrefix + stripExtension(file.getFileName().toString()))
                .toList();

        // Prune first, then upsert: "remove what no longer belongs, publish what does".
        appendPruneChangeSet(xml, slugs, lang, slugPrefix);

        for (Path markdownFile : markdownFiles) {
            String markdown = Files.readString(markdownFile, StandardCharsets.UTF_8);
            String html = markdownConverter.toHtml(markdown);
            String slug = slugPrefix + stripExtension(markdownFile.getFileName().toString());
            String title = extractTitle(markdown, slug);
            String hash = shortHash(markdown);

            String baseName = slug + "." + lang;
            Files.writeString(contentDir.resolve(baseName + ".md"), markdown, StandardCharsets.UTF_8);
            Files.writeString(contentDir.resolve(baseName + ".html"), html, StandardCharsets.UTF_8);

            String sourceRef = contentRefPrefix + "/" + baseName + ".md";
            String contentsRef = contentRefPrefix + "/" + baseName + ".html";
            appendChangeSet(xml, slug, lang, title, hash, sourceRef, contentsRef);
        }

        xml.append("\n</databaseChangeLog>\n");
        Files.writeString(outputChangelog, xml.toString(), StandardCharsets.UTF_8);
        return markdownFiles.size();
    }

    /**
     * Deletes rows for documents that no longer exist in the source directory, so a deleted (or renamed)
     * {@code .md} disappears from the application. Dropping its changeset is not enough on its own: Liquibase
     * never un-applies what it has already run, so without this the row would linger forever on every database
     * that saw the document.
     * <p>
     * <b>Requires a slug prefix.</b> The delete is scoped to {@code SLUG LIKE '<prefix>%'}; with no prefix that
     * degenerates to "every row not generated from this directory", which would wipe hand-authored content.
     * Pruning is therefore skipped — loudly — when no prefix is configured.
     * <p>
     * The changeset id hashes the slug list, so it re-runs exactly when the set of documents changes and is a
     * no-op the rest of the time.
     */
    private void appendPruneChangeSet(StringBuilder xml, List<String> slugs, String lang, String slugPrefix) {
        if (slugPrefix.isEmpty()) {
            xml.append("\n    <!-- No slug prefix configured, so stale rows are NOT pruned: an unscoped delete\n")
                    .append("         could remove hand-authored content. Deleting a source file will leave its\n")
                    .append("         row behind. Configure a slugPrefix to enable pruning. -->\n");
            System.out.println("WARNING: no slugPrefix configured -- stale rows will not be pruned. "
                    + "Deleting a Markdown file will leave its row in WEB_CONTENT.");
            return;
        }
        String keepList = slugs.isEmpty()
                ? ""
                : slugs.stream().map(slug -> "'" + sqlEsc(slug) + "'").collect(Collectors.joining(", "));
        String notInClause = keepList.isEmpty() ? "" : " AND SLUG NOT IN (" + keepList + ")";

        xml.append("\n    <changeSet id=\"prune-").append(esc(slugPrefix)).append(esc(lang))
                .append('-').append(shortHash(String.join("\n", slugs)))
                .append("\" author=\"").append(AUTHOR).append("\">\n");
        xml.append("        <comment>Remove documents no longer present in the source directory</comment>\n");
        xml.append("        <delete tableName=\"WEB_CONTENT\">\n")
                .append("            <where>SLUG LIKE '").append(sqlEsc(slugPrefix)).append("%' AND LANG = '")
                .append(sqlEsc(lang)).append('\'').append(notInClause).append("</where>\n")
                .append("        </delete>\n");
        xml.append("    </changeSet>\n");
    }

    private void appendChangeSet(StringBuilder xml, String slug, String lang, String title,
                                 String hash, String sourceRef, String contentsRef) {
        UUID id = contentId(slug, lang);
        xml.append("\n    <changeSet id=\"").append(esc(slug)).append('-').append(esc(lang))
                .append('-').append(hash).append("\" author=\"").append(AUTHOR).append("\">\n");
        xml.append("        <comment>Documentation '").append(esc(title)).append("' (")
                .append(esc(slug)).append(", ").append(esc(lang)).append(")</comment>\n");
        xml.append("        <delete tableName=\"WEB_CONTENT\">\n")
                .append("            <where>SLUG = '").append(sqlEsc(slug))
                .append("' AND LANG = '").append(sqlEsc(lang)).append("'</where>\n")
                .append("        </delete>\n");
        appendInsert(xml, DASHED_UUID_DBMS, id.toString(), slug, lang, title);
        appendInsert(xml, PLAIN_UUID_DBMS, id.toString().replace("-", ""), slug, lang, title);
        appendContentUpdate(xml, slug, lang, sourceRef, contentsRef);
        xml.append("    </changeSet>\n");
    }

    /** Scalar columns only — see {@link #appendContentUpdate} for why the CLOBs are not in here. */
    private void appendInsert(StringBuilder xml, String dbms, String id, String slug, String lang,
                              String title) {
        xml.append("        <insert tableName=\"WEB_CONTENT\" dbms=\"").append(dbms).append("\">\n");
        xml.append("            <column name=\"ID\" value=\"").append(id).append("\"/>\n");
        xml.append("            <column name=\"VERSION\" valueNumeric=\"1\"/>\n");
        xml.append("            <column name=\"TITLE\" value=\"").append(esc(title)).append("\"/>\n");
        xml.append("            <column name=\"SLUG\" value=\"").append(esc(slug)).append("\"/>\n");
        xml.append("            <column name=\"LANG\" value=\"").append(esc(lang)).append("\"/>\n");
        xml.append("            <column name=\"TYPE\" value=\"")
                .append(WebContentType.MD.getId()).append("\"/>\n");
        xml.append("        </insert>\n");
    }

    /**
     * Fills the CLOB columns in a separate {@code update}, keyed on {@code (SLUG, LANG)}.
     * <p>
     * They cannot go in the {@code insert}: a Liquibase insert that carries a {@code valueClobFile} is executed
     * as a {@code PreparedStatement}, which binds every column as a parameter — and binding the id as a string
     * fails on a {@code uuid} column ("column \"id\" is of type uuid but expression is of type character
     * varying" on PostgreSQL). Keeping the insert clob-free leaves it on the plain-SQL path, where a UUID
     * literal is fine, and the update binds no id at all.
     */
    private void appendContentUpdate(StringBuilder xml, String slug, String lang,
                                     String sourceRef, String contentsRef) {
        xml.append("        <update tableName=\"WEB_CONTENT\">\n");
        xml.append("            <column name=\"SOURCE\" valueClobFile=\"")
                .append(esc(sourceRef)).append("\" encoding=\"UTF-8\"/>\n");
        xml.append("            <column name=\"CONTENTS\" valueClobFile=\"")
                .append(esc(contentsRef)).append("\" encoding=\"UTF-8\"/>\n");
        xml.append("            <where>SLUG = '").append(sqlEsc(slug))
                .append("' AND LANG = '").append(sqlEsc(lang)).append("'</where>\n");
        xml.append("        </update>\n");
    }

    /**
     * Stable row id for a document, so regenerating targets the same row instead of accumulating duplicates.
     * Namespaced to keep these ids away from any other name-based UUIDs a consumer might mint.
     */
    static UUID contentId(String slug, String lang) {
        return UUID.nameUUIDFromBytes(
                ("io.jmix.webcontent:" + slug + ":" + lang).getBytes(StandardCharsets.UTF_8));
    }

    /** First ATX H1 in the document, falling back to the slug when it has none. */
    static String extractTitle(String markdown, String fallback) {
        for (String line : markdown.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).strip();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        }
        return fallback;
    }

    /**
     * 12 hex chars of SHA-256 over the content <i>and</i> {@link #FORMAT_VERSION} — enough to make an
     * accidental collision a non-issue for a docs set, and changing when either the document or the emitted
     * changeset shape changes.
     */
    static String shortHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((FORMAT_VERSION + " " + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static List<Path> listMarkdownFiles(Path sourceDir) throws IOException {
        try (Stream<Path> files = Files.list(sourceDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    // Sorted so the generated changelog is reproducible across filesystems.
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> ordered = new ArrayList<>(paths.toList());
            ordered.sort(Comparator.reverseOrder());
            for (Path path : ordered) {
                Files.delete(path);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Escapes for an XML attribute value. */
    private static String esc(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Escapes a single-quoted SQL literal used inside a Liquibase {@code where} clause. */
    private static String sqlEsc(String value) {
        return esc(value.replace("'", "''"));
    }
}
