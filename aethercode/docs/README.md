# AetherCode Documentation

**Last updated**: 2026-08-09 (after R77 metrics endpoint)

## Structure

```
docs/
├── README.md                       ← you are here
├── CHANGELOG.md                    ← single page, every released version
├── USAGE.md                        ← quick-start command reference
├── ROADMAP-R33-R82.md              ← planned rounds + status
│
├── user-guide/                     ← end-user docs (TUI, slash cmds, shortcuts)
│   ├── getting-started.md          ← first-time setup
│   ├── tui-guide.md                ← visual tour of the TUI
│   ├── keybindings.md              ← every shortcut (Ctrl-X)
│   ├── slash-commands.md           ← every /command
│   ├── features.md                 ← feature-by-feature explanation
│   └── themes.md                   ← R42 themes
│
├── api/                            ← for RPC / protocol consumers
│   ├── jsonrpc.md                  ← every method + notification
│   └── state-model.md              ← TUI reducer + State shape
│
├── dev-guide/                      ← for contributors
│   ├── architecture.md             ← module layout + runtime flow
│   ├── adding-rounds.md            ← how to add a new R-round
│   └── testing.md                  ← test conventions
│
└── changelog/                      ← per-round retrospectives
    ├── R1-R32.md
    ├── R33-R48.md
    ├── R49-R61.md
    ├── R77.md
    └── R78-R82.md (in progress)
```

## How docs stay fresh

Every R-round (R33+) does the following in order:
1. Code change + tests
2. Update `user-guide/features.md` (new feature: what it does, how to use it)
3. Update `user-guide/keybindings.md` / `user-guide/slash-commands.md` if applicable
4. Update `api/jsonrpc.md` if a new RPC was added
5. Update `api/state-model.md` if state shape changed
6. Append a retro paragraph to the current `changelog/RXX-RYY.md`
7. Bump `CHANGELOG.md` if user-facing

This is enforced by the per-round test `docs/MagicDocsTest.java`
(see `dev-guide/testing.md`).
