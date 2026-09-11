import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  UI_PERMISSION_MODES,
  mapUiPermissionToDaemon,
  mapDaemonToUiPermission,
  permissionModeLabel,
  type UiPermissionMode,
} from './index';

/**
 * 3-tier permission model.
 *
 * <p>Previously the desktop's MessageInput dropdown
 * surfaced 4 raw daemon-enum names (Default / Accept
 * Edits / Bypass / Plan). The user found the enum
 * names opaque — "what does the 'perm' field mean" — and asked for
 * a 3-tier Chinese-labeled model that maps to the
 * underlying enum:
 *
 *   主动询问 (ask)    → ASK_BEFORE_TOOL (or DEFAULT)
 *   智能授权 (smart)  → ACCEPT_EDITS
 *   始终授权 (bypass) → BYPASS_PERMISSIONS
 *
 * <p>The test below pins:
 * <ol>
 *   <li>The 3-tier list shape + labels (source-pin)</li>
 *   <li>The UI → daemon mapping (behaviour)</li>
 *   <li>The daemon → UI mapping (behaviour)</li>
 *   <li>The label helper (behaviour)</li>
 *   <li>The setPermissionMode action wires the mapping
 *       through the daemon RPC (source-pin)</li>
 *   <li>The StatusBar renders via permissionModeLabel
 *       (source-pin)</li>
 *   <li>The MessageInput dropdown uses UI_PERMISSION_MODES
 *       (source-pin)</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R203: UI_PERMISSION_MODES (3-tier dropdown source-of-truth)', () => {
  it('declares exactly 3 tiers', () => {
    // Source-pin: the new model collapses
    // legacy's 4 names (Default / Accept
    // Edits / Bypass / Plan) into 3. A
    // future round adding a 4th tier would
    // be a deliberate UX decision; until
    // then the test pins the count.
    expect(UI_PERMISSION_MODES.length).toBe(3);
  });

  it('uses the user-friendly Chinese labels', () => {
    // The 3 labels the user asked for. A
    // refactor that swaps to English or any
    // other language would fail the user's
    // explicit "what does the 'perm' field mean" feedback
    // — pin the literal Chinese strings.
    const labels = UI_PERMISSION_MODES.map((m) => m.label);
    expect(labels).toEqual(['主动询问', '智能授权', '始终授权']);
  });

  it('declares the three UI tier values as the union type', () => {
    // The dropdown's <option value="..."> is
    // keyed by these strings. A refactor that
    // changes a value would break the
    // dropdown ↔ store contract.
    const values = UI_PERMISSION_MODES.map((m) => m.value);
    expect(values).toEqual(['ask', 'smart', 'bypass']);
    // Union type is a TS-only check; we assert
    // via a tiny self-call so tsc still
    // narrows correctly.
    const ask: UiPermissionMode = 'ask';
    const smart: UiPermissionMode = 'smart';
    const bypass: UiPermissionMode = 'bypass';
    expect([ask, smart, bypass]).toEqual(['ask', 'smart', 'bypass']);
  });

  it('maps every UI tier to at least one daemon enum', () => {
    // The mapping MUST be exhaustive — a UI
    // tier with no canonical daemon would be
    // a no-op and the dropdown would never
    // actually change the engine state.
    for (const m of UI_PERMISSION_MODES) {
      expect(m.daemon.length).toBeGreaterThan(0);
      for (const d of m.daemon) {
        expect(typeof d).toBe('string');
        expect(d.length).toBeGreaterThan(0);
      }
    }
  });

  it('asks tier covers DEFAULT (legacy) and ASK_BEFORE_TOOL', () => {
    // DEFAULT is the legacy enum name. A
    // user upgrading from a v0.2.30 build
    // would have DEFAULT persisted. The
    // 主动询问 (ask) tier must cover both so the
    // dropdown round-trips correctly.
    const askMode = UI_PERMISSION_MODES.find((m) => m.value === 'ask')!;
    expect(askMode.daemon).toContain('ASK_BEFORE_TOOL');
    expect(askMode.daemon).toContain('DEFAULT');
  });

  it('smart tier covers ACCEPT_EDITS only', () => {
    // The 智能授权 (smart) tier maps to ACCEPT_EDITS — that's
    // the engine's existing mode name. We
    // didn't introduce a new PermissionMode
    // enum value; the new behaviour is in
    // ProjectPermissionPolicy.resolveSmart
    // (which ACCEPT_EDITS now routes to).
    const smartMode = UI_PERMISSION_MODES.find((m) => m.value === 'smart')!;
    expect(smartMode.daemon).toEqual(['ACCEPT_EDITS']);
  });

  it('bypass tier covers BYPASS_PERMISSIONS only', () => {
    const bypassMode = UI_PERMISSION_MODES.find((m) => m.value === 'bypass')!;
    expect(bypassMode.daemon).toEqual(['BYPASS_PERMISSIONS']);
  });
});

describe('R203: mapUiPermissionToDaemon (behaviour)', () => {
  it('maps ask → ASK_BEFORE_TOOL', () => {
    expect(mapUiPermissionToDaemon('ask')).toBe('ASK_BEFORE_TOOL');
  });
  it('maps smart → ACCEPT_EDITS', () => {
    expect(mapUiPermissionToDaemon('smart')).toBe('ACCEPT_EDITS');
  });
  it('maps bypass → BYPASS_PERMISSIONS', () => {
    expect(mapUiPermissionToDaemon('bypass')).toBe('BYPASS_PERMISSIONS');
  });
  it('passes through advanced / legacy enum names unchanged', () => {
    // Power users can pick ACCEPT_TASK / PLAN
    // / AUTO_READ_ONLY via the TUI; those
    // values must round-trip through the
    // store without being silently mapped to
    // a 3-tier enum.
    expect(mapUiPermissionToDaemon('ACCEPT_TASK')).toBe('ACCEPT_TASK');
    expect(mapUiPermissionToDaemon('PLAN')).toBe('PLAN');
    expect(mapUiPermissionToDaemon('AUTO_READ_ONLY')).toBe('AUTO_READ_ONLY');
    expect(mapUiPermissionToDaemon('DEFAULT')).toBe('DEFAULT');
  });
  it('returns the input unchanged for unknown values (defensive)', () => {
    expect(mapUiPermissionToDaemon('foo')).toBe('foo');
    expect(mapUiPermissionToDaemon('')).toBe('');
  });
});

describe('R203: mapDaemonToUiPermission (behaviour)', () => {
  it('maps ASK_BEFORE_TOOL → ask', () => {
    expect(mapDaemonToUiPermission('ASK_BEFORE_TOOL')).toBe('ask');
  });
  it('maps DEFAULT → ask (legacy compatibility)', () => {
    expect(mapDaemonToUiPermission('DEFAULT')).toBe('ask');
  });
  it('maps ACCEPT_EDITS → smart', () => {
    expect(mapDaemonToUiPermission('ACCEPT_EDITS')).toBe('smart');
  });
  it('maps BYPASS_PERMISSIONS → bypass', () => {
    expect(mapDaemonToUiPermission('BYPASS_PERMISSIONS')).toBe('bypass');
  });
  it('returns raw enum for advanced modes (so StatusBar shows it verbatim)', () => {
    expect(mapDaemonToUiPermission('ACCEPT_TASK')).toBe('ACCEPT_TASK');
    expect(mapDaemonToUiPermission('PLAN')).toBe('PLAN');
    expect(mapDaemonToUiPermission('AUTO_READ_ONLY')).toBe('AUTO_READ_ONLY');
  });
  it('returns input unchanged for unknown values (defensive)', () => {
    expect(mapDaemonToUiPermission('foo')).toBe('foo');
  });
});

describe('R203: permissionModeLabel (behaviour)', () => {
  it('returns the Chinese label for 3-tier enums', () => {
    expect(permissionModeLabel('ASK_BEFORE_TOOL')).toBe('主动询问');
    expect(permissionModeLabel('DEFAULT')).toBe('主动询问');
    expect(permissionModeLabel('ACCEPT_EDITS')).toBe('智能授权');
    expect(permissionModeLabel('BYPASS_PERMISSIONS')).toBe('始终授权');
  });
  it('returns the raw enum name for advanced modes', () => {
    // ACCEPT_TASK / PLAN / AUTO_READ_ONLY are
    // not surfaced in the dropdown; the
    // StatusBar / banner show them verbatim so
    // the power user knows they're in an
    // advanced mode.
    expect(permissionModeLabel('ACCEPT_TASK')).toBe('ACCEPT_TASK');
    expect(permissionModeLabel('PLAN')).toBe('PLAN');
    expect(permissionModeLabel('AUTO_READ_ONLY')).toBe('AUTO_READ_ONLY');
  });
  it('handles null / undefined / empty gracefully', () => {
    expect(permissionModeLabel(null)).toBe('—');
    expect(permissionModeLabel(undefined)).toBe('—');
    expect(permissionModeLabel('')).toBe('—');
  });
});

describe('R203: setPermissionMode action wires the mapping through the RPC', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('calls mapUiPermissionToDaemon before the RPC', () => {
    // The UI tier ('ask' / 'smart' / 'bypass')
    // must be mapped to the canonical enum
    // before hitting the daemon, otherwise
    // the daemon would see a value it doesn't
    // recognise and revert to DEFAULT.
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('const daemonMode = mapUiPermissionToDaemon(mode)');
    expect(block![0]).toContain('await rpc.setPermissionMode(daemonMode)');
  });

  it('keeps engineState.permissionMode on the canonical enum (for the StatusBar label)', () => {
    // The StatusBar reads engineState.permissionMode
    // and runs it through permissionModeLabel.
    // The label needs the canonical enum, not the
    // UI tier, so the helper can map DEFAULT →
    // 主动询问 (and the other UI labels) etc. (We could store the UI tier
    // in engineState and map the other way, but
    // keeping the canonical enum means the wire
    // surface stays consistent with the daemon
    // snapshot.)
    //
    // the value is now read from
    // `effectiveDaemonMode` (a local that the R204
    // rejection path overrides with the daemon's
    // response mode) instead of the literal
    // `daemonMode`. The store still writes the
    // canonical enum; only the variable name
    // changed to accommodate the rejection branch.
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?\n\s{4}\},\s*\n\s{4}\/\/ R198/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/engineState:\s*s\.engineState\s*\?\s*\{\s*\.\.\.s\.engineState,\s*permissionMode:\s*effectiveDaemonMode\s*\}/);
  });

  it('keeps the standalone permissionMode on the UI tier (so the dropdown stays in sync)', () => {
    // The dropdown binds via
    // <select value={permissionMode}>. The
    // value MUST match one of the 3 option
    // values ('ask' / 'smart' / 'bypass') or
    // the browser will show the wrong option
    // (it falls back to the first option when
    // value doesn't match any).
    //
    // the value is now read from
    // `effectiveMode` (a local that the rejection
    // path overrides with mapDaemonToUiPermission
    // of the daemon's response mode). On success
    // `effectiveMode` is just the user's input
    // `mode` — the UX is unchanged. The variable
    // name is the only diff.
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?\n\s{4}\},\s*\n\s{4}\/\/ R198/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/permissionMode:\s*effectiveMode,/);
  });
});

describe('R203: initialize() applies persisted UI tier through the mapping', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('persisted prefs.permissionMode is mapped before the RPC', () => {
    // The localStorage value is a UI tier
    // ('ask' / 'smart' / 'bypass') afterward.
    // The apply block must map it to the
    // canonical enum before calling
    // rpc.setPermissionMode.
    expect(storeSrc).toMatch(/const daemonMode = mapUiPermissionToDaemon\(prefs\.permissionMode\)/);
  });

  it('persisted UI tier survives a build downgrade (legacy enum round-trips)', () => {
    // The mapUiPermissionToDaemon function
    // passes through unknown values, so a
    // legacy raw-enum value (e.g. 'ACCEPT_EDITS'
    // from a v0.2.45 build) round-trips
    // unchanged. The StatusBar / dropdown
    // will see the canonical value and
    // behave correctly.
    expect(mapUiPermissionToDaemon('ACCEPT_EDITS')).toBe('ACCEPT_EDITS');
  });
});

describe('R203: refreshEngineState maps daemon enum → UI tier for the dropdown', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('uses mapDaemonToUiPermission when patching the standalone field', () => {
    // The 15s periodic engineState refresh
    // updates the standalone permissionMode
    // field (which the dropdown binds to).
    // The value MUST be a UI tier or the
    // dropdown will show the wrong option.
    expect(storeSrc).toMatch(/permissionMode:\s*r\.permissionMode\s*\?\s*mapDaemonToUiPermission\(r\.permissionMode\)/);
  });
});

describe('R203: StatusBar renders via permissionModeLabel', () => {
  const sbSrc = readFileSync(join(root, 'src', 'components', 'StatusBar.tsx'), 'utf-8');

  it('imports permissionModeLabel from the store', () => {
    expect(sbSrc).toContain('permissionModeLabel');
  });

  it('uses permissionModeLabel on engineState.permissionMode (not the raw enum)', () => {
    // The legacy StatusBar rendered
    // {engineState.permissionMode} verbatim,
    // showing 'ASK_BEFORE_TOOL' to the user.
    // R203 routes the value through
    // permissionModeLabel so the bar shows
    // 主动询问 / 智能授权 / 始终授权 (the user-facing labels) instead.
    expect(sbSrc).toContain('permissionModeLabel(engineState.permissionMode)');
  });

  it('keeps the raw enum in the tooltip for power users', () => {
    // The tooltip shows BOTH the user-friendly
    // label and the canonical enum so a user
    // who needs to know "exactly which
    // PermissionMode is in effect" can hover.
    expect(sbSrc).toMatch(/title=\{`Permission mode: \$\{permissionModeLabel\([^)]+\)\} \(\$\{engineState\.permissionMode\}\)`\}/);
  });
});

describe('R203: MessageInput dropdown uses UI_PERMISSION_MODES (single source of truth)', () => {
  const miSrc = readFileSync(join(root, 'src', 'components', 'MessageInput.tsx'), 'utf-8');

  it('imports UI_PERMISSION_MODES from the store', () => {
    // The legacy MessageInput had a local
    // PERMISSION_MODES constant. R203 deleted
    // it in favour of UI_PERMISSION_MODES so
    // the dropdown labels stay in sync with
    // the mapping the store uses.
    expect(miSrc).toContain("import { useStore, UI_PERMISSION_MODES } from '../store'");
  });

  it('does not declare a local PERMISSION_MODES array (the local one is gone)', () => {
    // The local PERMISSION_MODES had 3 entries
    // with the same labels but no daemon
    // field. R203 deletes it. A refactor that
    // re-introduces a local copy would risk
    // label / value drift from the store.
    expect(miSrc).not.toMatch(/const PERMISSION_MODES\s*=/);
  });

  it('renders one <option> per UI_PERMISSION_MODES entry', () => {
    // The dropdown's <option> list is
    // generated by mapping UI_PERMISSION_MODES
    // — not a hard-coded array.
    expect(miSrc).toMatch(/\{UI_PERMISSION_MODES\.map\(\(p\)\s*=>\s*<option key=\{p\.value\} value=\{p\.value\} title=\{p\.title\}>\{p\.label\}<\/option>\)\}/);
  });
});

describe('R203: PermissionPromptBanner shows the active mode', () => {
  const pbSrc = readFileSync(join(root, 'src', 'components', 'PermissionPromptBanner.tsx'), 'utf-8');

  it('imports permissionModeLabel from the store', () => {
    expect(pbSrc).toContain('permissionModeLabel');
  });

  it('renders the current mode via permissionModeLabel', () => {
    // The banner shows a small chip with the
    // active mode so the user can see "why am
    // I being asked". 主动询问 (ask) = the model
    // tried a non-read-only tool; 智能授权 (smart) =
    // the same but the policy could have
    // auto-allowed if the call was read-only.
    expect(pbSrc).toMatch(/permissionModeLabel\(engineState\?\.permissionMode\)/);
  });

  it('only renders the chip when engineState is loaded (defensive)', () => {
    // engineState is null on first paint
    // (before initialize() finishes). The
    // chip should not render in that case —
    // permissionModeLabel returns '—' for
    // null and the chip uses the literal
    // value to gate the JSX.
    expect(pbSrc).toMatch(/currentModeLabel !== '—' && \(/);
  });
});
