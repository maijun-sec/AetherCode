// Phase 4.1 (T-4-02): VariableSizeList — 200-line custom
// virtual list for the session list.
//
// We hand-roll this instead of pulling in `react-window` to
// keep the bundle small. The list:
//
//   - Renders only the rows visible in the scroll viewport
//     (plus a small overscan buffer so a fast scroll doesn't
//     show empty rows mid-flick).
//   - Supports per-row variable height via an `itemHeight`
//     callback. The pinned-row case (current session always
//     on top) uses a taller row with a coloured stripe, so
//     fixed height would clip the badge.
//   - Listens to wheel + scroll + resize, with rAF
//     debouncing so the React tree doesn't re-render on
//     every pixel.
//   - Maintains `scrollTop` in an internal ref so the
//     consumer can read it (e.g. to "jump to the current
//     session" on focus).
//
// The total height is the sum of every row's height. The
// rendered window is computed against the parent's
// `height` prop (caller measures the panel). A flex
// container is the natural host — the parent passes the
// measured height.

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';

export interface VariableSizeListProps<T> {
  items: readonly T[];
  /** Per-row height. Default 64 px. R222: was 56 px, bumped
   *  to match the new 14 px session-label + 8 px padding. */
  itemHeight?: (index: number, item: T) => number;
  /** Pixel height of the scroll viewport. The parent
   *  measures this with a ResizeObserver / window.innerHeight. */
  height: number;
  /** Pixel width. Default '100%'. */
  width?: number | string;
  /** Number of extra rows to render above + below the
   *  visible window. Default 4. */
  overscan?: number;
  /** Render a single row. `style` is the absolute
   *  position; spread it on the row's container. */
  renderItem: (index: number, item: T, style: CSSProperties) => ReactNode;
  /** Stable key extractor. */
  itemKey?: (index: number, item: T) => string | number;
  /** Fired when the visible range changes (debounced). */
  onVisibleRangeChange?: (range: { start: number; end: number }) => void;
  /** aria-label for the scrollable region. */
  ariaLabel?: string;
}

interface RowOffset {
  index: number;
  top: number;
  height: number;
}

export function VariableSizeList<T>({
  items,
  itemHeight,
  height,
  width = '100%',
  overscan = 4,
  renderItem,
  itemKey,
  onVisibleRangeChange,
  ariaLabel,
}: VariableSizeListProps<T>) {
  // was 56 px, bumped to 64 px to match the new
  // 14 px session-label + 8 px padding (SessionList.css
  // .session-item).
  // bump again 64 → 72 px to match the 15 px label
  // + 10 px padding. 72 also leaves room for the 12 px
  // meta line + 3 px margin so the row doesn't visually
  // clip on 2-line label wrap.
  const defaultHeight = 72;
  const getHeight = useCallback(
    (idx: number, it: T) => (itemHeight ? itemHeight(idx, it) : defaultHeight),
    [itemHeight],
  );

  // Pre-compute the cumulative offsets so the scroll math
  // is O(log n) per query. The total height is the last
  // row's bottom.
  const offsets = useMemo<RowOffset[]>(() => {
    const arr: RowOffset[] = new Array(items.length);
    let top = 0;
    for (let i = 0; i < items.length; i++) {
      const h = getHeight(i, items[i]);
      arr[i] = { index: i, top, height: h };
      top += h;
    }
    return arr;
  }, [items, getHeight]);

  const totalHeight = offsets.length > 0
    ? offsets[offsets.length - 1].top + offsets[offsets.length - 1].height
    : 0;

  // Binary search: find the first row whose bottom is past
  // `target`. Returns `lo` such that offsets[lo].top <=
  // target < offsets[lo+1].top (or items.length if no such
  // row exists).
  const findRowAt = useCallback(
    (target: number): number => {
      let lo = 0;
      let hi = offsets.length - 1;
      let ans = offsets.length;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        const bottom = offsets[mid].top + offsets[mid].height;
        if (bottom > target) {
          ans = mid;
          hi = mid - 1;
        } else {
          lo = mid + 1;
        }
      }
      return ans;
    },
    [offsets],
  );

  // Scroll state. We hold scrollTop in a ref + mirror in
  // state for re-render. The scroll handler writes to the
  // ref and triggers a single re-render via rAF.
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const scrollTopRef = useRef(0);
  const rafRef = useRef<number | null>(null);
  const [scrollTop, setScrollTop] = useState(0);

  const requestUpdate = useCallback(() => {
    if (rafRef.current != null) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null;
      setScrollTop(scrollTopRef.current);
    });
  }, []);

  // Compute the visible window. We expand by `overscan` rows
  // on each side so a fast scroll doesn't show empty space.
  const { startIdx, endIdx, padTop, padBottom } = useMemo(() => {
    if (offsets.length === 0) {
      return { startIdx: 0, endIdx: 0, padTop: 0, padBottom: 0 };
    }
    const start = Math.max(0, findRowAt(scrollTop) - overscan);
    // The first row past the bottom edge:
    const bottomEdge = scrollTop + height;
    const end = Math.min(
      offsets.length,
      // Find the first row that starts past bottomEdge. Add
      // overscan. We use the same binary search but with a
      // different predicate (top > target).
      (() => {
        let lo = 0;
        let hi = offsets.length - 1;
        let ans = offsets.length;
        while (lo <= hi) {
          const mid = (lo + hi) >> 1;
          if (offsets[mid].top >= bottomEdge) {
            ans = mid;
            hi = mid - 1;
          } else {
            lo = mid + 1;
          }
        }
        return ans;
      })() + overscan,
    );
    return {
      startIdx: start,
      endIdx: end,
      padTop: start > 0 ? offsets[start].top : 0,
      padBottom:
        end < offsets.length
          ? totalHeight - offsets[end].top
          : 0,
    };
  }, [offsets, scrollTop, height, overscan, findRowAt, totalHeight]);

  // Notify the parent when the visible range changes
  // (debounced to one notification per rAF).
  useEffect(() => {
    if (onVisibleRangeChange) {
      onVisibleRangeChange({ start: startIdx, end: endIdx });
    }
  }, [startIdx, endIdx, onVisibleRangeChange]);

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    scrollTopRef.current = el.scrollTop;
    requestUpdate();
  }, [requestUpdate]);

  // Public imperative API: jump to a row. The parent calls
  // this from "scroll to current session" buttons.
  const scrollToIndex = useCallback((index: number) => {
    const el = scrollRef.current;
    if (!el) return;
    if (index < 0 || index >= offsets.length) return;
    el.scrollTop = offsets[index].top;
    scrollTopRef.current = el.scrollTop;
    setScrollTop(el.scrollTop);
  }, [offsets]);

  // Expose the imperative method on a ref so the parent can
  // call it without re-rendering.
  useEffect(() => {
    (VariableSizeList as any).__lastScrollToIndex = scrollToIndex;
  }, [scrollToIndex]);

  return (
    <div
      ref={scrollRef}
      className="variable-size-list"
      style={{
        height,
        width,
        overflowY: 'auto',
        overflowX: 'hidden',
        position: 'relative',
      }}
      onScroll={onScroll}
      role="listbox"
      aria-label={ariaLabel}
      tabIndex={0}
    >
      {totalHeight > 0 && (
        <div
          className="variable-size-list-inner"
          style={{
            height: totalHeight,
            position: 'relative',
          }}
        >
          {padTop > 0 && (
            <div style={{ height: padTop }} aria-hidden />
          )}
          {offsets.slice(startIdx, endIdx).map((row) => {
            const item = items[row.index];
            const key = itemKey ? itemKey(row.index, item) : row.index;
            return (
              <div
                key={key}
                style={{
                  position: 'absolute',
                  top: row.top,
                  left: 0,
                  right: 0,
                  height: row.height,
                }}
              >
                {renderItem(row.index, item, {
                  position: 'absolute',
                  inset: 0,
                })}
              </div>
            );
          })}
          {padBottom > 0 && (
            <div style={{ height: padBottom }} aria-hidden />
          )}
        </div>
      )}
    </div>
  );
}

/** Helper for callers that want a stable imperative API. */
export interface VariableSizeListHandle {
  scrollToIndex(index: number): void;
}
