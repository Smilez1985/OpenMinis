# AppFunctions: from declaration to indexed function

_Read and measured 2026-10-27. Every claim names its source; anything not
verified is marked as such at the end._

## Summary

An app declares AppFunctions in Kotlin, the **compiler** turns those
declarations into an **XML schema file** shipped inside the APK, and the
**OS** indexes that file. The XML is therefore not a legacy format and not a
second mechanism: it is the compiled form of the declaration. A caller that
reads the XML is reading the annotation — after the compiler has run.

## 1. Declaration and artifact are two layers of one pipeline

The official overview describes the flow in named steps
(developer.android.com/ai/appfunctions, read 2026-10-27):

> **Schema generation:** The AppFunctions Jetpack library **generates an XML
> schema file** that lists all the declared AppFunctions in the app. The
> Android OS uses this file to index the available AppFunctions.

The same holds on the runtime-registration path. The API reference for
`AppFunctionSignature` (developer.android.com/reference/androidx/appfunctions/AppFunctionSignature)
states that interfaces annotated with it

> allow the AppFunction compiler to **generate metadata XML files** and
> adapters that bridge the gap between platform requests and your Kotlin
> implementation.

So the pipeline is:

```
@AppFunctionDeclaration (Kotlin source)
        │  KSP  (androidx.appfunctions:appfunctions-compiler)
        ▼
schema XML inside the APK
        │  indexed by the OS
        ▼
the app function registry  ──  queried by callers
```

## 2. The annotation was renamed, and the old name is no longer an annotation

From the Jetpack release notes (developer.android.com/jetpack/androidx/releases/appfunctions,
read 2026-10-27):

| Version | Date | Change |
|---|---|---|
| alpha01 … alpha12 | up to 2026-09-23 | the annotation is `@AppFunction` |
| **alpha13** | **2026-10-07** | “**Rename AppFunction annotation to AppFunctionDeclaration**” |
| beta01 | 2026-10-21 | current release, unchanged name |

Measured directly in the AAR `androidx.appfunctions:appfunctions:1.0.0-beta01`
(downloaded 2026-10-27, 590 756 bytes; class file access flags parsed):

| Class | `ACC_ANNOTATION` | `ACC_INTERFACE` |
|---|---|---|
| `androidx.appfunctions.AppFunctionDeclaration` | **true** | true |
| `androidx.appfunctions.AppFunction` | **false** | true |
| `androidx.appfunctions.AppFunctionSignature` | true | true |
| `androidx.appfunctions.AppFunctionSerializable` | true | true |

`AppFunction` is retained as a name but is **no longer an annotation** — it is
an interface. Code that still refers to “the `@AppFunction` annotation” is
describing the pre-alpha13 API.

## 3. The annotation is not readable at runtime

Measured in the same AAR: the constant pool of `AppFunctionDeclaration`
contains `java.lang.annotation.RetentionPolicy` with the value **`CLASS`**.

Consequence: the annotation is present in the DEX file of an APK, but the
Android runtime does not expose it through reflection. Anyone who wants to
read declarations out of an installed or downloaded APK has to parse the DEX
annotation table; there is no reflective shortcut.

It also means that recovering declarations from an APK gains nothing over
reading the generated XML, which the compiler wrote from those very
annotations.

## 4. What the OS requires of the app

Also from the release notes:

- beta01: “Generate an empty XML file even when no AppFunctions are present,
  since **AppSearch expects the corresponding file** specified in the App
  manifest.” A missing file is not tolerated by the indexer.
- beta01: “Renamed `AppFunctionAppMetadata.description` to `instructions`, and
  the **description XML attribute** of `AppFunctionAppMetadata` to
  `instructions`, to align with **MCP naming conventions**.” The generated XML
  is alive and is being kept in step with MCP vocabulary.
- alpha08: `AppFunctionSignature` was added to declare **runtime registered**
  function signatures — and these, too, get generated metadata XML
  (“Omit `enabledByDefault` from generated XML for `AppFunctionSignature`s”).

The file name is a parameter of the declaration:
`@AppFunctionServiceEntryPoint(serviceName = …, appFunctionXmlFileName = …)`
(example in the official overview).

## 5. What a caller should read

Two sources, and they answer different questions:

| Source | Answers |
|---|---|
| the registry — `cmd app_function list-app-functions`, or `dumpsys app_function` | which functions exist **now**, with parameters where an app supplies them |
| `assets/*app_function*.xml` inside the target APK | the parameter schema as the app shipped it |

The registry is a superset: it is built by the OS from the same declarations,
and it also covers functions an app registers at runtime without shipping any
XML (measured — see the companion note).

## 6. Not verified

- Whether Google intends to replace the XML-based indexing. Nothing to that
  effect appears in the release notes up to beta01 (2026-10-21); the opposite
  is documented, since AppSearch is stated to require the file. Marked here so
  that the question is not answered by assumption.
- The exact set of attributes the OS requires in the XML beyond the ones named
  above. Not read from the platform sources.
