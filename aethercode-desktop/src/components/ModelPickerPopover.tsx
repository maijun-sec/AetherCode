import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  ProviderModelPicker,
  type ProviderInfo,
} from './ProviderModelPicker';
import './ModelPickerPopover.css';

/**
 * R342 — compact model picker for the MessageInput config bar.
 *
 * <p>The legacy R282 implementation rendered the model picker as a
 * single HTML {@code <select>} dropdown with every model across
 * every provider squashed into one flat list. The user reported
 * "现在 model 还是没办法筛选啊，只有一个 MiniMax-M3" — a closed
 * HTML {@code <select>} only shows the currently-selected option,
 * and there's no way to filter the list when it grows past a
 * handful of entries. With 7 providers × 3-7 models each, the
 * dropdown already had ~30 rows that were unsearchable.
 *
 * <p>This component exposes the full R341 2-level picker
 * (chip row + debounced filter + model list) behind a compact
 * trigger button. The trigger button stays one line in the
 * input config bar (showing {@code <provider>/<model>}), and
 * clicking it pops a popover that overlays the chat column.
 * Selecting a model from the popover fires
 * {@link #onSwitch} and closes the popover; switching chips
 * stays internal (the chip row in the popover picks which
 * provider's models to show, no store call yet).
 *
 * <p>Why a popover instead of inlining the picker:
 * <ul>
 *   <li>The input config bar already carries 6 controls
 *       (Thinking, SDD, Quality, Model, Perm, Cwd, Workflow);
 *       chip-row + filter + model-list would explode the
 *       horizontal layout.</li>
 *   <li>The picker is only useful when actively browsing
 *       — the trigger button is the constant.</li>
 *   <li>A popover mirrors the existing Workflow picker
 *       (R163) affordance for "click to expand a model list"
 *       so users don't have to learn a new pattern.</li>
 * </ul>
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Uses {@link ProviderModelPicker} in uncontrolled mode
 *       (no {@code selectedProvider} prop) — chip selection
 *       stays internal until the user picks a model.</li>
 *   <li>Click-outside detection: a {@code mousedown} listener
 *       on {@code document} that closes the popover when the
 *       target isn't inside the popover or trigger ref.
 *       Cleaned up on unmount / open-flip.</li>
 *   <li>Escape key closes the popover (matches browser
 *       dropdown conventions).</li>
 *   <li>Popover placement: anchored to the trigger button's
 *       top edge (the popover grows downward). Falls back to
 *       upward growth if the trigger is in the lower half of
 *       the viewport (the chat input is usually near the
 *       bottom of the column, so the popover should grow up
 *       to avoid clipping by the viewport edge).</li>
 * </ul>
 */
export interface ModelPickerPopoverProps {
  /** Full provider list from the daemon's listProviders RPC.
   *  Pre-filtered by hasApiKey (the legacy R282 dropdown did
   *  this too — providers without an API key configured in
   *  the daemon's env shouldn't pollute the picker). */
  providers: readonly ProviderInfo[];
  /** The daemon's currently-active provider name. The
   *  trigger button label uses it; the picker's "(current)"
   *  highlight uses it. */
  currentProvider: string | null;
  /** The daemon's currently-active model id (bare, not
   *  provider-prefixed). The trigger button label uses it. */
  currentModel: string | null;
  /** Single seam — the popover delegates to the parent so
   *  MessageInput can route through {@code switchProvider}
   *  (which the store action wires to the daemon RPC). */
  onSwitch: (provider: string, model: string) => Promise<void> | void;
  /** Disable interaction (during streaming, etc.). The
   *  trigger button greys out; the popover can't open. */
  disabled?: boolean;
}

export function ModelPickerPopover(props: ModelPickerPopoverProps) {
  const {
    providers,
    currentProvider,
    currentModel,
    onSwitch,
    disabled = false,
  } = props;
  // open state for the popover. closed by default — the
  // trigger button is the constant in the input bar.
  const [open, setOpen] = useState(false);
  // anchor refs for the click-outside detector. The trigger
  // lives inside .r342-model-picker (rootRef); the popover
  // is rendered via createPortal at document.body so we
  // need a separate popoverRef to keep it inside the
  // "click is mine" zone — otherwise clicking a chip in
  // the popover would trip the click-outside detector and
  // close the popover mid-interaction.
  const rootRef = useRef<HTMLDivElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  // Popover position + placement direction. Computed from
  // the trigger's bounding rect at open time and on
  // scroll/resize while open. The popover is rendered
  // through createPortal at document.body level (NOT as a
  // child of the input config bar) because .input-config-bar
  // has `overflow-x: auto` and clips absolutely-positioned
  // descendants — the popover would be invisible.
  const [pos, setPos] = useState<{ left: number; top: number; placement: 'up' | 'down' } | null>(null);
  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (e: MouseEvent) => {
      const root = rootRef.current;
      const popover = popoverRef.current;
      // A click inside either the trigger or the portaled
      // popover is "ours" — don't close. Anything else is
      // outside and should close.
      if (e.target instanceof Node) {
        if (root && root.contains(e.target)) return;
        if (popover && popover.contains(e.target)) return;
      }
      setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDocMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  // when the trigger flips open, recompute placement so
  // the popover doesn't clip the viewport. The popover is
  // rendered via createPortal at document.body so the
  // absolute coordinates are page-relative.
  const onTriggerClick = () => {
    if (disabled) return;
    setOpen((prev) => {
      const next = !prev;
      if (next && rootRef.current) {
        const rect = rootRef.current.getBoundingClientRect();
        const viewportH = window.innerHeight || document.documentElement.clientHeight;
        const spaceBelow = viewportH - rect.bottom;
        // Picker is ~360px tall with 7 providers; if there's
        // less than 380px below the trigger, grow upward so
        // the popover doesn't get clipped by the viewport
        // edge. Otherwise grow downward (the trigger sits in
        // the lower config bar of the chat column).
        const placement: 'up' | 'down' = spaceBelow < 380 ? 'up' : 'down';
        // left edge: align with trigger's left, but never
        // overflow the right edge of the viewport (the
        // popover can be up to 520px wide).
        const popWidth = 520;
        const margin = 8;
        let left = rect.left;
        const maxLeft = window.innerWidth - popWidth - margin;
        if (left > maxLeft) left = Math.max(margin, maxLeft);
        // top / bottom: page-relative for createPortal.
        const top = placement === 'down'
          ? rect.bottom + 6
          : rect.top - 6; // anchor the popover's bottom at top - 6
        setPos({ left, top, placement });
      }
      return next;
    });
  };
  // Reposition on scroll / resize while open so the
  // popover stays glued to the trigger button.
  useEffect(() => {
    if (!open) return;
    const reposition = () => {
      if (!rootRef.current) return;
      const rect = rootRef.current.getBoundingClientRect();
      const viewportH = window.innerHeight || document.documentElement.clientHeight;
      const spaceBelow = viewportH - rect.bottom;
      const placement: 'up' | 'down' = spaceBelow < 380 ? 'up' : 'down';
      const popWidth = 520;
      const margin = 8;
      let left = rect.left;
      const maxLeft = window.innerWidth - popWidth - margin;
      if (left > maxLeft) left = Math.max(margin, maxLeft);
      const top = placement === 'down' ? rect.bottom + 6 : rect.top - 6;
      setPos({ left, top, placement });
    };
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
    };
  }, [open]);
  // resolve the label parts. Falls back to the first
  // visible provider's default model when the daemon
  // reports a model the local cache doesn't know about
  // (e.g. a model from a custom providers.yaml entry that
  // hasn't been refreshed into the store yet).
  const labelProvider = currentProvider ?? providers[0]?.name ?? '—';
  const labelModel = currentModel ?? providers.find((p) => p.name === labelProvider)?.defaultModel ?? '—';
  const onPick = async (providerName: string, modelId: string) => {
    // close first so the user sees the trigger label flip
    // immediately (optimistic); the actual store call
    // races in the background. If the switch fails the
    // label reverts on the next engineState refresh.
    setOpen(false);
    await onSwitch(providerName, modelId);
  };
  return (
    <div
      ref={rootRef}
      className={['r342-model-picker', open ? 'r342-model-picker-open' : ''].filter(Boolean).join(' ')}
    >
      <button
        type="button"
        className="r342-model-picker-trigger"
        onClick={onTriggerClick}
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={open}
        data-testid="r342-model-picker-trigger"
        title={`${labelProvider}/${labelModel} — click to change`}
      >
        <span className="r342-model-picker-trigger-label">
          <span className="r342-model-picker-trigger-provider">{labelProvider}</span>
          <span className="r342-model-picker-trigger-sep">/</span>
          <span className="r342-model-picker-trigger-model">{labelModel}</span>
        </span>
        <span className="r342-model-picker-trigger-caret" aria-hidden="true">▾</span>
      </button>
      {open && pos && typeof document !== 'undefined'
        ? createPortal(
          <div
            ref={popoverRef}
            className={[
              'r342-model-picker-popover',
              pos.placement === 'up' ? 'r342-model-picker-popover-up' : 'r342-model-picker-popover-down',
            ].filter(Boolean).join(' ')}
            style={{
              position: 'fixed',
              left: pos.left,
              top: pos.placement === 'down' ? pos.top : undefined,
              bottom: pos.placement === 'up'
                ? `calc(100vh - ${pos.top}px)`
                : undefined,
            }}
            role="dialog"
            aria-label="Pick a model"
            data-testid="r342-model-picker-popover"
          >
            <ProviderModelPicker
              providers={providers}
              currentProvider={currentProvider}
              currentModel={currentModel}
              onSwitch={onPick}
            />
          </div>,
          document.body
        )
        : null}
    </div>
  );
}