# Jmix WebContent Addon

Allows to use content stored in database and editable from the admin area

# Installation

1. Checkout this repo
2. build and publish artifacts to your Maven

```
./gradlew publishToMavenLocal
```
3. Select Install Manually from Jmix Addon Manager and insert addon's artifactId 

```
io.jmix.webcontent:webcontent-starter:0.0.1
```

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