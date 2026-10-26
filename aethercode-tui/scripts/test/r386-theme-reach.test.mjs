// R386 — the theme the user picks IS the theme they see.
//
// ## What this round found
//
// 1. `/theme NAME` printed "theme → NAME" and changed NOTHING. The
//    command said it worked; the screen did not move.
//
// 2. The palette was computed and DISCARDED. `src/theme.ts` exported
//    a STATIC token table `t` that thirty components import and
//    render with; `src/ThemeContext.tsx` published a palette through
//    a React context whose `useTheme()` had **zero call sites**.
//
// 3. The theme vocabulary was written in FOUR files, and the copies
//    had already disagreed:
//
//      - `src/themes.ts` shipped FIVE palettes;
//      - `src/commands.ts` accepted only THREE — `/theme kanagawa`
//        and `/theme opencode` were rejected with "unknown theme";
//      - `src/state.ts` typed `themeName` as a three-value union;
//      - `src/tui.tsx` cast to the same three-value union.
//
//    `kanagawa` and `opencode` were shipped palettes NOBODY COULD
//    SELECT.
//
// 4. A SECOND vocabulary existed. The `aethercode-themes` package
//    ships `light` / `dark` / `solarized-light` / `solarized-dark` /
//    `high-contrast` — a different set, on a light/dark model the
//    TUI's fixed dark terminal cannot honour. The TUI's picker was
//    driven by that package's `ThemeStore`, whose `setActive()`
//    wrote to `<UserHome>/.aethercode/theme.json` while the screen
//    kept rendering the TUI's own colours.
//
// 5. The picker's selection never touched `state.themeName` — the
//    field the renderer reads. So the palette on screen and the
//    palette the user picked were two unrelated things.
//
// ## The contracts this file enforces
//
//   §1  the palette REACHES the tokens        (read `t` after a publish)
//   §2  the palette REACHES the SCREEN        (render with Ink; read the ANSI)
//   §3  the vocabulary is written in ONE file  (scan every src/*.ts)
//   §4  `/theme NAME` accepts exactly the registry's names
//   §5  the picker lists the registry, and picking writes `themeName`
//   §6  the second vocabulary is gone
//   §7  the sentinels keep their owners on BOTH renderers
//
// ## Why §2 renders a real tree
//
// §1 proves the token table follows the published palette. That is
// NOT the same claim as "the screen changes", and R386's whole defect
// was the gap between the two: the palette was computed, published
// to a context nobody read, and the screen sat on the old colours.
//
// So §2 renders an Ink tree, switches the theme, and reads the ANSI
// escape codes out of the emitted frame. The pre-fix code cannot
// pass it: `t` was a frozen object literal, so the second frame
// would carry the FIRST frame's colours.
//
// It renders the REAL `ThemeProvider` and the REAL token table, and
// it calls the REAL publish path (`useActiveTheme`) — not a copy of
// it. A gate that verifies a copy of the product is a gate that
// verifies the copy (R381, Lesson 1217).

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { scan, blankNonCode } from "./ts-scan.mjs";
import { claimedBy } from "./claimed-by.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const srcRoot = join(root, "src");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");

// =========================================================================
// compile the product's own modules
// =========================================================================
//
// The output directory is INSIDE the package on purpose: the compiled
// `ThemeContext.js` imports "react" and "ink", and a bare specifier
// only resolves if the importing file sits under the package's
// `node_modules`. `tmp-*-tsc/` is gitignored.

const out = join(root, "tmp-r386-tsc");
mkdirSync(out, { recursive: true });

{
  const tscBin = join(
    root,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tsc.cmd" : "tsc",
  );
  const args = [
    "--outDir",
    out,
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--jsx",
    "react",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
    `"${join(root, "src", "ThemeContext.tsx")}"`,
    `"${join(root, "src", "theme.ts")}"`,
    `"${join(root, "src", "commands.ts")}"`,
  ];
  const r = spawnSync(`"${tscBin}"`, args, { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}

const imp = (rel) => import("file:///" + join(out, rel).replace(/\\/g, "/"));

const themesMod = await imp("themes.js");
const themeMod = await imp("theme.js");
const ctxMod = await imp("ThemeContext.js");
const commandsMod = await imp("commands.js");

const { PALETTES, THEME_NAMES, THEME_LABELS, resolveThemeName, setActivePalette } = themesMod;
const { t } = themeMod;
const { ThemeProvider, useActiveTheme } = ctxMod;
const { routeSlash } = commandsMod;

const tuiCode = codeOf(read("tui.tsx"), "tui.tsx");
const lineCode = codeOf(read("line.ts"), "line.ts");
const commandsCode = codeOf(read("commands.ts"), "commands.ts");
const stateCode = codeOf(read("state.ts"), "state.ts");

// =========================================================================
// §1  the palette reaches the tokens
// =========================================================================

test("every palette role in `t` follows the published palette", () => {
  // Derived, not literal: the roles are the palette's OWN keys, so a
  // palette that grows a role is covered the day it grows one — and a
  // role the token table forgot shows up as `undefined` here instead
  // of painting nothing.
  for (const [name, palette] of Object.entries(PALETTES)) {
    setActivePalette(palette);
    for (const role of Object.keys(palette)) {
      if (role === "background") continue; // the surface, not a token
      assert.equal(t[role], palette[role], `t.${role} does not follow the "${name}" palette`);
    }
  }
});

test("switching the palette changes the tokens, and leaves the syntax colours alone", () => {
  setActivePalette(PALETTES.default);
  const beforeBrand = t.brand;
  const beforeToken = t.tokKeyword;

  setActivePalette(PALETTES.monokai);

  assert.notEqual(t.brand, beforeBrand, "the brand token did not change with the palette");
  assert.equal(t.brand, PALETTES.monokai.brand);
  assert.equal(
    t.tokKeyword,
    beforeToken,
    "a markdown syntax colour followed the palette — the token table has two layers",
  );
});

test("the registry's names and its palettes agree, in both directions", () => {
  const names = [...THEME_NAMES].sort();
  const palettes = Object.keys(PALETTES).sort();
  assert.deepEqual(
    names,
    palettes,
    "the registry's name list and its palette table disagree — a name with no palette is a theme that cannot be picked",
  );
  for (const name of THEME_NAMES) {
    assert.ok(THEME_LABELS[name], `"${name}" has no label`);
    assert.ok(THEME_LABELS[name].blurb, `"${name}" has no blurb`);
  }
});

// =========================================================================
// §2  the palette reaches the SCREEN
// =========================================================================

test("switching the theme repaints the rendered frame", async () => {
  // FORCE_COLOR must be set BEFORE chalk is loaded — chalk decides
  // its colour level at import time, and a static import would run
  // before this line. Hence the dynamic imports.
  process.env.FORCE_COLOR = "3";
  const { default: chalk } = await import("chalk");
  const React = (await import("react")).default;
  const { render, Text } = await import("ink");
  const { Writable } = await import("node:stream");

  /** The escape sequence chalk opens a colour with, for a given colour. */
  const openCode = (colour) => {
    const builder = colour.startsWith("#") ? chalk.hex(colour) : chalk[colour];
    assert.ok(builder, `chalk has no colour named "${colour}" — the gate cannot predict the frame`);
    const painted = builder("X");
    return painted.slice(0, painted.indexOf("X"));
  };

  const chunks = [];
  const stdout = new Writable({
    write(chunk, _enc, cb) {
      chunks.push(chunk.toString());
      cb();
    },
  });
  stdout.columns = 80;
  stdout.rows = 24;
  stdout.isTTY = true;

  /**
   * A child that reads the token table inside its OWN render body —
   * the shape twenty-nine of the TUI's components have.
   */
  const Child = () => React.createElement(Text, { color: t.brand }, "R386-CHILD");

  /**
   * The App: it publishes the palette BEFORE it builds the tree, and
   * the tree contains BOTH a child that reads `t` later and an
   * element the App itself builds (where `t` is read immediately).
   */
  const App = ({ themeName }) => {
    useActiveTheme(themeName);
    return React.createElement(
      ThemeProvider,
      { themeName },
      React.createElement(Child),
      React.createElement(Text, { color: t.accent }, "R386-APP"),
    );
  };

  const waitFor = async (needle, what) => {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      if (chunks.join("").includes(needle)) return true;
      await new Promise((r) => setTimeout(r, 25));
    }
    assert.fail(
      `${what} never appeared in the rendered frame.\n--- frame ---\n${chunks.join("")}\n---`,
    );
  };

  const inst = render(React.createElement(App, { themeName: "default" }), {
    stdout,
    stdin: process.stdin,
    stderr: process.stderr,
    debug: true,
    patchConsole: false,
    exitOnCtrlC: false,
  });

  try {
    const defaultBrand = openCode(PALETTES.default.brand);
    const defaultAccent = openCode(PALETTES.default.accent);
    const solarizedBrand = openCode(PALETTES.solarized.brand);
    const solarizedAccent = openCode(PALETTES.solarized.accent);

    await waitFor(defaultBrand, "the default palette's brand colour");
    await waitFor(defaultAccent, "the default palette's accent colour");
    assert.notEqual(defaultBrand, solarizedBrand, "the two palettes render the same colour");

    chunks.length = 0;
    inst.rerender(React.createElement(App, { themeName: "solarized" }));

    await waitFor(solarizedBrand, "the solarized palette's brand colour");
    await waitFor(solarizedAccent, "the solarized palette's accent colour");

    const frame = chunks.join("");
    assert.ok(
      !frame.includes(defaultBrand),
      "the frame still carries the OLD palette's brand colour — the screen did not follow the theme",
    );
  } finally {
    inst.unmount();
  }
});

// =========================================================================
// §3  the vocabulary is written in ONE file
// =========================================================================

/**
 * The theme names, and the file allowed to write them.
 *
 * "default" is ALSO an ordinary English word — a highlighter's
 * fallback language, a prompt formatter's colour name — so the rule
 * is split in two:
 *
 *   - the four DISTINCTIVE names are policed across the whole tree;
 *   - "default" is policed among the files that TAKE PART in the
 *     theme surface, i.e. the ones that import the registry.
 *
 * Both halves are derived, not curated: "takes part" means "imports
 * themes.js", so a new participant is covered without an edit here.
 */
const DISTINCTIVE = THEME_NAMES.filter((n) => n !== "default");

test("the distinctive theme names are written in exactly one file", () => {
  const owner = "themes.ts";
  const offenders = [];

  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name)) {
        const rel = p.slice(srcRoot.length + 1).replace(/\\/g, "/");
        if (rel === owner) continue;
        const code = codeOf(readFileSync(p, "utf-8"), rel);
        for (const s of scan(code).strings) {
          if (DISTINCTIVE.includes(s.value)) offenders.push(`${rel}: "${s.value}"`);
        }
      }
    }
  };
  walk(srcRoot);

  assert.deepEqual(
    offenders,
    [],
    "a theme name is written outside src/themes.ts — every copy is a place the list can drift: " +
      offenders.join(", "),
  );
});

test("a file that takes part in the theme surface writes no theme name at all", () => {
  // The other half: "default" is too ordinary to police globally, so
  // it is policed where it means "a theme" — in the files that import
  // the registry. Those files must reach the names through the
  // registry, not through a literal.
  const offenders = [];

  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name)) {
        const rel = p.slice(srcRoot.length + 1).replace(/\\/g, "/");
        if (rel === "themes.ts") continue;
        const code = codeOf(readFileSync(p, "utf-8"), rel);
        if (!/from\s+["'][^"']*themes\.js["']/.test(code)) continue;
        for (const s of scan(code).strings) {
          if (THEME_NAMES.includes(s.value)) offenders.push(`${rel}: "${s.value}"`);
        }
      }
    }
  };
  walk(srcRoot);

  assert.deepEqual(
    offenders,
    [],
    "these files take part in the theme surface but spell a theme name out: " +
      offenders.join(", "),
  );
});

test("the command layer and the state no longer carry the vocabulary", () => {
  // A spot-check with a reason: the two files that used to hold a
  // hand-written list. If the rule above ever needs an exception,
  // this is where it would show up first.
  for (const [label, code] of [
    ["commands.ts", commandsCode],
    ["state.ts", stateCode],
  ]) {
    for (const s of scan(code).strings) {
      assert.ok(
        !THEME_NAMES.includes(s.value),
        `${label} still writes the theme name "${s.value}" — take it from the registry`,
      );
    }
  }
});

// =========================================================================
// §4  /theme NAME accepts exactly the registry's names
// =========================================================================

test("/theme <name> routes every name the registry ships", () => {
  for (const name of THEME_NAMES) {
    const r = routeSlash(`/theme ${name}`);
    assert.equal(r.local, `__THEME__:${name}`, `/theme ${name} does not reach the renderer`);
  }
});

test("/theme <name> is case-insensitive", () => {
  // A theme name is a word, not an identifier — and the registry's
  // own resolver is the only thing that may normalise it.
  for (const name of THEME_NAMES) {
    const r = routeSlash(`/theme ${name.toUpperCase()}`);
    assert.equal(
      r.local,
      `__THEME__:${name}`,
      `/theme ${name.toUpperCase()} does not reach the renderer`,
    );
  }
});

test("/theme with an unknown name reports the registry's names", () => {
  const r = routeSlash("/theme nosuchtheme");
  assert.ok(!String(r.local).startsWith("__"), "an unknown theme produced a sentinel");
  for (const name of THEME_NAMES) {
    assert.ok(
      String(r.local).includes(name),
      `the error message does not mention "${name}" — the registry's names are derived, not typed`,
    );
  }
});

test("bare /theme opens the picker", () => {
  assert.equal(routeSlash("/theme").local, "__THEMES_PICK__");
  assert.equal(routeSlash("/theme pick").local, "__THEMES_PICK__");
  assert.equal(routeSlash("/theme-pick").local, "__THEMES_PICK__");
});

test("the resolver is total over the vocabulary", () => {
  for (const name of THEME_NAMES) {
    assert.equal(resolveThemeName(name), name, `${name} does not survive resolution`);
    assert.equal(resolveThemeName(name.toUpperCase()), name, `${name} is case-sensitive`);
  }
  assert.equal(resolveThemeName("nosuchtheme"), undefined);
});

// =========================================================================
// §5  the picker lists the registry, and picking writes `themeName`
// =========================================================================

test("the picker's rows are built from the registry", () => {
  // Pins CALLS, not identifiers: the rows must come from the
  // registry's list, the labels from its label table, and the
  // dark/light marker from a DERIVED function (a hand-written
  // `isDark` per palette is a second copy of a fact the colours
  // already carry).
  assert.ok(/THEME_NAMES\.map\(/.test(tuiCode), "the picker's rows are not built from THEME_NAMES");
  assert.ok(/THEME_LABELS\[/.test(tuiCode), "the picker does not read the registry's labels");
  assert.ok(/paletteIsDark\(/.test(tuiCode), "the picker's dark/light marker is not derived");
});

/**
 * The end of a JSX element that starts at `at`.
 *
 * The ThemePicker is SELF-CLOSING (`/>`), so there is no closing tag
 * to find — and a brace matcher cannot help either, because a JSX
 * element's attributes are expression containers: the first `{` after
 * `<ThemePicker` is `themes={…}`, and matching braces there returns
 * that container and nothing else.
 *
 * So: walk forward, track the depth of the `{…}` containers, and take
 * the first `/>` at depth zero. The string literals are blanked
 * first, so a `/>` inside a string cannot end the element early.
 */
function jsxElementEnd(src, at) {
  const code = blankNonCode(src);
  let depth = 0;
  for (let i = at; i < code.length; i++) {
    if (code[i] === "{") depth++;
    else if (code[i] === "}") depth--;
    else if (depth === 0 && code[i] === "/" && code[i + 1] === ">") return i + 2;
  }
  return -1;
}

test("picking a theme writes the state's theme", () => {
  // The defect was that the picker's selection never touched the
  // field the renderer reads. Scope the assertion to the picker's
  // own JSX block, so a `setTheme` elsewhere cannot satisfy it.
  const at = tuiCode.indexOf("<ThemePicker");
  assert.ok(at > -1, "tui.tsx does not render the ThemePicker");
  const end = jsxElementEnd(tuiCode, at);
  assert.ok(end > at, "the ThemePicker's JSX block has no end — re-anchor this test");
  const block = tuiCode.slice(at, end);

  // Each handler is checked on its OWN, so a `setTheme` in one
  // handler cannot satisfy the assertion about another.
  const handlers = ["onClose", "onSelect", "onPreview"];
  for (let i = 0; i < handlers.length; i++) {
    const name = handlers[i];
    const start = block.indexOf(`${name}=`);
    assert.ok(start > -1, `the picker has no ${name} handler`);
    const end2 =
      i + 1 < handlers.length ? block.indexOf(`${handlers[i + 1]}=`, start) : block.length;
    const body = block.slice(start, end2 > start ? end2 : block.length);
    assert.ok(
      /type:\s*"setTheme"/.test(body),
      `the picker's ${name} handler does not write \`themeName\` — the screen would not follow the pick`,
    );
  }
});

test("the registry's blurbs reach the user", () => {
  // A table nobody reads is the same defect as a palette nobody
  // renders — and `THEME_LABELS` was written in the same round that
  // found `useTheme()` had zero call sites. The label has to travel:
  // the host puts it on the row, and the picker shows it.
  assert.ok(
    /description:\s*label\?\.blurb/.test(tuiCode),
    "the picker's rows carry no description — the registry's blurbs reach nobody",
  );
  const picker = codeOf(read("components/ThemePicker.tsx"), "components/ThemePicker.tsx");
  assert.ok(/t\.description/.test(picker), "the picker never renders the description it is handed");
});

test("the App publishes the palette before it builds the tree", () => {
  // The App reads the token table while it builds its JSX, so the
  // publish has to happen in the App's body — a provider renders
  // AFTER that, and the elements the App builds would otherwise
  // paint one frame late.
  assert.ok(
    /useActiveTheme\(state\.themeName\)/.test(tuiCode),
    "the App does not publish the palette — the elements it builds would keep the old colours",
  );
  assert.ok(
    /<ThemeProvider\s+themeName=\{state\.themeName\}>/.test(tuiCode),
    "the tree is not wrapped in the theme provider",
  );
});

// =========================================================================
// §6  the second vocabulary is gone
// =========================================================================

test("no source file reaches for the aethercode-themes package", () => {
  // The package's themes are a light/dark model. The TUI's terminal
  // surface is fixed and dark, so a light theme is not selectable
  // here — and the package's `ThemeStore` wrote to a file on disk
  // while the screen kept rendering the TUI's own colours.
  const offenders = [];

  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name)) {
        const rel = p.slice(srcRoot.length + 1).replace(/\\/g, "/");
        const code = codeOf(readFileSync(p, "utf-8"), rel);
        if (/from\s+["']aethercode-themes["']/.test(code)) offenders.push(rel);
      }
    }
  };
  walk(srcRoot);

  assert.deepEqual(
    offenders,
    [],
    `these files still import the second theme vocabulary: ${offenders.join(", ")}`,
  );
});

test("the bridge files are gone", () => {
  for (const f of ["aethercodeThemesBridgeCore.ts", "aethercodeThemesBridgeUI.tsx"]) {
    assert.ok(!existsSync(join(srcRoot, f)), `${f} is still there — it had zero call sites`);
  }
});

// =========================================================================
// §7  the sentinels keep their owners on BOTH renderers
// =========================================================================

test("the theme sentinels are claimed on both renderers", () => {
  for (const sentinel of ["__THEMES_PICK__", "__THEME__"]) {
    assert.ok(
      claimedBy(tuiCode, sentinel),
      `no Ink renderer claims ${sentinel} — the user would see the raw token`,
    );
    assert.ok(
      claimedBy(lineCode, sentinel),
      `no line-mode renderer claims ${sentinel} — the user would see the raw token`,
    );
  }
});
