---
id: platform-common
title: platform-common
sidebar_position: 2
---

# platform-common

Shared models and utilities used across all other modules. Has **no Spring dependencies** — it is a pure Kotlin library.

## Contents

- Domain exceptions (`TransformException`, `SpecValidationException`, etc.)
- Utility classes shared across modules
- No parsers, no writers, no Spring beans

## Rules

- Never add Spring Boot or Spring Framework dependencies to this module
- Never add database or messaging dependencies here
- Keep it lean — only truly cross-cutting code belongs here
