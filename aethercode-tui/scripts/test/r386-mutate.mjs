// R386 mutation battery.
//
// ## What this battery is for
//
// R386 made the theme real: the palette the user picks is the palette
// on the screen, and the theme vocabulary is written in ONE file.
//
// Each of those is a property that can silently rot:
//
//   - the token table can go back to being a frozen literal (the
//     screen stops following the theme, and every other assertion
//     about the palette still passes);
//   - the publish can stop publishing;
//   - the App can stop publishing BEFORE it builds its tree — the
//     elements it builds then paint one frame late;
//   - the resolver can stop being case-insensitive;
//   - a theme can drop out of the registry, or stop being routable;
//   - the picker can stop writing `themeName` — the screen would
//     not follow the pick;
//   - a file can start writing the vocabulary again;
//   - the second vocabulary (the `aethercode-themes` package) can
//     come back;
//   - a sentinel can lose its owner on one of the two renderers.
//
// Every case below must turn the gate RED. A case that does not is a
// HOLE, and a hole is the most expensive kind of green: it reports
// like a pass.
//
// ## The rules this battery follows
//
//   - the gate must be GREEN before the first mutation (Lesson 1194);
//   - a needle that matches nothing is a NO-OP, and a NO-OP is
//     reported as a hole, not as a pass;
//   - every needle lands in CODE, not in a comment (Lesson 1203) and is
//     SCOPED to the construct it claims to check (Lesson 1195 / 1212);
//   - a needle that is not UNIQUE lands on the wrong occurrence
//     (Lesson 1212) — every needle below is checked for that;
//   - the battery exits NON-ZERO when a hole survives (Lesson 1196);
//   - the battery never runs concurrently with the test suite, and it
//     restores every file it touched.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
import { requireGreenBaseline } from "./mutation-baseline.mjs";
import { mutateInCode } from "./code-needle.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const PATHS = {
  gate: join(here, "r386-theme-reach.test.mjs"),
  themes: join(root, "src", "themes.ts"),
  theme: join(root, "src", "theme.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  commands: join(root, "src", "commands.ts"),
  state: join(root, "src", "state.ts"),
  ctx: join(root, "src", "ThemeContext.tsx"),
};

// A battery that is KILLED mid-case must not leave a mutation on disk.
const SNAPSHOT = join(root, "tmp-r386-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);
const snapshot = writeSnapshot(
  Object.entries(PATHS).map(([k, p]) => [p, original[k]]),
  SNAPSHOT,
);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", PATHS.gate], { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  // `fail === -1` means the gate never got as far as reporting.
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

// The gate must be green BEFORE the first mutation. (R377 Lesson 1194.)
requireGreenBaseline(runGate, "r386 mutation battery");

const CASES = [
  // --- the palette stops reaching the screen -----------------------------------
  [
    "the token table goes back to being a frozen literal",
    [
      [
        "theme",
        (s) =>
          mutateInCode(
            s,
            /get: \(\) => getActivePalette\(\)\[role\],/,
            "get: () => BASE_TOKENS[role],",
            "theme.ts",
          ),
      ],
    ],
  ],
  [
    "publishing stops publishing",
    [
      [
        "themes",
        (s) =>
          mutateInCode(
            s,
            /const palette = pickPalette\(name\);\n  activePalette = palette;/,
            "const palette = pickPalette(name);",
            "themes.ts",
          ),
      ],
    ],
  ],
  [
    "the App stops publishing before it builds the tree",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /useActiveTheme\(state\.themeName\);/,
            "void state.themeName;",
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the provider stops publishing for its children",
    [
      [
        "ctx",
        (s) =>
          mutateInCode(
            s,
            /export function useActiveTheme\(themeName: ThemeName\): Palette \{\n  return publishTheme\(themeName\);/,
            "export function useActiveTheme(themeName: ThemeName): Palette {\n  return pickPalette(themeName);",
            "ThemeContext.tsx",
          ),
      ],
    ],
  ],

  // --- the vocabulary drifts ---------------------------------------------------
  [
    "the resolver stops being case-insensitive",
    [
      [
        "themes",
        (s) =>
          mutateInCode(
            s,
            /const wanted = name\.trim\(\)\.toLowerCase\(\);/,
            "const wanted = name.trim();",
            "themes.ts",
          ),
      ],
    ],
  ],
  [
    "a theme drops out of the registry",
    [
      [
        "themes",
        (s) =>
          mutateInCode(
            s,
            /THEME_NAMES: ThemeName\[\] = \["default", "solarized", "monokai", "kanagawa", "opencode"\];/,
            'THEME_NAMES: ThemeName[] = ["default", "solarized", "monokai", "opencode"];',
            "themes.ts",
          ),
      ],
    ],
  ],
  [
    "a shipped theme stops being routable",
    [
      [
        "themes",
        (s) =>
          mutateInCode(
            s,
            /return THEME_NAMES\.find\(\(n\) => n\.toLowerCase\(\) === wanted\);/,
            'return THEME_NAMES.find((n) => n.toLowerCase() === wanted && n !== "opencode");',
            "themes.ts",
          ),
      ],
    ],
  ],
  [
    "a file starts writing the vocabulary again",
    [
      [
        "state",
        (s) =>
          mutateInCode(s, /themeName: DEFAULT_THEME_NAME,/, 'themeName: "default",', "state.ts"),
      ],
    ],
  ],
  [
    "the command layer starts writing the vocabulary again",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /const raw = String\(rest\[0\]\);/,
            'const raw = String(rest[0]);\n      const legacy = "solarized";\n      void legacy;',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the picker stops following the pick -------------------------------------
  [
    "picking a theme stops writing the state's theme",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /themePickedRef\.current = true;\n\s*dispatch\(\{ type: "setTheme", theme \}\);/,
            "themePickedRef.current = true;",
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the live preview stops writing the state's theme",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /onPreview=\{\(name\) => \{\n(\s*)const theme = resolveThemeName\(name\);\n\s*if \(!theme\) return;\n\s*dispatch\(\{ type: "setTheme", theme \}\);/,
            "onPreview={(name) => {\n$1const theme = resolveThemeName(name);\n$1if (!theme) return;",
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the picker's rows stop coming from the registry",
    [["tui", (s) => mutateInCode(s, /paletteIsDark\(palette\)/, "true", "tui.tsx")]],
  ],
  [
    "the registry's blurbs stop reaching the picker",
    [
      [
        "tui",
        (s) =>
          mutateInCode(s, /description: label\?\.blurb,/, "description: undefined,", "tui.tsx"),
      ],
    ],
  ],

  // --- the second vocabulary comes back ----------------------------------------
  [
    "a renderer reaches for the aethercode-themes package again",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /import \{ ThemeProvider, useActiveTheme \} from "\.\/ThemeContext\.js";/,
            'import { ThemeProvider, useActiveTheme } from "./ThemeContext.js";\nimport { ThemeStore } from "aethercode-themes";\nvoid ThemeStore;',
            "tui.tsx",
          ),
      ],
    ],
  ],

  // --- a sentinel nobody owns ----------------------------------------------------
  [
    "line mode stops claiming the theme sentinels",
    [
      [
        "line",
        (s) => mutateInCode(s, /case "__THEMES_PICK__":/, 'case "__THEMES_PICK__X":', "line.ts"),
      ],
    ],
  ],
  [
    "Ink stops claiming the theme picker sentinel",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /slash\?\.local === "__THEMES_PICK__"/,
            'slash?.local === "__THEMES_PICK__X"',
            "tui.tsx",
          ),
      ],
    ],
  ],
];

console.log(`r386 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
let noops = 0;

for (const [name, edits] of CASES) {
  const applied = [];
  let broken = null;
  for (const [key, mutate] of edits) {
    const before = original[key];
    let after;
    try {
      after = mutate(before);
    } catch (err) {
      // A needle that does not land is a NO-OP, and the message says
      // WHY: "not in the file at all" and "only in a comment" are
      // different mistakes with different fixes.
      broken = `${key} — ${err.message}`;
      break;
    }
    if (after === before) {
      broken = `${key} — the needle matched but changed nothing`;
      break;
    }
    applied.push([key, before, after]);
  }
  if (broken) {
    holes++;
    noops++;
    console.log(`  ??  ${name}\n      NO-OP on ${broken} — this case tests nothing`);
    continue;
  }

  for (const [key, , after] of applied) writeFileSync(PATHS[key], after, "utf-8");
  const { fail, status, out } = runGate();
  restore(applied);

  const ok = fail > 0 || status !== 0;
  if (!ok) holes++;
  console.log(
    `  ${ok ? "ok  " : "HOLE"}  ${name}\n      gate fail=${fail} status=${status}  — gate must go red`,
  );
  if (!ok) {
    const lines = out.split(/\r?\n/);
    lines.forEach((l, i) => {
      const m = /^✖ (.+?) \(/.exec(l);
      if (!m) return;
      console.log(`        ✖ ${m[1]}`);
      for (let k = i + 1; k < Math.min(i + 5, lines.length); k++) {
        if (/^\s*(AssertionError|Error):/.test(lines[k])) {
          console.log(`            ${lines[k].trim().slice(0, 220)}`);
          break;
        }
      }
    });
  }
}

function restore(applied) {
  for (const [key, before] of applied) writeFileSync(PATHS[key], before, "utf-8");
}

for (const [k, p] of Object.entries(PATHS)) {
  if (readFileSync(p, "utf-8") !== original[k]) {
    writeFileSync(p, original[k], "utf-8");
    console.log(`  !! restored ${p}`);
  }
}

// The snapshot MUST go when the battery finishes — a snapshot that
// outlives a clean run is a landmine.
snapshot.clear();

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}` +
    (noops ? ` (${noops} no-op)` : ""),
);
process.exit(holes === 0 ? 0 : 1);
