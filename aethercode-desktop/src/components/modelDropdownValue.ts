// pure helper that computes the
// `modelEntries`-compatible option id for a given
// `model` field. Extracted from MessageInput so the
// lookup logic is unit-testable without rendering
// the full component (and pulling in Tauri /
// WebSocket dependencies).
//
// <p>Background: the model dropdown's options are
// keyed by `${providerName}/${modelId}` (e.g.
// `minmax/MiniMax-M3`). The store's `model` field
// is the bare id (`MiniMax-M3`). The pre-fix
// dropdown set `<select value={model}>` and the
// browser fell back to the first option in the list
// (alphabetically first) whenever the value didn't
// match an option — so the dropdown always looked
// like the user's "default" was whatever model
// sorted first, not what the daemon was actually
// using. prior round fixes this by looking up the entry
// whose id ends with `/<model>` and using that
// entry's id as the select value.
//
// <p>Edge cases:
// <ul>
//   <li>`model` is empty / nullish → empty
//       string (placeholder shows)</li>
//   <li>`model` matches multiple entries
//       (e.g. the same model id exists in two
//       providers — unlikely but possible) → first
//       match wins, the same as `find()`</li>
//   <li>`model` is not in the list (e.g. the
//       daemon is using a model the local
//       providers cache doesn't know about) →
//       fall back to the bare model so the
//       placeholder line shows it instead of
//       silently picking the first option</li>
// </ul>

export type ModelEntry = { id: string; label: string; provider: string };

export function computeModelDropdownValue(
  model: string | null | undefined,
  entries: readonly ModelEntry[],
): string {
  if (!model) return '';
  const match = entries.find((e) => e.id.endsWith('/' + model));
  if (match) return match.id;
  return model;
}
