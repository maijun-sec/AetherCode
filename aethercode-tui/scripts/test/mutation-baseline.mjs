/**
 * A mutation battery measures ONE thing: "did the gate notice?".
 *
 * That question has a PRECONDITION, and no battery used to state it:
 * the gate must be GREEN before anything is mutated.
 *
 * ## Why the precondition matters
//
// Without it the battery is theatre. A gate that is already red —
// because of an unrelated defect, or because the round's own change
// broke it — stays red under every mutation, so every case reports
// "caught" and the battery prints a wall of green.
//
// R377 hit exactly this. Its own new gate had a red assertion (the
// daemon handler's body was extracted through a guessed `\n  }` window
// that never matched, because the closing brace is indented by four
// spaces). The battery's 15 cases ALL reported "caught" — and were
// measuring nothing whatsoever.
//
// The red gate was the visible symptom; the invisible one is the
// battery that shrugged at it.
//
// ## The check
//
// One extra run of the gate before the first mutation. It is cheap, and
// it is the only honest answer to "is this battery trustworthy?".
//
// ## Usage
//
//     import { requireGreenBaseline } from "./mutation-baseline.mjs";
//
//     function runGate() { ... }          // must return { fail, out }
//
//     requireGreenBaseline(runGate, "r377 mutation battery");
//
// `runGate()` must return `fail === 0` for a green gate and a POSITIVE
// number for a red one; `-1` (the harness convention for "the gate never
// got as far as reporting") counts as red.
 */

/**
 * Refuse to run a battery against a gate that is already red.
 *
 * @param {() => {fail: number, out?: string}} runGate
 * @param {string} label the battery's name, for the message
 * @returns {true} when the gate is green
 */
export function requireGreenBaseline(runGate, label) {
  const result = runGate() ?? {};
  const fail = typeof result.fail === "number" ? result.fail : -1;
  if (fail === 0) return true;

  console.error(
    `\n  ✖ ${label}: the gate is NOT green before any mutation (fail=${fail}).\n` +
      '    Every case would report "caught" for the wrong reason, so this\n' +
      "    battery would measure nothing at all. Fix the gate first, then\n" +
      "    re-run the battery.\n",
  );

  const out = result.out ?? "";
  for (const line of out.split(/\r?\n/)) {
    const m = /^✖ (.+?) \(/.exec(line);
    if (m) console.error(`      ✖ ${m[1]}`);
  }

  process.exit(2);
}
