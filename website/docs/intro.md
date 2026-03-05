---
id: intro
title: Introduction
slug: /
sidebar_position: 1
---

# Transform Platform

**Enterprise-grade, spec-driven file ↔ event transformation engine.**

Transform Platform lets you parse, validate, correct, and route any file format — without writing code. Define a `FileSpec`, upload a file, and the platform handles the rest.

## What is it?

A single `FileSpec` JSON object describes a file format completely — field names, types, positions, validation rules, and correction rules. The platform uses that spec to:

- **Parse** any file format (CSV, Fixed-Width, XML, …) into a universal `ParsedRecord` stream
- **Correct** dirty data automatically (trim, pad, coerce dates, regex replace, …)
- **Validate** every record against business rules
- **Write** results to Kafka, files, webhooks, or databases

> No code changes are needed to support a new file layout — register a spec, upload a file.

## Key Principles

| Principle | What it means |
|-----------|---------------|
| **Spec-Driven** | All parsing behaviour is declared in `FileSpec`. Business logic lives in specs, not code. |
| **Stream-First** | Files of any size are processed as a `Flow` — never loaded fully into memory. |
| **Open/Closed** | Add new parsers or writers by implementing one interface. Zero changes to existing code. |
| **Fail-Safe** | Errors are collected per-record; the pipeline continues unless a `FATAL` error is encountered. |
| **Security-First** | Sensitive fields are masked in logs. Encryption at rest and in transit by design. |

## Supported Formats

| Format | Status |
|--------|--------|
| CSV / Delimited (any delimiter) | ✅ Phase 1 |
| Fixed-Width / Flat File | ✅ Phase 1 |
| XML (XPath field mapping, XSD validation) | ✅ Phase 1 |
| JSON | 🔜 Phase 2 |
| NACHA | 🔜 Phase 2 |
| ISO 20022 | 🔜 Phase 2 |

## Next Steps

- [Getting Started](/getting-started) — run the platform locally in minutes
- [Architecture](/architecture) — understand the transformation pipeline
- [API Reference](/api-reference) — REST endpoints for spec management and file transforms
