// Phase 5: PresetSelector (T-5-07) — three-card picker for the
// permission preset. Click a card to apply the preset; the active
// preset is highlighted.

import { useSetPreset } from '../../rpc/mutations';
import { useApp } from '../../state/AppContext';
import type { PermissionPreset } from '../../rpc/types';

interface PresetDescriptor {
  id: PermissionPreset;
  label: string;
  description: string;
  allowed: string[];
  prompted: string[];
}

const PRESETS: PresetDescriptor[] = [
  {
    id: 'permissive',
    label: 'Permissive',
    description: 'Auto-allow safe reads; prompt for everything else.',
    allowed: ['read_file', 'glob_files', 'grep_files'],
    prompted: ['bash', 'edit_file', 'write_file', 'web_fetch', 'web_search'],
  },
  {
    id: 'cautious',
    label: 'Cautious',
    description: 'Prompt for everything except reads. (Recommended)',
    allowed: ['read_file', 'glob_files'],
    prompted: ['bash', 'edit_file', 'write_file', 'grep_files', 'web_fetch', 'web_search'],
  },
  {
    id: 'strict',
    label: 'Strict',
    description: 'Deny bash and write_file until explicitly allowed.',
    allowed: ['read_file', 'glob_files'],
    prompted: ['bash', 'edit_file', 'write_file', 'grep_files', 'web_fetch', 'web_search'],
  },
];

export interface PresetSelectorProps {
  /** Override the active preset. Defaults to the AppContext value. */
  activePreset?: PermissionPreset;
  /** Optional callback fired after a successful apply. */
  onApplied?: (preset: PermissionPreset) => void;
}

export function PresetSelector({ activePreset, onApplied }: PresetSelectorProps) {
  const { permissionPreset, setPermissionPreset } = useApp();
  const setPreset = useSetPreset();
  const active = activePreset ?? permissionPreset;

  return (
    <div className="preset-selector" data-testid="preset-selector" data-active={active}>
      {PRESETS.map((p) => (
        <button
          key={p.id}
          type="button"
          data-testid={`preset-card-${p.id}`}
          className={`preset-card ${active === p.id ? 'active' : ''}`}
          disabled={setPreset.isPending}
          aria-pressed={active === p.id}
          onClick={() => {
            setPermissionPreset(p.id);
            setPreset.mutate(
              { preset: p.id },
              { onSuccess: () => onApplied?.(p.id) },
            );
          }}
        >
          <span className="preset-card-label">{p.label}</span>
          <span className="preset-card-desc">{p.description}</span>
          <span className="preset-card-detail">
            <span><strong>Allow:</strong> {p.allowed.join(', ')}</span>
            <span><strong>Prompt:</strong> {p.prompted.join(', ')}</span>
          </span>
        </button>
      ))}
    </div>
  );
}

export const PRESET_LIST = PRESETS;
