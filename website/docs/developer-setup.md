---
id: developer-setup
title: Developer Setup
sidebar_position: 3
---

# Developer Setup

This guide covers everything you need to run the documentation site locally and contribute to the Transform Platform project.

---

## Overview

```mermaid
flowchart TD
    START([Start]) --> CHECK_NODE{Node.js ≥ 18?}
    CHECK_NODE -->|Yes| INSTALL[npm install]
    CHECK_NODE -->|No| NVM{nvm installed?}

    NVM -->|No| INSTALL_NVM[Install nvm]
    NVM -->|Yes, not in PATH| SOURCE_NVM["Source nvm in ~/.zshrc"]
    NVM -->|Yes| USE_NVM["nvm install + nvm use"]

    INSTALL_NVM --> SOURCE_NVM
    SOURCE_NVM --> USE_NVM
    USE_NVM --> INSTALL

    INSTALL --> START_DEV[npm start]
    START_DEV --> BROWSER(["localhost:3000/transform-platform/"])

    style START fill:#dbeafe,stroke:#2563eb
    style BROWSER fill:#dcfce7,stroke:#16a34a
    style SOURCE_NVM fill:#fef3c7,stroke:#d97706
    style INSTALL_NVM fill:#fef3c7,stroke:#d97706
```

---

## Node.js Version Requirements

Docusaurus 3 requires **Node.js ≥ 18**. This project pins to **Node 20 LTS** via `.nvmrc`.

```mermaid
timeline
    title Node.js Support Timeline
    Node 16 : End of Life (not supported)
    Node 18 : Minimum supported
    Node 20 : Pinned in .nvmrc (recommended)
    Node 22 : Latest LTS (compatible)
```

Check your current version:

```bash
node --version
```

If you see `v16.x` or lower, follow the nvm setup below.

---

## Installing nvm

[nvm (Node Version Manager)](https://github.com/nvm-sh/nvm) lets you install and switch between Node versions without touching your system Node.

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
```

---

## Fixing "zsh: command not found: nvm"

After installing nvm, your shell needs to be told where to find it. This is the most common setup issue.

```mermaid
flowchart TD
    ERR["zsh: command not found: nvm"] --> CHECK[Check ~/.zshrc for nvm lines]
    CHECK -->|Lines missing| ADD[Add nvm sourcing block to ~/.zshrc]
    CHECK -->|Lines present| RELOAD[source ~/.zshrc]
    ADD --> RELOAD
    RELOAD --> TEST[nvm --version]
    TEST -->|Prints version| OK([✅ nvm working])
    TEST -->|Still fails| NEWTERM[Open a new terminal window]
    NEWTERM --> OK

    style ERR fill:#fee2e2,stroke:#ef4444
    style OK fill:#dcfce7,stroke:#16a34a
```

Add these lines to your `~/.zshrc` (for zsh) or `~/.bashrc` (for bash):

```bash
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
[ -s "$NVM_DIR/bash_completion" ] && \. "$NVM_DIR/bash_completion"
```

Then reload:

```bash
source ~/.zshrc
```

Verify nvm is available:

```bash
nvm --version
```

---

## Installing and Using Node 20

The `website/.nvmrc` file pins the project to Node 20. From inside `website/`:

```bash
nvm install    # reads .nvmrc → installs Node 20 if not already present
nvm use        # switches to Node 20 for this terminal session
node --version # → v20.x.x
```

To make Node 20 your default across all sessions:

```bash
nvm alias default 20
```

---

## Running the Docs Site Locally

```mermaid
sequenceDiagram
    participant DEV as Developer
    participant TERM as Terminal
    participant NVM as nvm
    participant NPM as npm
    participant BROWSER as Browser

    DEV->>TERM: cd website
    DEV->>TERM: nvm use
    TERM->>NVM: switch to Node 20
    NVM-->>TERM: Now using Node v20.x.x

    DEV->>TERM: npm install
    TERM->>NPM: install dependencies
    NPM-->>TERM: added N packages

    DEV->>TERM: npm start
    TERM->>NPM: start dev server
    NPM-->>BROWSER: opens localhost:3000/transform-platform/

    Note over BROWSER: Hot-reload active
    DEV->>TERM: edit docs/*.md
    TERM-->>BROWSER: page auto-refreshes
```

### Step-by-step

```bash
# 1. Navigate to the website directory
cd website

# 2. Switch to the correct Node version
nvm use

# 3. Install dependencies (first time only, or after package.json changes)
npm install

# 4. Start the dev server
npm start
```

The site opens at **http://localhost:3000/transform-platform/**. Any `.md` file you edit under `docs/` refreshes instantly — no restart required.

---

## Editing Documentation

```mermaid
flowchart LR
    subgraph docs["website/docs/"]
        INTRO["intro.md"]
        GS["getting-started.md"]
        ARCH["architecture.md"]
        DEV["developer-setup.md"]
        MOD["modules/"]
        INT["integration/"]
        EXT["extending/"]
        CON["contributing/"]
    end

    EDIT([Edit .md file]) --> SAVE([Save])
    SAVE --> HMR([Hot-reload])
    HMR --> BROWSER([Browser updates instantly])

    style EDIT fill:#dbeafe,stroke:#2563eb
    style BROWSER fill:#dcfce7,stroke:#16a34a
```

All docs live in `website/docs/`. The sidebar structure is controlled by `website/sidebars.ts`. To add a new page:

1. Create a `.md` file under `website/docs/` with a front-matter `id` field
2. Add the `id` to the appropriate category in `sidebars.ts`
3. The dev server picks it up immediately — no restart needed

---

## Adding Mermaid Diagrams

Mermaid is enabled globally. Use a fenced code block with the `mermaid` language tag:

````md
```mermaid
flowchart LR
    A[Parse] --> B[Correct] --> C[Validate] --> D[Write]
```
````

Supported diagram types used in this project:

| Type | Use case |
|------|----------|
| `flowchart` | Flows, pipelines, decision trees |
| `sequenceDiagram` | Service interactions, request/response |
| `classDiagram` | Data models, class hierarchies |
| `erDiagram` | Database schema |
| `gantt` | Implementation phases, timelines |
| `timeline` | Version history, roadmaps |
| `pie` | Distribution charts |
| `mindmap` | Feature overviews |

Diagrams automatically adapt to light/dark mode.

---

## Production Build

To verify the site builds without errors (same as CI):

```bash
npm run build
```

Preview the production build locally:

```bash
npm run serve
# → http://localhost:3000/transform-platform/
```

---

## Deployment

Deployment is fully automated. There is nothing to manually deploy.

```mermaid
flowchart LR
    PUSH[Push to main\nwebsite/** changed] --> GHA[GitHub Actions\ntriggered]
    GHA --> BUILD[npm ci + npm run build]
    BUILD --> UPLOAD[Upload Pages artifact]
    UPLOAD --> DEPLOY[Deploy to GitHub Pages]
    DEPLOY --> LIVE([Live at\ngithub.io/transform-platform/])

    style PUSH fill:#dbeafe,stroke:#2563eb
    style LIVE fill:#dcfce7,stroke:#16a34a
```

- Any push to `main` that touches `website/**` automatically triggers the **Deploy Docs** workflow
- Build artifacts are never committed to the repo — the workflow hands the built output directly to GitHub Pages
- You can also trigger it manually from the **Actions** tab → **Deploy Docs** → **Run workflow**

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `Minimum Node.js version not met` | Node < 18 installed | Run `nvm install && nvm use` inside `website/` |
| `zsh: command not found: nvm` | nvm not sourced in shell | Add nvm block to `~/.zshrc`, then `source ~/.zshrc` |
| Port 3000 already in use | Another dev server running | Kill the other process or use `npm start -- --port 3001` |
| Stale styles after editing CSS | Browser cache | Hard-refresh with `Cmd+Shift+R` (Mac) / `Ctrl+Shift+R` |
| `Cannot find module` on `npm start` | Missing dependencies | Run `npm install` first |
| Mermaid diagram not rendering | Syntax error in diagram | Check the [Mermaid live editor](https://mermaid.live) |
