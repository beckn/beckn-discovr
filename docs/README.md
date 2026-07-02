# Discovr Documentation

Navigation index for `docs/`. Each folder holds one kind of document.

| Folder | What lives here |
|--------|-----------------|
| [`architecture/`](architecture/) | System design, fan-out discovery design, failure analysis, and diagrams (`.excalidraw` sources + `.png`/`.svg` renders in [`assets/`](architecture/assets/)). |
| [`adr/`](adr/) | Architecture Decision Records — one decision per file, numbered. Superseded ADRs are kept as history. |
| [`requirements/`](requirements/) | `REQ-*` — structured requirements, approved before design. |
| [`design/`](design/) | `DESIGN-*` design specs. |
| [`scenarios/`](scenarios/) | `NN-feature.md` — end-to-end test scenarios per feature. |
| [`reviews/`](reviews/) | Performance review reports. |
| [`reference/`](reference/) | Stable reference material: [`USER_GUIDE.md`](reference/USER_GUIDE.md), [`onboarding-flowchart.md`](reference/onboarding-flowchart.md). |

## Conventions

- **Diagrams:** commit the editable `.excalidraw` source alongside the exported `.png`/`.svg`; re-export after editing so inline embeds stay current.
- **Generated artifacts** (`.docx` / `.pdf` exports) are **not committed** — they are regenerable and ignored via `.gitignore`.
- **ADRs** are immutable once accepted; supersede rather than edit.
