# Paranoid Source Deobfuscator

A standalone Java tool that statically replaces resolvable Paranoid and LSParanoid string decoder calls in Java source produced by JADX.

It operates on source files only:

- no runtime hooking;
- no Android project;
- no Gradle;
- no APK or DEX rebuild;
- no external Java dependencies.

## Supported call patterns

The tool currently recognizes constant `long` IDs in these common forms:

```java
DeobfuscatorHelper.getString(-123456789L, chunks)
Deobfuscator.android.module.getString(-123456789L)
Deobfuscator$android$module.getString(-123456789L)
```

It scans the main source tree and any optional support source trees for literal `String[]` chunk tables, decodes candidate strings, associates wrapper classes with their tables, and replaces successful calls with escaped Java string literals.

Calls that use a runtime variable are intentionally left unchanged:

```java
return DeobfuscatorHelper.getString(id, chunks);
```

That line is the decoder implementation, not a statically resolvable call site.

## Requirements

- JDK or Android Studio JBR 17 or newer.
- Java source exported by JADX or another decompiler.
- The relevant `String[]` chunk tables must be present in the main source tree or in an additional support source tree.

Check Java:

```text
java -version
```

## Repository layout

```text
.
├── src/
│   └── ParanoidSourceDeobfuscator.java
├── scripts/
│   ├── run.bat
│   └── run.sh
├── tests/
│   ├── fixtures/
│   ├── run-smoke-test.ps1
│   └── run-smoke-test.sh
├── .github/workflows/ci.yml
├── CHANGELOG.md
├── LICENSE
├── NOTICE
└── README.md
```

## Usage

The first argument is the source tree to patch. The second argument is the output path. Any later arguments are optional source files or directories used only to discover additional decoder/chunk tables.

### Windows

```powershell
.\scripts\run.bat `
  .\app_jadx `
  .\patched_source
```

When a decoder table is located in another decompiled DEX tree:

```powershell
.\scripts\run.bat `
  .\app_jadx `
  .\patched_source `
  .\secondary_dex_jadx
```

### Linux and macOS

```bash
./scripts/run.sh \
  ./app_jadx \
  ./patched_source
```

With an additional support tree:

```bash
./scripts/run.sh \
  ./app_jadx \
  ./patched_source \
  ./secondary_dex_jadx
```

### Run the Java source directly

Java source-file mode does not require compilation:

```bash
java src/ParanoidSourceDeobfuscator.java \
  app_jadx \
  patched_source \
  secondary_dex_jadx
```

### Multiple support sources

You may provide more than one support source:

```bash
java src/ParanoidSourceDeobfuscator.java \
  main_jadx \
  patched_source \
  dump_1_jadx \
  dump_2_jadx \
  extra_chunks.java
```

Only the first input tree is copied and patched. Support sources are scanned for tables but are not copied into the output.

## Output

The output directory contains:

```text
patched_source/
├── sources/...
├── decoded_strings.tsv
└── unresolved_calls.tsv
```

### `decoded_strings.tsv`

A tab-separated mapping of strings that were actually used for replacements:

```text
id    java_literal    plaintext    table_source    table_field    score
```

### `unresolved_calls.tsv`

Calls that could not be safely replaced:

```text
path    line    kind    id    qualifier    array_expr    reason    call
```

Typical reasons include:

- no table could decode the ID;
- multiple tables produced similarly plausible but different strings;
- the required decoder/chunk table was not included in the scanned sources.

## Verifying the result

Search only for decoder calls that still contain a constant `long` literal.

### ripgrep

```bash
rg -n 'getString\(\s*[+-]?(?:0[xX][0-9A-Fa-f]+|[0-9]+)[lL]' patched_source/sources
```

No output means no supported literal-ID call remains.

A broad search such as:

```bash
rg -n 'Deobfuscator.*getString|DeobfuscatorHelper.*getString' patched_source/sources
```

may still find decoder implementation code such as:

```java
return DeobfuscatorHelper.getString(id, chunks);
```

That is expected.

## Example

Before:

```java
public String value() {
    return Deobfuscator$android$mpp.getString(-984964770L);
}
```

After:

```java
public String value() {
    return "decoded text";
}
```

## How table selection works

For every constant ID, the tool attempts decoding against the discovered tables.

It then ranks valid candidates using:

- text plausibility;
- a direct reference to a field such as `g.a`;
- the Java class or file name owning the table;
- wrapper names normalized between dotted and dollar-sign forms, for example:
  - `Deobfuscator.android.mpp`
  - `Deobfuscator$android$mpp`

If two similarly ranked tables produce different plausible strings, the call is not modified and is written to `unresolved_calls.tsv`.

## Troubleshooting

### No literal `String[]` table was found

The decoder table is missing from the supplied source trees.

Locate classes similar to:

```java
private static final String[] chunks = { ... };
```

Then add the containing file or decompiled source directory as another command-line argument.

### Some wrapper calls remain unresolved

Find the wrapper class referenced by the call:

```java
Deobfuscator$android$mpp.getString(...)
```

The corresponding generated class normally contains its own table:

```java
public final class Deobfuscator$android$mpp {
    private static final String[] chunks = { ... };

    public static String getString(long id) {
        return DeobfuscatorHelper.getString(id, chunks);
    }
}
```

Include the source tree containing that class as a support source.

### `unsupported escape`

Version 1.0.0 supports standard Java escapes, Unicode escapes, octal escapes, line continuation, and the Java `\s` escape. Unrelated non-literal arrays are skipped instead of aborting the scan.

### The replacement count is larger than the JADX search count

JADX may display one search result per source line. A single line can contain two or more decoder calls. The tool counts each replaced call separately.

### Decoded output contains `\ufffd` or corrupted characters

JADX may replace malformed or unpaired UTF-16 code units with `U+FFFD` while rendering or exporting source. When that happens, the chunk data may already be damaged. Use source that preserves the original Java escapes or extract the table from a different decompiler output.

## Tests

Linux/macOS:

```bash
./tests/run-smoke-test.sh
```

Windows PowerShell:

```powershell
.\tests\run-smoke-test.ps1
```

The smoke test covers:

- a direct two-argument helper call;
- a one-argument wrapper using a dollar-sign class name;
- a chunk table located in a separate support source tree.

## Limitations

- This is a lightweight source rewriter, not a complete Java parser.
- It processes Java source, not raw DEX, smali, APK, or AAB files.
- Only decoder calls with constant decimal or hexadecimal `long` literals are replaced.
- Dynamic IDs, calculated IDs, and runtime variables are not resolved.
- The tool does not rename classes, methods, or fields.
- The tool does not reconstruct control flow.
- Results depend on the chunk table being preserved correctly by the decompiler.

## Responsible use

Use this tool only on software you own or are authorized to analyze.

## Credits

The string decoding algorithm is compatible with:

- [LSPosed/LSParanoid](https://github.com/LSPosed/LSParanoid)
- [MichaelRocks/paranoid](https://github.com/MichaelRocks/paranoid)

Related static deobfuscation projects that informed the workflow:

- [giacomoferretti/paranoid-deobfuscator](https://github.com/giacomoferretti/paranoid-deobfuscator)
- [mkurkar/paranoid-deobfuscator-selfScript](https://github.com/mkurkar/paranoid-deobfuscator-selfScript)

This project is independent and is not affiliated with LSPosed or the projects listed above.

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
