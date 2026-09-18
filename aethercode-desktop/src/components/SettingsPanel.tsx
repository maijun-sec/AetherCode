import { useEffect, useRef, useState } from 'react';
import { useStore } from '../store';
import './SettingsPanel.css';

interface SettingsPanelProps { onClose: () => void; }
const PERMISSION_MODES = [
  { value: 'default', label: 'Default' },
  { value: 'acceptEdits', label: 'Accept Edits' },
  { value: 'bypassPermissions', label: 'Bypass' },
  { value: 'plan', label: 'Plan Mode' },
];
// prior round: concurrency profile options. The
// engine's ConcurrencyController accepts three profiles
// that map to (queries, tools, branches) semaphores.
// The labels include a short description so the user
// knows what they're picking without having to hover.
const CONCURRENCY_PROFILES = [
  { value: 'low',    label: 'Low    (1q / 2t / 1b — low-end machines)' },
  { value: 'normal', label: 'Normal (1q / 4t / 2b — default)' },
  { value: 'high',   label: 'High   (2q / 8t / 4b — beefy desktops)' },
];

export function SettingsPanel({ onClose }: SettingsPanelProps) {
  const {
    engineState, setPermissionMode,
    refreshModels,
    engineStats, requestConcurrencyProfile,
    availableProviders, currentProvider,
    refreshProviders, switchProvider,
    // the loop detector threshold tweak. Pulled
    // from the store so the slider state can be
    // optimistic (the RPC is fire-and-forget — the
    // canonical state lands via the 15s
    // refreshEngineState() tick in the background).
    setLoopDetectorThresholds,
    // supervisor auto-restart toggle.
    // The boolean is cached in the store so
    // re-opening the panel keeps the value
    // without a fresh getState round-trip.
    autoRestart, setAutoRestart: setAutoRestartStore,
    // explicit "force fresh daemon" action.
    // The normal setCwd path no longer kills the
    // daemon (it uses bindSessionCwd in-place);
    // resetDaemon is the deliberate escape hatch.
    resetDaemon,
  } = useStore();
  // local optimistic state for the
  // auto-restart toggle. The daemon round-trip
  // is fire-and-forget; the canonical value
  // lands via the same 15s tick. The 250ms
  // debounce below keeps the WS calm during
  // a rapid toggle.
  const [localAutoRestart, setLocalAutoRestart] = useState(autoRestart);
  const [autoRestartStatus, setAutoRestartStatus] = useState<'idle' | 'pushing' | 'error'>('idle');
  const [autoRestartError, setAutoRestartError] = useState<string | null>(null);
  useEffect(() => {
    // 250ms debounce so a quick double-click
    // doesn't fire two RPCs. The status
    // pill mirrors the loop detector's
    // "pushing / idle / error" UX.
    if (localAutoRestart === autoRestart) return;
    setAutoRestartStatus('pushing');
    setAutoRestartError(null);
    const t = window.setTimeout(async () => {
      try {
        const r = await setAutoRestartStore(localAutoRestart);
        if (r.ok) {
          setAutoRestartStatus('idle');
        } else {
          setAutoRestartStatus('error');
          setAutoRestartError(r.enabled ? 'daemon rejected enable' : 'daemon rejected disable');
        }
      } catch (e: any) {
        setAutoRestartStatus('error');
        setAutoRestartError(e?.message ?? String(e));
      }
    }, 250);
    return () => window.clearTimeout(t);
  }, [localAutoRestart, autoRestart, setAutoRestartStore]);
  const [perm, setLocalPerm] = useState(engineState?.permissionMode ?? 'default');
  // bind the profile dropdown to the engine's
  // current profile. The daemon is the source of truth
  // (the constructor picks a profile based on the CLI
  // flag / default); we read it from the latest
  // engineStats snapshot which carries the field.
  const currentProfile = engineStats?.concurrencyProfile ?? 'normal';
  const [profile, setLocalProfile] = useState<'low' | 'normal' | 'high'>(
    (currentProfile as 'low' | 'normal' | 'high') ?? 'normal'
  );
  // local state for the loop-detector sliders.
  // Initialised from engineState.loopWindow /
  // loopThreshold (the daemon's canonical values).
  // The "enabled" toggle maps to a sentinel pair
  // (window=0, threshold=0) which the daemon
  // interprets as "disable the detector entirely".
  // legacy the user could only change these by
  // re-spawning the JVM; the Settings panel now
  // exposes them as live-tweakable sliders.
  const initialLoopEnabled = (engineState?.loopWindow ?? 0) > 0 && (engineState?.loopThreshold ?? 0) > 0;
  const [loopEnabled, setLoopEnabled] = useState(initialLoopEnabled);
  const [loopWindow, setLoopWindow] = useState(Math.max(2, engineState?.loopWindow ?? 8));
  const [loopThreshold, setLoopThreshold] = useState(Math.max(1, Math.min(loopWindow, engineState?.loopThreshold ?? 3)));
  // debounce the slider → RPC push. 250 ms is
  // long enough that a fast drag doesn't fire one RPC
  // per pixel, short enough that the detector adjusts
  // in near-real-time. The timer is module-scope'd
  // via useRef so a re-render doesn't reset it.
  const loopTimerRef = useRef<number | null>(null);
  const [loopStatus, setLoopStatus] = useState<'idle' | 'pushing' | 'error'>('idle');
  const [loopError, setLoopError] = useState<string | null>(null);
  // when the engine's engineState changes
  // (a periodic refresh or a switchProvider
  // side-effect), re-sync the local slider state so
  // the UI doesn't drift from the canonical values.
  // This is the "bind daemon → UI" half of the
  // bidirectional sync; the "bind UI → daemon" half
  // is the debounced RPC above.
  useEffect(() => {
    const w = engineState?.loopWindow;
    const t = engineState?.loopThreshold;
    if (w == null || t == null) return;
    const enabled = w > 0 && t > 0;
    setLoopEnabled(enabled);
    if (enabled) {
      setLoopWindow(w);
      setLoopThreshold(Math.min(t, w));
    }
  }, [engineState?.loopWindow, engineState?.loopThreshold]);
  // debounced push. The dep on the slider
  // values means the timer resets on every change;
  // 250 ms after the last change the actual RPC
  // fires. Cancelled in the cleanup so a remount
  // during the debounce window doesn't fire a
  // stale RPC.
  useEffect(() => {
    if (loopTimerRef.current != null) {
      window.clearTimeout(loopTimerRef.current);
    }
    loopTimerRef.current = window.setTimeout(async () => {
      loopTimerRef.current = null;
      const w = loopEnabled ? loopWindow : 0;
      const t = loopEnabled ? loopThreshold : 0;
      setLoopStatus('pushing');
      setLoopError(null);
      const r = await setLoopDetectorThresholds({ window: w, threshold: t });
      if (r.ok) {
        setLoopStatus('idle');
      } else {
        setLoopStatus('error');
        setLoopError(r.error ?? 'unknown');
      }
    }, 250);
    return () => {
      if (loopTimerRef.current != null) {
        window.clearTimeout(loopTimerRef.current);
        loopTimerRef.current = null;
      }
    };
  }, [loopEnabled, loopWindow, loopThreshold, setLoopDetectorThresholds]);
  // provider picker state. The user
  // picks a provider (e.g. "glm") + a model
  // (e.g. "glm-4-flash"). We keep them as two
  // separate local fields so changing the
  // provider resets the model picker to that
  // provider's default. The "save" button
  // sends both via switchProvider.
  const [provider, setLocalProvider] = useState<string>(currentProvider ?? '');
  const [providerModel, setLocalProviderModel] = useState<string>('');
  // R286: "Show all providers" toggle. The
  // default (true) hides providers without an
  // API key configured in the daemon's env
  // (mirroring the R282 MessageInput filter so
  // the user doesn't see rows they can't
  // call). Toggling the switch shows every
  // entry in the registry, marked with a small
  // "(no API key)" badge so the user can see
  // WHY a row is greyed out. Persisted to
  // localStorage so the choice survives a
  // renderer reload.
  const [showAllProviders, setShowAllProviders] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    try {
      const raw = window.localStorage.getItem('aethercode.settings.showAllProviders');
      if (raw === null) return false;  // default: hide unconfigured
      return raw === '1';
    } catch {
      return false;
    }
  });
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(
        'aethercode.settings.showAllProviders',
        showAllProviders ? '1' : '0',
      );
    } catch {
      // private mode — in-memory state is still set.
    }
  }, [showAllProviders]);
  const filteredProviders = showAllProviders
    ? (availableProviders ?? [])
    : (availableProviders ?? []).filter((p: any) => p.hasApiKey !== false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // re-pull the model list every time the panel opens
  // so newly-added models in the engine price table show up
  // without needing a renderer reload.
  useEffect(() => { void refreshModels(); }, [refreshModels]);
  // also re-pull engine stats so the profile
  // dropdown reflects the daemon's current value (the
  // user could have changed it via another window or
  // the CLI's --concurrency-profile flag).
  useEffect(() => { setLocalProfile((currentProfile as 'low' | 'normal' | 'high') ?? 'normal'); }, [currentProfile]);
  // refresh the provider list every time the
  // panel opens, so a freshly-edited providers.yaml
  // (or a daemon that just started) shows up. Also
  // bind the local state to the daemon's current
  // provider+model so the pickers highlight the
  // active row.
  useEffect(() => { void refreshProviders(); }, [refreshProviders]);
  useEffect(() => {
    if (currentProvider) setLocalProvider(currentProvider);
    if (engineState?.model) setLocalProviderModel(engineState.model);
  }, [currentProvider, engineState?.model]);

  const onSave = async () => {
    setSaving(true); setError(null);
    try {
      // provider + model is the single
      // prior round control surface. The legacy
      // engineState.model is a separate field
      // for the Settings panel's own "active
      // row" display; we route every model
      // change through switchProvider so the
      // engine's ChatClient stays consistent.
      // prior round follow-up: when only the
      // providerModel changes (same
      // provider), we'd want a thinner
      // setModelOnly RPC. For now, route
      // through switchProvider for both
      // cases — the extra round-trip is one
      // RPC and stays under 50ms.
      if (provider && (provider !== currentProvider || (providerModel && providerModel !== engineState?.model))) {
        await switchProvider(provider, providerModel || undefined);
      }
      if (perm !== engineState?.permissionMode) await setPermissionMode(perm);
      if (profile !== currentProfile) await requestConcurrencyProfile(profile);
      onClose();
    } catch (e: any) { setError(e?.message ?? String(e)); }
    finally { setSaving(false); }
  };

  // the model picker for the selected
  // provider. When the user changes the
  // provider, reset the model to the new
  // provider's defaultModel (so the picker
  // doesn't show stale models from the
  // previous provider).
  const selectedProvider = (availableProviders ?? []).find(
    (p: any) => p.name === provider
  );
  const modelsForProvider = selectedProvider?.models ?? [];

  return (
    <div className="settings-overlay" onClick={onClose}>
      <div className="settings-panel" onClick={(e) => e.stopPropagation()}>
        <div className="settings-header">
          <h2>Settings</h2>
          <button className="settings-close" onClick={onClose}>×</button>
        </div>
        <div className="settings-body">
          {/* R286: provider visibility toggle.
              Off by default — the user reported
              "你配置的很多模型都是我没办法用的",
              so the Settings panel mirrors the
              MessageInput's R282 filter. When
              on, every registry entry shows up
              with a small "(no API key)" badge
              next to the env-var hint so the
              user can see why a row isn't
              usable. The toggle persists to
              localStorage so a renderer reload
              keeps the user's choice. */}
          <div className="settings-field settings-field-row">
            <label className="settings-toggle">
              <input
                type="checkbox"
                checked={showAllProviders}
                onChange={(e) => setShowAllProviders(e.target.checked)}
                data-testid="settings-show-all-providers"
              />
              <span>Show all providers (incl. unconfigured)</span>
            </label>
            <small className="settings-hint">
              {filteredProviders.length === (availableProviders ?? []).length
                ? `showing ${filteredProviders.length} of ${(availableProviders ?? []).length}`
                : `showing ${filteredProviders.length} configured · ${(availableProviders ?? []).length - filteredProviders.length} hidden (no API key)`}
            </small>
          </div>
          {/* provider + model picker. The
              first row picks the provider (e.g.
              "minmax" / "glm" / "qwen" /
              "deepseek" / "anthropic" etc.); the
              second row picks the model within
              that provider. We list the providers
              as a single-line dropdown (one entry
              per brand) and the models in a
              second dropdown that swaps when the
              provider changes. */}
          <label className="settings-field">
            <span>Provider</span>
            <select
              value={provider}
              onChange={(e) => {
                setLocalProvider(e.target.value);
                // Reset the model picker to the
                // new provider's defaultModel so
                // the user doesn't accidentally
                // submit a model that doesn't
                // belong to the new provider.
                const np = filteredProviders.find(
                  (p: any) => p.name === e.target.value
                );
                setLocalProviderModel(np?.defaultModel ?? '');
              }}
            >
              {filteredProviders.length === 0 ? (
                <option value="" disabled>(no providers — daemon offline? or toggle "Show all")</option>
              ) : filteredProviders.map((p: any) => (
                <option key={p.name} value={p.name}>
                  {p.name}{p.name === currentProvider ? ' (current)' : ''}
                  {p.apiKeyEnv ? ` · ${p.apiKeyEnv}` : ''}
                  {/* R286: when the user has
                      enabled "Show all", badge
                      the unconfigured rows so the
                      reason is obvious. The
                      filter already gates the
                      underlying list, so the
                      dropdown only shows
                      unconfigured rows when the
                      user explicitly opted in. */}
                  {showAllProviders && p.hasApiKey === false ? ' · (no API key)' : ''}
                </option>
              ))}
            </select>
            <small className="settings-hint">
              {selectedProvider?.baseUrl
                ? `${selectedProvider.baseUrl}${
                    selectedProvider?.apiKeyEnv
                      ? ` · set ${selectedProvider.apiKeyEnv} in env to use this provider`
                      : ''
                  }`
                : 'pick a provider'}
            </small>
          </label>
          <label className="settings-field">
            <span>Model</span>
            <select
              value={providerModel}
              onChange={(e) => setLocalProviderModel(e.target.value)}
              disabled={modelsForProvider.length === 0}
            >
              {modelsForProvider.length === 0 ? (
                <option value="" disabled>(no models for this provider)</option>
              ) : modelsForProvider.map((m: any) => (
                <option key={m.id} value={m.id}>
                  {m.id}{m.default ? ' (default)' : ''}
                  {' · '}${(m.inputPer1k ?? 0).toFixed(4)}/${(m.outputPer1k ?? 0).toFixed(4)} per 1k
                  {/* R286: per-model pricing
                      summary. Convert the
                      per-1k USD rate to the
                      standard $X/M-input +
                      $Y/M-output label so the
                      user can compare models at
                      a glance without opening a
                      calculator. M = million
                      tokens, so ×1000 over the
                      per-1k value. Free / blank
                      rates (e.g. local stubs)
                      show as "free" so the row
                      doesn't read as $0.00 (a
                      missing signal a 0 would
                      give). */}
                      {' · $' + ((m.inputPer1k ?? 0) * 1000).toFixed(2) + '/M in, $' + ((m.outputPer1k ?? 0) * 1000).toFixed(2) + '/M out'}
                </option>
              ))}
            </select>
            <small className="settings-hint">
              {selectedProvider && (() => {
                // R286: provider-level cost
                // summary. Sum the per-1k
                // input + output rates across
                // the provider's models and
                // show the band (min..max in
                // + out). Lets the user see at
                // a glance that glm runs
                // $0.10-$1.20/M input while
                // openai gpt-4 sits at $30/M.
                const inMin = Math.min(...modelsForProvider.map((mm: any) => mm.inputPer1k ?? 0));
                const inMax = Math.max(...modelsForProvider.map((mm: any) => mm.inputPer1k ?? 0));
                const outMin = Math.min(...modelsForProvider.map((mm: any) => mm.outputPer1k ?? 0));
                const outMax = Math.max(...modelsForProvider.map((mm: any) => mm.outputPer1k ?? 0));
                if (inMin === inMax && outMin === outMax) {
                  return `${modelsForProvider.length} model(s) · $${(inMin * 1000).toFixed(2)}/M in, $${(outMin * 1000).toFixed(2)}/M out`;
                }
                return `${modelsForProvider.length} model(s) · in $${(inMin * 1000).toFixed(2)}–$${(inMax * 1000).toFixed(2)}/M, out $${(outMin * 1000).toFixed(2)}–$${(outMax * 1000).toFixed(2)}/M`;
              })()}
            </small>
          </label>
          <label className="settings-field">
            <span>Permission mode</span>
            <select value={perm} onChange={(e) => setLocalPerm(e.target.value)}>
              {PERMISSION_MODES.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
            </select>
          </label>
          {/* concurrency profile dropdown. The
              engineStats view already carries the
              current profile; we just bind the
              <select> to it. Switching profiles takes
              effect on the next query (the engine
              rebuilds the Semaphores in
              setConcurrencyProfile). The throttle /
              backpressure pills in the StatusBar /
              Header update within ~5s because
              refreshEngineStats is already on a 5s
              timer. */}
          <label className="settings-field">
            <span>Concurrency profile</span>
            <select
              value={profile}
              onChange={(e) => setLocalProfile(e.target.value as 'low' | 'normal' | 'high')}
            >
              {CONCURRENCY_PROFILES.map((p) => (
                <option key={p.value} value={p.value}>{p.label}</option>
              ))}
            </select>
            <small className="settings-hint">
              {(engineStats?.queriesInFlight ?? 0)}q / {(engineStats?.toolsInFlight ?? 0)}t / {(engineStats?.branchesInFlight ?? 0)}b in flight
              {engineStats?.throttled ? ' · ⏳ throttled' : ''}
              {engineStats?.backpressured ? ' · ⛔ backpressured' : ''}
            </small>
          </label>
          {/* loop-detector threshold sliders. The
              engine stops a run when the same tool-call
              fingerprint appears `threshold` times in
              the last `window` turns. legacy these
              were build-time constants; the Settings
              panel now lets the user tweak them live.
              The "enabled" toggle is the master switch
              — off maps to (window=0, threshold=0) which
              the daemon treats as "skip the detector
              entirely". The two sliders are mutually
              constrained: the threshold slider's max is
              the window value (a threshold larger than
              the window would fire on the first repeat,
              which is rarely what the user wants). The
              250ms debounce in the parent effect keeps
              the WS calm during a drag. The status
              pill (pushing / idle / error) tells the
              user the RPC actually fired; the error
              message surfaces the daemon's reason (e.g.
              "window must be >= threshold") inline. */}
          <div className="settings-field settings-field-group">
            <span>Loop detection</span>
            <label className="settings-field-inline">
              <input
                type="checkbox"
                checked={loopEnabled}
                onChange={(e) => setLoopEnabled(e.target.checked)}
              />
              <span>enable the loop detector</span>
            </label>
            <label className="settings-field-inline">
              <span className="settings-slider-label">
                window: <code>{loopWindow}</code> turns
              </span>
              <input
                type="range"
                min={2}
                max={32}
                step={1}
                value={loopWindow}
                disabled={!loopEnabled}
                onChange={(e) => {
                  const w = Number(e.target.value);
                  setLoopWindow(w);
                  // Keep the threshold in range — the
                  // user dragging the window past the
                  // current threshold would otherwise
                  // make the next threshold tweak
                  // snap-clamp on the next render.
                  setLoopThreshold((cur) => Math.min(cur, w));
                }}
              />
            </label>
            <label className="settings-field-inline">
              <span className="settings-slider-label">
                threshold: <code>{loopThreshold}</code> repeats
              </span>
              <input
                type="range"
                min={1}
                max={loopWindow}
                step={1}
                value={loopThreshold}
                disabled={!loopEnabled}
                onChange={(e) => setLoopThreshold(Number(e.target.value))}
              />
            </label>
            <small className="settings-hint">
              stop the run when the same tool-call fingerprint appears
              <code> {loopThreshold} </code> times in the last
              <code> {loopWindow} </code> turns.
              {loopStatus === 'pushing' && <> · ⏳ pushing…</>}
              {loopStatus === 'idle' && <> · ✅ applied</>}
              {loopStatus === 'error' && loopError && (
                <> · ❌ {loopError}</>
              )}
            </small>
          </div>
          {/* supervisor auto-restart
              toggle. Off by default (a crash
              should not silently restart; the
              user may want to inspect). When
              on, the supervisor respawns a
              crashed child up to
              MAX_RESTARTS_PER_CHILD (5) times
              with 1s backoff so the OS can
              release the port. Useful for
              production / long-running
              sessions; the user explicitly
              asked for "summary at end of
              every task" — this is the
              resilience counterpart. */}
          <label className="settings-field-inline">
            <input
              type="checkbox"
              checked={autoRestart}
              onChange={(e) => setLocalAutoRestart(e.target.checked)}
            />
            <span>supervisor: auto-restart crashed children</span>
          </label>
          <small className="settings-hint">
            when on, a child that crashes (DEAD/UNHEALTHY) is respawned up
            to 5 times with 1s backoff. The TUI shows the new PID under
            "Children" so you can verify the swap. Default off — leave
            off for development so you can inspect a crash in place.
            {autoRestartStatus === 'pushing' && <> · ⏳ pushing…</>}
            {autoRestartStatus === 'idle' && <> · ✅ applied</>}
            {autoRestartStatus === 'error' && autoRestartError && (
              <> · ❌ {autoRestartError}</>
            )}
          </small>
          {/* explicit "restart daemon" action.
              legacy the daemon was killed-and-respawned
              on every cwd change (a 1-2s blank UI). Post-
              R160 the in-place bindSessionCwd handles
              normal cwd switches, so this button is the
              rare "the daemon is wedged" escape hatch. The
              user explicitly asked "为什么切换 cwd 还需要
              重新连接" — this gives them both: fast
              in-place cwd (the new default) AND a
              deliberate restart option when they really
              want a clean slate. */}
          <div className="settings-field settings-field-group">
            <span>Advanced</span>
            <button
              type="button"
              className="settings-restart-btn"
              onClick={() => {
                if (window.confirm('重启 daemon 会断开当前连接、丢失未保存的 transcript,继续吗?')) {
                  void resetDaemon();
                }
              }}
            >
              🔄 重启 daemon
            </button>
            <small className="settings-hint">
              正常切换工作目录不需要重启 (R160 后已改为就地切换)。仅在 daemon 卡死或状态异常时使用此按钮。会清掉当前 transcript 并重新建立 WebSocket 连接,约 1-2 秒。
            </small>
          </div>
          {error && <div className="settings-error">{error}</div>}
        </div>
        <div className="settings-footer">
          <button onClick={onClose}>Cancel</button>
          <button className="primary" onClick={onSave} disabled={saving}>{saving ? 'Saving…' : 'Save'}</button>
        </div>
      </div>
    </div>
  );
}
