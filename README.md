# Jmix WebContent Addon

Allows to use content stored in database and editable from the admin area

# Installation

## From JitPack (no local build)

Add the JitPack repository and depend on a released tag:

```groovy
repositories {
    maven {
        url = 'https://jitpack.io'
        content {
            // JitPack proxies all of Maven Central; without this, every unresolved artifact in your build
            // would be retried against it.
            includeGroup 'com.github.jmix-framework.jmix-webcontent'
        }
    }
}

dependencies {
    implementation 'com.github.jmix-framework.jmix-webcontent:webcontent-starter:0.1.0'
}
```

The group is `com.github.<owner>.<repo>` — how JitPack namespaces a GitHub project — and the version is the
git tag. Releases are tagged in this repository; `0.1.0` is the first with Markdown support.

## From a local build

1. Checkout this repo
2. build and publish artifacts to your Maven

```
./gradlew publishToMavenLocal
```
3. Select Install Manually from Jmix Addon Manager and insert addon's artifactId 

```
io.jmix.webcontent:webcontent-starter:0.1.0
```

## Releasing

`jitpack.yml` pins JDK 21 (the add-on targets Java 21) and publishes under JitPack's own group via
`-PpublishGroup`, so the starter's POM reference to the `webcontent` module resolves from the JitPack
repository. Keep `version` in `build.gradle` equal to the tag you push, then request the new version once to
trigger the build:

```
curl https://jitpack.io/com/github/jmix-framework/jmix-webcontent/webcontent-starter/<tag>/webcontent-starter-<tag>.pom
```

# Content types

Every item declares a `type`:

| Type | Body is authored as | `contents` holds |
| --- | --- | --- |
| `HTML` (default) | HTML, typed into the text area | exactly what you typed |
| `MD` | Markdown, typed into the Jmix Markdown editor (`source`) | HTML rendered from `source` on every save |

`contents` is **always servable HTML**, whichever type is used — that is the contract consumers rely on, so
`findBySlug(...).getContents()` needs no Markdown parser at request time. For an `MD` item the rendering happens
in an `EntitySavingEvent` listener, so it applies to every write path (admin UI, `DataManager`, REST, tests),
and `source` keeps the Markdown so it can be edited again.

Rendering uses commonmark with GFM tables, autolinks and heading anchors enabled. Raw HTML in Markdown source
is **escaped**, not passed through: content is admin-authored, but it ends up in a page via `innerHTML`, and
escaping stops a stored document from becoming a scripting vector.

Items created before the type column existed are migrated to `HTML`.

# Usage 

## Code

By default web content is taken by user selected locale fallbacking to 'en'.
Consider you have div on view with id "instructions" and web content with
the same slug field value:

```
WebContent instructionWebContent = webContentService.findBySlug("instructions");
instructions.getElement().setProperty("innerHTML", instructionWebContent.getContents());
```

## Migrations

To have contents installed automatically, use liquibase changesets as follows:

1. Create new migration changeset
2. Add changeset contents

```xml
<changeSet id="1" author="xxx" context="!cuba">

    <insert tableName="WEB_CONTENT" dbms="postgresql, mssql, hsqldb">
        <column name="ID" value="6c9e420a-2b7a-4c42-8654-a9027ee14083"/>
        <column name="VERSION" value="1"/>
        <column name="TITLE" value="Инструкция"/>
        <column name="SLUG" value="instructions"/>
        <column name="LANG" value="ru"/>
        <column name="CONTENTS" valueClobFile="instructions-ru.html"/>
    </insert>

    <insert tableName="WEB_CONTENT" dbms="oracle, mysql, mariadb">
        <column name="ID" value="6c9e420a2b7a4c428654a9027ee14083"/>
        <column name="VERSION" value="1"/>
        <column name="TITLE" value="Инструкция"/>
        <column name="SLUG" value="instructions"/>
        <column name="LANG" value="ru"/>
        <column name="CONTENTS" valueClobFile="instructions-ru.html"/>
    </insert>
</changeSet>
```

3. Place your htmls with contents aside the changeset

## Generating migrations from Markdown files

To publish a directory of Markdown files (project documentation, for example) as `MD` content, run
`io.jmix.webcontent.tools.WebContentMigrationGenerator` from your build instead of writing changesets by hand:

```
WebContentMigrationGenerator <sourceDir> <outputChangelog> <contentDir> <contentRefPrefix> [lang] [slugPrefix]
```

A Gradle wiring, hooked so a plain `./gradlew build` keeps the migration current:

```groovy
def changelogDir = 'src/main/resources/com/company/app/liquibase/changelog'

tasks.register('generateDocsMigration', JavaExec) {
    // compileClasspath, not runtimeClasspath: the latter depends on processResources (via classes), and this
    // task must run before it. compileClasspath already carries this add-on and commonmark.
    classpath = sourceSets.main.compileClasspath
    mainClass = 'io.jmix.webcontent.tools.WebContentMigrationGenerator'
    args = [
            file('docs').absolutePath,
            file("$changelogDir/100-docs-webcontent.xml").absolutePath,
            file("$changelogDir/100-docs-webcontent").absolutePath,
            '100-docs-webcontent',
            'en',
            'docs-',
    ]
    inputs.dir('docs')
    outputs.file("$changelogDir/100-docs-webcontent.xml")
    outputs.dir("$changelogDir/100-docs-webcontent")
}

tasks.named('processResources') { dependsOn 'generateDocsMigration' }
```

What it produces, and why:

- **One changeset per document, id suffixed with a hash of the content**, containing a `delete` then an
  `insert` on `(SLUG, LANG)` — an upsert. An edited document gets a changeset id Liquibase has not seen, so it
  re-applies; an unchanged one regenerates byte-identically, so builds stay up to date and git stays clean.
  Nothing already applied is ever rewritten, so checksums never break.
- **The row id is derived from `(slug, lang)`**, so it is stable across regenerations.
- **HTML is rendered at build time** with the same converter the save listener uses, because a Liquibase
  `insert` never fires `EntitySavingEvent` and would otherwise leave `contents` empty.
- **CLOB columns are set by a separate `update`.** An `insert` carrying a `valueClobFile` is executed as a
  `PreparedStatement`, which binds the id as a string and fails on a `uuid` column ("column \"id\" is of type
  uuid but expression is of type character varying" on PostgreSQL).
- **Title** comes from the document's first `# H1`, falling back to the slug.
- **A leading prune changeset deletes rows for documents that no longer exist**, so removing or renaming a
  `.md` removes it from the application. Dropping its changeset would not be enough: Liquibase never
  un-applies what it has already run, so the row would linger on every database that saw it. The prune's id
  hashes the slug list, so it re-runs exactly when the set of documents changes.

  Pruning **requires a `slugPrefix`** — the delete is scoped to `SLUG LIKE '<prefix>%'`, and without a prefix
  that would mean "every row not generated from this directory", wiping hand-authored content. With no prefix
  the generator skips pruning and says so, on stdout and in an XML comment.

Two things to know:

- Liquibase resolves `valueClobFile` **relative to the changelog file**, so `contentDir` must be a directory
  beside `outputChangelog` and `contentRefPrefix` its name.
- If your root changelog collects changelogs with `includeAll`, restrict that scan to XML
  (`endsWithFilter=".xml"` in Liquibase 5) — otherwise it tries to parse the `.md`/`.html` content files
  sitting next to the changelog as changelogs.

The upsert overwrites the row wholesale, so **the files win**: a generated document can be edited in the admin
UI, but the next build that sees a changed source file replaces it. Content meant to be maintained in the UI
should not be generated.