import { useEffect, useRef, useMemo } from 'react';
import { filterCommands, SlashCommand } from './commandCommands';
import './CommandDropdown.css';

/** Slash-command dropdown. Mounted inside MessageInput
 *  whenever the user has typed `/` and no space yet (so the
 *  dropdown stays open as they refine the command name). The
 *  dropdown:
 *    • shows the filtered command list
 *    • ↑↓ to navigate, Enter to invoke / insert, Esc to close
 *    • Tab cycles too (matches the CommandPalette behaviour)
 *    • click on a row to invoke
 *  When the user types a space (i.e. they started providing
 *  args), the dropdown hides and the input behaves normally.
 *
 *  Visual style: same modal/popover language as CommandPalette
 *  but smaller (max-width 360px) and anchored to the input.
 *  Each row is grouped by category via a subtle left-border
 *  colour. */

const CATEGORY_COLOR: Record<string, string> = {
  '会话':  'var(--accent, #5da9ff)',
  '上下文': '#b48dd9',
  '模型':   '#6cc28b',
  '权限':   '#f4a371',
  '工具':   'var(--text-dim)',
  '系统':   'var(--text-dim)',
};

export function CommandDropdown({
  query,
  onSelect,
  activeIdx: externalActiveIdx,
  onActiveIdxChange,
}: {
  query: string;
  onSelect: (cmd: SlashCommand) => void;
  /** parent-controlled active index. The parent
   *  (MessageInput) drives the highlight via the textarea's
   *  keydown handler; the dropdown is "dumb" and just renders
   *  the row. The parent passes the new index back via
   *  onActiveIdxChange when the user mouses over a row. */
  activeIdx: number;
  onActiveIdxChange: (i: number) => void;
}) {
  const items = useMemo(() => filterCommands(query), [query]);
  const listRef = useRef<HTMLUListElement>(null);

  // Scroll active into view.
  useEffect(() => {
    if (!listRef.current) return;
    const row = listRef.current.querySelector<HTMLElement>(`[data-idx="${externalActiveIdx}"]`);
    row?.scrollIntoView({ block: 'nearest' });
  }, [externalActiveIdx, items.length]);

  if (items.length === 0) {
    return (
      <div className="command-dropdown" role="listbox" aria-label="Slash commands">
        <div className="command-dropdown-empty">没有匹配的命令 (试试 /help)</div>
      </div>
    );
  }

  return (
    <div
      className="command-dropdown"
      role="listbox"
      aria-label="Slash commands"
      tabIndex={-1}
    >
      <ul className="command-dropdown-list" ref={listRef}>
        {items.map((c, idx) => (
          <li
            key={c.id}
            data-idx={idx}
            className={`command-dropdown-item ${idx === externalActiveIdx ? 'active' : ''}`}
            role="option"
            aria-selected={idx === externalActiveIdx}
            onMouseEnter={() => onActiveIdxChange(idx)}
            onMouseDown={(e) => { e.preventDefault(); onSelect(c); }}  /* mousedown so textarea doesn't blur first */
          >
            <span
              className="command-dropdown-cat"
              style={{ borderLeftColor: CATEGORY_COLOR[c.category] ?? 'var(--border)' }}
              title={c.category}
            />
            <span className="command-dropdown-label">{c.label}</span>
            <span className="command-dropdown-hint">{c.hint}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
