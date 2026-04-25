# User Guide

This guide explains how to run `interlis-mcp`, connect it to MCP clients, and call the available tools with the correct payload shapes.

## Prerequisites
- Java 21 runtime for builds and for running the JAR
- Optional: Docker

## Start The Server

### Packaged JAR
```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

If your default `java` is not Java 21, use the explicit Java 21 binary path.

### Development Mode
```bash
./gradlew bootRun
```

### Docker
```bash
./gradlew buildAndPushMultiArchImage
docker run --rm -i interlis-mcp
```

Do not allocate a TTY. MCP uses STDIN and STDOUT directly.

## Runtime Characteristics
- Transport: STDIO
- Capability set: `tools` plus runtime `logging`
- Negotiated MCP protocol in current smoke tests: `2025-06-18`
- Server metadata:
  - `name = interlis-mcp`
  - `version = Gradle-Buildversion`

For local builds the version is typically `0.0.LOCALBUILD`. CI builds expose the version calculated from the existing Gradle versioning script.

## Connect Clients

### Claude Desktop
Add a server entry to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "interlis-mcp": {
      "command": "/path/to/java-21/bin/java",
      "args": [
        "-jar",
        "/Users/stefan/sources/interlis-mcp/build/libs/interlis-mcp.jar"
      ],
      "env": {
        "JAVA_TOOL_OPTIONS": "-Xms512m -Xmx512m"
      }
    }
  }
}
```

### VS Code
Example `mcp.json` entry:

```json
{
  "servers": {
    "interlis-mcp": {
      "command": "/path/to/java-21/bin/java",
      "args": [
        "-jar",
        "/Users/stefan/sources/interlis-mcp/build/libs/interlis-mcp.jar"
      ]
    }
  }
}
```

## Tool Reference

### Snippet Generators Returning `{ "iliSnippet": ... }`
- `createModelSnippet`
  Builds a `MODEL` skeleton with optional `lang`, `uri`, `version`, `iliVersion`, `imports`, `includeSolothurnHeader`, `iliDoc`, and `metaAttributes`.
- `createTopicSnippet`
  Builds a `TOPIC` block and accepts optional `iliDoc` and `metaAttributes`.
- `createClassSnippet`
  Builds a `CLASS` block with optional `isAbstract`, `extendsFqn`, `oidDecl`, `attrLines`, `iliDoc`, and `metaAttributes`.
- `createStructureSnippet`
  Builds a `STRUCTURE` block with optional `iliDoc` and `metaAttributes`.
- `createAssociationSnippet`
  Builds an `ASSOCIATION` block from `name` and `roles`, plus optional `attrLines`, `iliDoc`, and `metaAttributes`. Roles support mandatory `name` and `classFQN` plus optional `card` and `external`.
- `createEnumDomainSnippet`
  Builds an enumerated `DOMAIN` with optional `iliDoc` and `metaAttributes`; enum values can also carry optional `iliDoc` and `metaAttributes` via `itemSpecs`.
- `createEnumTreeDomainSnippet`
  Builds a nested enumerated `DOMAIN` from recursive `items`, with optional `iliDoc` and `metaAttributes` on both domain and item level.
- `createNumericDomainSnippet`
  Builds a numeric `DOMAIN` with optional `unitFqn`, `iliDoc`, and `metaAttributes`.
- `createUnitSnippet`
  Builds a `UNIT` with optional `iliDoc` and `metaAttributes`.
- `createCoordDomainSnippet`
  Builds a default 2D or 3D `COORD` domain with optional `iliDoc` and `metaAttributes`.
- `createStructureAttributeLine`
  Builds an attribute line referencing a `STRUCTURE`, with optional `iliDoc` and `metaAttributes`.
- `createUniqueConstraint`
- `createMandatoryConstraint`
- `createSetConstraint`
- `createPresentIfConstraint`
- `createValueRangeConstraint`
- `createExistenceConstraint`
- `createMetaAttributeBlock`
  Builds a pure `!!@` block from validated meta attributes.

### `createAttributeLine`
`createAttributeLine` is the structured attribute helper. In MCP it currently expects a nested `req` object:

```json
{
  "req": {
    "name": "hoehe",
    "iliDoc": "Gemessene Höhe",
    "metaAttributes": [
      {
        "name": "ch.so.quality",
        "rawValue": "INTERLIS"
      }
    ],
    "mandatory": true,
    "collection": "NONE",
    "typeSpec": {
      "baseType": {
        "kind": "NUM_RANGE",
        "min": 0.0,
        "max": 100.0,
        "unitFqn": "INTERLIS.m"
      }
    }
  }
}
```

Domain reference variant:

```json
{
  "req": {
    "name": "farbe",
    "typeSpec": {
      "domainFqn": "Demo.Core.Farbe"
    }
  }
}
```

The response is an `AttributeLineResponse` object with `iliSnippet`.

Supported `typeSpec` families:
- `domainFqn`
- `baseType`
- `referenceType`
- `blackboxType`
- `enumTreeValueType`
- `basketType`
- `objectType`
- `metaobjectType`

Examples for the new families:

```json
{
  "req": {
    "name": "refObj",
    "typeSpec": {
      "referenceType": {
        "targetClassFqn": "Demo.Topic.Target",
        "external": true
      }
    }
  }
}
```

```json
{
  "req": {
    "name": "statusPfad",
    "typeSpec": {
      "enumTreeValueType": {
        "enumTreeDomainFqn": "Demo.Topic.StatusTree"
      }
    }
  }
}
```

`enumTreeValueType` intentionally renders through a named enum-tree domain, because raw `ENUMTREEVAL` is not accepted by ili2c as an attribute declaration.

### `createAssociationSnippet`
Roles use the shape `{ "name": "...", "classFQN": "...", "card"?: "{0..*}", "external"?: true }`. For relationship attributes, reuse existing attribute tools and pass the resulting ILI lines via `attrLines`.

```json
{
  "name": "Link",
  "roles": [
    { "name": "from", "classFQN": "Demo.Topic.Source", "card": "{1}", "external": true },
    { "name": "to", "classFQN": "Demo.Topic.Target", "card": "{0..*}" }
  ],
  "attrLines": [
    "/** Beziehungscode */\ncode : TEXT*20;"
  ]
}
```

### `createEnumTreeDomainSnippet`
Use recursive `items` of the form `{ "name": "...", "iliDoc"?: "...", "metaAttributes"?: [...], "children"?: [...] }`.

```json
{
  "name": "StatusTree",
  "items": [
    {
      "name": "A",
      "metaAttributes": [
        { "name": "ili2db.dispName", "value": "Eltern" }
      ],
      "children": [
        {
          "name": "B",
          "metaAttributes": [
            { "name": "ili2db.dispName", "value": "Kind B" }
          ]
        },
        { "name": "C" }
      ]
    },
    { "name": "D" }
  ]
}
```

### `createEnumDomainSnippet`
Legacy payloads with `items: ["A", "B"]` remain valid. If you need item-level `iliDoc` or meta attributes such as `ili2db.dispName`, use `itemSpecs` instead of `items`.

```json
{
  "name": "GebaeudeArt",
  "itemSpecs": [
    {
      "name": "Wohnhaus",
      "iliDoc": "Wohngebaeude",
      "metaAttributes": [
        { "name": "ili2db.dispName", "value": "Wohngebaeude" }
      ]
    },
    { "name": "Gewerbe" }
  ]
}
```

### `createMetaAttributeBlock`

```json
{
  "metaAttributes": [
    { "name": "title", "value": "Demo" },
    { "name": "ch.so.flag", "rawValue": "TRUE" }
  ]
}
```

### `renameModelElement`

```json
{
  "modelText": "INTERLIS 2.4; ...",
  "elementFqn": "Demo.Topic.Target",
  "newName": "TargetRenamed"
}
```

`expectedKind` is optional and can be used as an additional guard, for example `"expectedKind": "CLASS_OR_STRUCTURE"`.

The tool returns regenerated `updatedModelText`. It is semantically robust, but not source-preserving with regard to whitespace or declaration layout.

### IliDoc And Meta Attributes
- `iliDoc` renders as INTERLIS block comment directly before the affected element, for example `/** Beschreibung */`.
- `metaAttributes` render as real INTERLIS meta attributes directly before the affected element, for example `!!@ ch.so.flag=TRUE`.
- `metaAttributes` entries use this shape:

```json
{
  "name": "title",
  "value": "a title"
}
```

- Use `value` for safely quoted string values and `rawValue` for verbatim output after `=`.

Example `createModelSnippet` payload:

```json
{
  "name": "DemoModel",
  "iliDoc": "Kantonales Testmodell",
  "metaAttributes": [
    { "name": "title", "value": "Kantonales Testmodell" },
    { "name": "ch.so.test", "rawValue": "TRUE" }
  ]
}
```

Example `createClassSnippet` payload:

```json
{
  "name": "Gebaeude",
  "attrLines": [
    "/** Amtliche Nummer */\n!!@ ch.so.oid=\"extern\"\nnummer : TEXT*20;"
  ],
  "iliDoc": "Gebäude im Bestand"
}
```

### Plain String Responses
- `createImportLine`
  Returns a single `IMPORTS` line as text.
- `formatIliModel`
  Returns the formatted INTERLIS model text as plain text.

### Model Transformation Tools
- `renameModelElement`
  Renames a `MODEL`, `TOPIC`, `CLASS/STRUCTURE`, `ASSOCIATION`, `DOMAIN`, `UNIT`, or `ATTRIBUTE` by recompiling and regenerating the full model text.
  Returns a structured object with `updatedModelText`, `oldElementFqn`, `newElementFqn`, `expectedKind`, and `notes`.
  `expectedKind` is optional and acts only as an additional guard.

### Validation, Geometry, And Lookup Tools
- `validateIliModel`
  Returns `{ "valid": boolean, "messages": [...] }`.
- `ensureGeometryDependencies`
  Returns:
  - `importLinesToAdd`
  - `domainsToAdd`
  - `attributeLine`
  - `notes`
- `listGeometryTypes`
  Returns `{ "iliVersion": "...", "types": [{ "name": "...", "model": "..." }] }`.
- `listMathFunctions`
  Returns `{ "iliVersion": "...", "functions": [{ "function": "...", "returns": "..." }] }`.
- `listTextFunctions`
  Returns `{ "iliVersion": "...", "functions": [{ "function": "...", "returns": "..." }] }`.

### Identifier Utilities
- `sanitizeIdentifier`
  Returns `{ "value": "...", "changed": boolean }`.
- `validateIdentifier`
  Returns `{ "valid": true }` or throws.
- `validateFqn`
  Returns `{ "valid": true }` or throws.

## Tips
- Send exactly the argument shape shown by `tools/list`.
- Optional parameters are omitted rather than sent as empty strings when possible.
- Use `validateIliModel` on full `MODEL ... END` content, not on isolated snippets.
- Use `ensureGeometryDependencies` before manually composing geometry attributes into a model.
- For comments use `iliDoc`; do not send free `!!` comment lines.
- For actual INTERLIS meta attributes use `metaAttributes`, not `iliDoc`.
