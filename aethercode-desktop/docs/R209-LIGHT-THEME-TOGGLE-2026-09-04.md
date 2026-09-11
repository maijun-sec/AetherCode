# R209: Light theme toggle (white + light grey)

**Version**: v0.2.50 (R209 重 build)
**Date**: 2026-09-04
**Type**: feature
**Symptom**: 用户 "我个人更喜欢白色+灰色的浅色系背景",当前 desktop 是全深色(`#1e1e1e` 背景, `#d4d4d4` 文本)。

## 现状

- `aethercode-themes/src/themes/light.ts` **已经存在**完整 light theme(`#ffffff` 背景, `#1f2328` 文本, `#0969da` accent, 等)
- `aethercode-themes/src/default-theme.ts` 已经声明 **`APP_DEFAULT_THEME_NAME = 'light'`**,注释说"the new app default (was `dark`)"
- `ThemeSettings.tsx` 也存在完整的 settings UI(用户可写 YAML 主题)
- **但整套系统没被 wire 进 App.tsx** — `ThemeSettings` 没 mount,`App.css` 强制深色 token,R107-D 历史选择(三列统一深色)
- 用户目前看到的是 `global.css` `:root` 的深色 token + `App.css` `.center` 重新定义的深色 token

R209 不接 aethercode-themes 完整系统(hot-reload + user YAML + RPC + persistence 太重),而是:
1. 加一个 binary `theme: 'dark' | 'light'` 切换
2. CSS 浅色 token override 在 `[data-theme="light"]` 下
3. localStorage 持久化
4. Header 一个 ☀/☾ 按钮
5. 启动时同步 apply(避免 dark → light 闪烁)

未来可以接 aethercode-themes 完整系统作为 follow-up(用户 YAML / 内置 theme 列表 / live preview / hot-reload)。

## 修改

### `aethercode-desktop/src/store/index.ts`

**1. `EnginePrefs` interface 加 `theme?: 'dark' | 'light'`** (持久化到 localStorage `aethercode.enginePrefs`)

**2. `AppState` interface 加 `theme: 'dark' | 'light'` + `setTheme: (t) => void`** action

**3. 初始 state** `theme: 'dark'`(保持 pre-R209 默认)

**4. `initialize()` 读 prefs.theme,set store state + apply data-theme 到 root**:
```ts
const restoredTheme = prefs.theme === 'light' ? 'light' : 'dark';
set({ theme: restoredTheme });
try {
  document.documentElement.setAttribute('data-theme', restoredTheme);
} catch { /* jsdom / SSR */ }
```

**5. `setTheme(t)` action** — set state + setAttribute + writeEnginePrefs:
```ts
setTheme: (t) => {
  set({ theme: t });
  try { document.documentElement.setAttribute('data-theme', t); } catch {}
  try {
    const prefs = readEnginePrefs();
    (prefs as Record<string, unknown>).theme = t;
    writeEnginePrefs(prefs);
  } catch {}
},
```

### `aethercode-desktop/src/styles/global.css`

**`:root[data-theme="light"]` 浅色 token override**:

```css
:root[data-theme="light"] {
  --bg: #ffffff;
  --bg-elevated: #f6f8fa;
  --bg-input: #f0f2f5;
  --border: #d0d7de;
  --border-soft: rgba(31, 35, 40, 0.10);
  --text: #1f2328;
  --text-dim: #6e7781;
  --accent: #0969da;
  --accent-hover: #0550ae;
  --success: #1a7f37;
  --error: #cf222e;
  --warning: #9a6700;
  --chat-surface: #ffffff;
  --chat-surface-2: #f6f8fa;
  --chat-code-bg: #f0f2f5;
  --chat-user-bubble: #ddf4ff;
  --chat-user-bubble-border: #b6e3ff;
  --chat-text: #1f2328;
  --chat-text-muted: #6e7781;
  --chat-divider: #d0d7de;
  --chat-accent-soft: rgba(9, 105, 218, 0.12);
  --chat-accent: #0969da;
  --chat-success-soft: rgba(26, 127, 55, 0.12);
  --chat-success: #1a7f37;
  --chat-error-soft: rgba(207, 34, 46, 0.12);
  --chat-error: #cf222e;
  --chat-warning-soft: rgba(154, 103, 0, 0.14);
  --chat-warning: #9a6700;
}
```

GitHub-style 调色板,跟 `aethercode-themes/src/themes/light.ts` 同步。

### `aethercode-desktop/src/App.css`

**`.center[data-theme="light"]` 浅色 token override**:
R107-D 显式在 `.center` scope 重新定义了 dark token(因为 chat cards 用 `--chat-*` token)。
R209 加同样 shape 的 light override,保证 chat 区跟 header / leftpanel 统一。

### `aethercode-desktop/src/main.tsx`

**启动时同步 apply data-theme attribute** — 避免 dark → light 闪烁:

```ts
// R209: apply the persisted theme attribute SYNCHRONOUSLY
// before React mounts, so the very first paint already uses
// the right CSS variable set.
try {
  const raw = window.localStorage.getItem('aethercode.enginePrefs');
  const parsed = raw ? JSON.parse(raw) as { theme?: string } : null;
  const theme = parsed && parsed.theme === 'light' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', theme);
} catch { /* SSR / private mode */ }
```

(在 `ReactDOM.createRoot()` 之前同步执行,而不是放在 React effect 里,否则首屏 dark 然后 useEffect 触发再 light,user 会看到一帧 dark。)

### `aethercode-desktop/src/components/Header.tsx`

**加 ☀/☾ 切换按钮**(在 Telemetry button 之后,Settings 之前):

```tsx
<button
  className="header-icon-btn header-theme-toggle"
  title={theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme'}
  aria-label={theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme'}
  onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
>
  {theme === 'light' ? '☀' : '☾'}
</button>
```

### `aethercode-desktop/src/styles/themeR209.test.ts`

新建 13 个 test (source-pin + behavioural):

1. `EnginePrefs.theme?: "dark" | "light"` declared
2. `AppState.theme` + `setTheme` declared
3. Initial state defaults to `dark` (pre-R209 behaviour)
4. `initialize()` reads `prefs.theme` + applies `data-theme`
5. `setTheme()` writes back to `prefs.theme` (round-trip)
6. `main.tsx` reads `aethercode.enginePrefs` + sets `data-theme` synchronously
7. `Header.tsx` destructures `theme, setTheme` from `useStore`
8. `Header.tsx` button flips via `setTheme(theme === 'light' ? 'dark' : 'light')`
9. `global.css` has `:root[data-theme="light"]` block
10. `global.css` light block re-binds core palette (`--bg` / `--text` / `--accent`)
11. `App.css` has `.center[data-theme="light"]` block
12. Behavioural: `setAttribute("data-theme", "light")` observed on `<html>`
13. Behavioural: `prefs.theme` round-trips through `localStorage`

## Test 跑分

| 套件 | 之前 | R209 后 | 增量 |
|------|------|---------|------|
| `src/styles/themeR209.test.ts` (新) | — | **13** | +13 (R209 source-pin + behavioural) |
| `src/**/*.test.{ts,tsx}` (全部) | 1000 | **1013** | +13 |

**1013/1013 通过,无 regression。**

## Build

- exe: `AetherCode.exe` 3,965,440 bytes (+2,048 vs R208 build 3,963,392,
  setTheme action + Header 按钮 + CSS overrides + tests)
  SHA256 `00D5E670F7E1A40A534E803BAB3D255019E4F8E2F10E1749A682D7C09DE17BDF`
- setup.exe (NSIS): 2,119,140 bytes
- msi: 2,600,960 bytes

jar / jar SHA256 跟 v0.2.50 R207/R208 build 一样(没改 Java),
`05BCAFD3952E12D2BB70E6626F6D2BBA2B39880107F758F344B4189194D8146C`。

Release 目录: `release/aethercode-0.2.50/`

## 后续 (R210+)

- 接 aethercode-themes 完整系统:`ThemeStore` / `ThemeSettings.tsx` (已经存在但没 mount) / hot-reload / user YAML
- 加更多 preset 主题(solarized-light 已经在 aethercode-themes 里,可以直接读)
- 把 `data-theme` attribute 同步到 `data-color-scheme` for `meta[name=color-scheme]`(mobile / browser chrome)

## 教训 (2026-09-04)

1. **"Default" 在文档里写 ≠ 在代码里实现** — `default-theme.ts` 早就声明
   `APP_DEFAULT_THEME_NAME = 'light'`,但 App.css 强制深色 + ThemeSettings
   没 mount + store 没 theme state。R209 之前,light theme 是个 "design intent
   that the runtime never reached"。**教训:design.md / spec.md 写"default"
   时,顺手 grep 一下 ":root { --bg" 跟 ".center { --bg" 是不是真的跟
   default 一致;不一致就 quick-fix。**

2. **"启动时同步 apply" 是隐藏的"无 flash" contract** — useEffect
   是异步的,首帧永远是 dark 然后 useEffect 跑完才 light,user 看到
   一帧 dark 再 light。修法是 main.tsx 同步读 localStorage 设
   attribute,然后 React 第一次 render 时已经是 light 了。**教训:任何
   "apply 主题 / 语言 / font" 必须在 useEffect 之前同步执行,否则
   reload 会闪。**

3. **CSS variable re-skin 是最便宜的 theme 切换** — 不需要重 mount
   React tree,不需要重新跑 useEffect,只需要在 `<html>` 改一个
   attribute,然后所有 `var(--bg)` / `var(--text)` 引用都自动
   re-resolve。**教训:首轮 theme 系统用 CSS variable + attribute
   切换最简单,不要上来就接 Redux / Context / theme store。**

4. **aethercode-themes 已经存在但没 wire** — 这个项目的 `aethercode-themes`
   sibling package 是个完整的 theme 系统(built-in / user YAML / hot-reload),
   但 desktop 还没 mount 进 App.tsx。R209 是"轻量版":只 binary toggle,
   不接完整系统。未来 R210+ 可以把 aethercode-themes 完整接上,作为
   "power user" feature。**教训:看到 sibling 项目存在但没 wire,
   是个 follow-up 信号,不是 R209 该做的事(避免 scope creep)。**
