# Changelog

All notable changes to this project are documented in this file.

## 1.0.0 - 2026-08-05

### Added

- Static replacement of direct `DeobfuscatorHelper.getString(long, String[])` calls.
- Static replacement of dotted wrapper calls such as `Deobfuscator.android.module.getString(long)`.
- Static replacement of dollar-sign wrapper calls such as `Deobfuscator$android$module.getString(long)`.
- Recursive scanning of main and support Java source trees for literal chunk tables.
- Heuristic table selection with ambiguity protection.
- Java string literal parsing with Unicode, octal, line-continuation, and `\s` escape support.
- Patched source-tree output.
- `decoded_strings.tsv` and `unresolved_calls.tsv` reports.
- Windows and Unix launch scripts.
- Cross-platform smoke tests and GitHub Actions CI.
