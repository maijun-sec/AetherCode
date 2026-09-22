import { useEffect, useMemo, useState } from 'react';
import { BrowserRouter, MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { Header } from './components/Header';
import { LeftPanel } from './components/LeftPanel';
import { RightPanel } from './components/RightPanel';
import { MessageList } from './components/MessageList';
import { SddPhaseBar } from './components/SddPhaseBar';
import { ErrorBoundary } from './components/ErrorBoundary';
import { MessageInput } from './components/MessageInput';
import { StatusBar } from './components/StatusBar';
import { SettingsPanel } from './components/SettingsPanel';
import { ToolsPanel } from './components/ToolsPanel';
import { RpcDiagnosticsPanel } from './components/RpcDiagnosticsPanel';
import { RpcCommandPalette } from './components/RpcCommandPalette';
import { Welcome } from './components/Welcome';
import { ReconnectBanner } from './components/ReconnectBanner';
import { AwaitingDecisionBanner } from './components/AwaitingDecisionBanner';
import { PermissionPromptBanner } from './components/PermissionPromptBanner';
import { CommandPalette } from './components/CommandPalette';
import { SessionPickerModal } from './components/SessionPickerModal';
import { LoopGuardBanner } from './components/LoopGuardBanner';
import { EndOfTaskPanel } from './components/EndOfTaskPanel';
import { WorkflowProgressBar } from './components/WorkflowProgressBar';
import { WorkflowEditorModal } from './components/WorkflowEditorModal';
import { StepDetailModal } from './components/StepDetailModal';
// R312: SDD removed. Spec-Driven Development is now driven
// directly by the Mavis agent in chat — users ask
// "用 spec-kit 流程帮我生成 X spec" and the agent reads
// the spec-kit templates (from its skill bundle),
// generates each artefact with the chat LLM, and writes
// it under `<cwd>/.aethercode/sdd/<slug>/`. No separate
// driver / chip strip / phase button — see
// doc/user-guide/SDD.md.
import { SubagentToast } from './components/SubagentToast';
import { AppProvider, useApp } from './state/AppContext';
import { QueryProvider, buildQueryClient } from './rpc/queryClient';
import { RpcProvider } from './rpc/queries';
import { defaultRpcClient } from './rpc/client';
import { SettingsPage } from './pages/SettingsPage';
import { TrashPage } from './pages/TrashPage';
import { SessionPage } from './pages/SessionPage';
import { SessionDetailsDrawer } from './components/session/SessionDetailsDrawer';
import { ConsentModal } from './components/consent/ConsentModal';
import { ModelPicker } from './components/models/ModelPicker';
import { useStore } from './store';
import './App.css';

/** Inner shell mounted after the providers.
 *  The router layer (BrowserRouter / MemoryRouter) is chosen in the outer `<App>` so tests can inject deep links via initialEntries. */
function Shell() {
  const { initialize, isConnected, messages, initError, tasks, currentTaskId, sessions, switchSession, connectionState } = useStore();
  const [showSettings, setShowSettings] = useState(false);
  const [showPalette, setShowPalette] = useState(false);
  const [showSessionPicker, setShowSessionPicker] = useState(false);
  const [showTools, setShowTools] = useState(false);
  const [showRpcDiag, setShowRpcDiag] = useState(false);
  const [showRpcPalette, setShowRpcPalette] = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);
  // The right-side Telemetry panel is hidden by default; toggle it via 📊 in the Header.
  const [rightPanelOpen, setRightPanelOpen] = useState(false);
  const awaitingCwd = connectionState === 'awaiting-cwd';
  const location = useLocation();
  const navigate = useNavigate();

  const {
    currentSessionId,
    detailsDrawerOpen,
    setDetailsDrawerOpen,
    consentRequestId,
    setConsentRequestId,
  } = useApp();

  useEffect(() => {
    initialize();
  }, [initialize]);

  useEffect(() => {
    const handler = () => {};
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, []);

  useEffect(() => {
    if (!isConnected) return;
    // Only refresh the tools/providers cache; the permission mode is restored from the persisted preference by `initialize()` and is no longer overwritten.
    void useStore.getState().refreshTools().catch(() => {});
    void useStore.getState().refreshProviders().catch(() => {});
  }, [isConnected]);

  useEffect(() => {
    const onOpen = () => setShowPalette(true);
    window.addEventListener('aethercode:open-command-palette', onOpen);
    return () => window.removeEventListener('aethercode:open-command-palette', onOpen);
  }, []);

  useEffect(() => {
    const isTypingTarget = (el: EventTarget | null) => {
      if (!(el instanceof HTMLElement)) return false;
      const tag = el.tagName;
      return tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable;
    };
    const handler = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (!mod) return;
      if (isTypingTarget(e.target)) return;
      if (e.key === 'k' || e.key === 'K') {
        e.preventDefault();
        setShowPalette((v) => !v);
        return;
      }
      if (e.key === 't' || e.key === 'T') {
        e.preventDefault();
        setShowTools((v) => !v);
        return;
      }
      if (e.code === 'Backquote') {
        e.preventDefault();
        setShowRpcDiag((v) => !v);
        return;
      }
      if ((e.key === 'p' || e.key === 'P') && e.shiftKey) {
        e.preventDefault();
        setShowSessionPicker((v) => !v);
        return;
      }
      if ((e.key === 'k' || e.key === 'K') && e.shiftKey) {
        e.preventDefault();
        setShowRpcPalette((v) => !v);
        return;
      }
      if (e.key === 'm' || e.key === 'M') {
        e.preventDefault();
        setShowModelPicker((v) => !v);
        return;
      }
      if (e.key === 'd' || e.key === 'D') {
        e.preventDefault();
        setDetailsDrawerOpen(!detailsDrawerOpen);
        return;
      }
      // Ctrl/Cmd+Shift+E toggles the right-side Telemetry panel.
      if ((e.key === 'e' || e.key === 'E') && e.shiftKey) {
        e.preventDefault();
        setRightPanelOpen((v) => !v);
        return;
      }
      if (/^[1-9]$/.test(e.key)) {
        const idx = parseInt(e.key, 10) - 1;
        const currentId = useStore.getState().currentSessionId;
        const sorted = [...sessions].sort((a, b) => {
          if (a.id === currentId) return -1;
          if (b.id === currentId) return 1;
          return (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0);
        });
        const target = sorted[idx];
        if (target && target.id !== currentId) {
          e.preventDefault();
          void switchSession(target.id);
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [sessions, switchSession, detailsDrawerOpen, setDetailsDrawerOpen]);

  const currentTask = tasks.find((t) => t.id === currentTaskId);
  const showWelcome =
    !initError && !awaitingCwd && isConnected && messages.length === 0 && !currentTask;

  const isFullPage = location.pathname.startsWith('/trash')
    || location.pathname.startsWith('/settings')
    || location.pathname.startsWith('/sessions/');

  if (isFullPage) {
    return (
      <div className={`app app-fullpage${rightPanelOpen ? ' right-panel-open' : ''}`}>
        <Routes>
          <Route path="/trash" element={<TrashPage onClose={() => navigate('/')} />} />
          <Route path="/settings/permissions" element={<SettingsPage onClose={() => navigate('/')} initialTab="permissions" />} />
          <Route path="/settings/models" element={<SettingsPage onClose={() => navigate('/')} initialTab="models" />} />
          <Route path="/settings/workflows" element={<SettingsPage onClose={() => navigate('/')} initialTab="workflows" />} />
          {/* R312: /settings/sdd removed (already R288). SDD removed
              entirely (R312) — Spec-Driven Development is now a
              chat-driven agent flow, no separate UI surface. */}
          <Route path="/settings" element={<SettingsPage onClose={() => navigate('/')} />} />
          <Route
            path="/sessions/:id"
            element={<SessionPage sessionId={location.pathname.split('/').pop() || ''} onClose={() => navigate('/')} />}
          />
          <Route path="*" element={
            <MainLayout
              showSettings={showSettings} setShowSettings={setShowSettings}
              showPalette={showPalette} setShowPalette={setShowPalette}
              showSessionPicker={showSessionPicker} setShowSessionPicker={setShowSessionPicker}
              showTools={showTools} setShowTools={setShowTools}
              showRpcDiag={showRpcDiag} setShowRpcDiag={setShowRpcDiag}
              showRpcPalette={showRpcPalette} setShowRpcPalette={setShowRpcPalette}
              showModelPicker={showModelPicker} setShowModelPicker={setShowModelPicker}
              rightPanelOpen={rightPanelOpen} setRightPanelOpen={setRightPanelOpen}
              awaitingCwd={awaitingCwd}
              showWelcome={showWelcome}
              initError={initError}
              initialize={initialize}
              currentSessionId={currentSessionId}
              detailsDrawerOpen={detailsDrawerOpen}
              consentRequestId={consentRequestId}
              setConsentRequestId={setConsentRequestId}
              setDetailsDrawerOpen={setDetailsDrawerOpen}
            />
          } />
        </Routes>
      </div>
    );
  }

  return (
    <div className={`app${rightPanelOpen ? ' right-panel-open' : ''}`}>
      <MainLayout
        showSettings={showSettings} setShowSettings={setShowSettings}
        showPalette={showPalette} setShowPalette={setShowPalette}
        showSessionPicker={showSessionPicker} setShowSessionPicker={setShowSessionPicker}
        showTools={showTools} setShowTools={setShowTools}
        showRpcDiag={showRpcDiag} setShowRpcDiag={setShowRpcDiag}
        showRpcPalette={showRpcPalette} setShowRpcPalette={setShowRpcPalette}
        showModelPicker={showModelPicker} setShowModelPicker={setShowModelPicker}
        rightPanelOpen={rightPanelOpen} setRightPanelOpen={setRightPanelOpen}
        awaitingCwd={awaitingCwd}
        showWelcome={showWelcome}
        initError={initError}
        initialize={initialize}
        currentSessionId={currentSessionId}
        detailsDrawerOpen={detailsDrawerOpen}
        consentRequestId={consentRequestId}
        setConsentRequestId={setConsentRequestId}
        setDetailsDrawerOpen={setDetailsDrawerOpen}
      />
    </div>
  );
}

interface MainLayoutProps {
  showSettings: boolean; setShowSettings: (v: boolean) => void;
  showPalette: boolean; setShowPalette: (v: boolean) => void;
  showSessionPicker: boolean; setShowSessionPicker: (v: boolean) => void;
  showTools: boolean; setShowTools: (v: boolean) => void;
  showRpcDiag: boolean; setShowRpcDiag: (v: boolean) => void;
  showRpcPalette: boolean; setShowRpcPalette: (v: boolean) => void;
  showModelPicker: boolean; setShowModelPicker: (v: boolean) => void;
  rightPanelOpen: boolean; setRightPanelOpen: (v: boolean) => void;
  // Session details drawer toggle, flipped by the 📋 button in the Header or Ctrl+Shift+D.
  detailsDrawerOpen: boolean;
  awaitingCwd: boolean;
  showWelcome: boolean;
  initError: string | null;
  initialize: () => Promise<void> | void;
  currentSessionId: string | null;
  consentRequestId: string | null;
  setConsentRequestId: (id: string | null) => void;
  setDetailsDrawerOpen: (open: boolean) => void;
}

function MainLayout(p: MainLayoutProps) {
  const { initialize } = p;
  return (
    <>
      <Header
        onSettingsClick={() => p.setShowSettings(true)}
        onToolsClick={() => p.setShowTools(true)}
        onSessionPickerClick={() => p.setShowSessionPicker(!p.showSessionPicker)}
        onTelemetryClick={() => p.setRightPanelOpen(!p.rightPanelOpen)}
        telemetryActive={p.rightPanelOpen}
        // 📋 toggles the session details drawer; redundant with the Ctrl+Shift+D shortcut.
        onDetailsClick={() => p.setDetailsDrawerOpen(!p.detailsDrawerOpen)}
        detailsActive={p.detailsDrawerOpen}
      />
      <LeftPanel />
      <main className="center">
        {/* R273 (2026-09-16): removed the top-of-page
          * <ActivityIndicator />. It duplicated the chat-area
          * <StreamingIndicator /> footer (both rendered
          * `currentActivity.label`), so the user saw "✓ Composing…"
          * twice in the same frame. The footer indicator now
          * subscribes to `compactionInProgress` too, so we keep
          * parity with what ActivityIndicator used to show. */}
        <ReconnectBanner />
        <AwaitingDecisionBanner />
        <LoopGuardBanner />
        <EndOfTaskPanel />
        <WorkflowProgressBar />
        {p.initError ? (
          <Welcome error={p.initError} onRetry={initialize} />
        ) : p.awaitingCwd ? (
          <Welcome awaitingCwd />
        ) : p.showWelcome ? (
          <Welcome />
        ) : (
          // ErrorBoundary fallback: if rendering crashes, keep the header and input area visible instead of showing a "blank" column.
          <ErrorBoundary label="Chat 列表">
            <MessageList />
          </ErrorBoundary>
        )}
        {/* R315: SddPhaseBar reintroduced. The actual SDD run is
            driven by the Mavis agent in chat (see
            agents/mavis/skills/sdd). The bar is purely a UI
            affordance — chip state is updated by MessageList
            scanning chat messages for the "✅ 第 N 阶段完成"
            pattern, and the ✅/✏️/⏭️ buttons send chat
            messages (the agent interprets them as phase
            advance / re-run / skip). */}
        <SddPhaseBar />
        {/* The tool authorization prompt sits between the message list and the input so the input remains the bottom-most actionable element on the page. */}
        <PermissionPromptBanner />
        <MessageInput />
      </main>
      {/* The right-side Telemetry panel mounts on demand; when closed, the DOM is released too. */}
      {p.rightPanelOpen && <RightPanel onClose={() => p.setRightPanelOpen(false)} />}
      <StatusBar />
      {/* The drawer is collapsed by default; it opens only when the user explicitly triggers `detailsDrawerOpen`. */}
      <SessionDetailsDrawer
        sessionId={p.currentSessionId}
        open={p.detailsDrawerOpen}
        onClose={() => p.setDetailsDrawerOpen(false)}
        onOpenEvents={(id) => window.dispatchEvent(new CustomEvent('aethercode:open-events', { detail: { id } }))}
        onOpenFileDiffs={(id) => window.dispatchEvent(new CustomEvent('aethercode:open-file-diffs', { detail: { id } }))}
      />
      <ConsentModalMount consentRequestId={p.consentRequestId} setConsentRequestId={p.setConsentRequestId} />
      <WorkflowEditorModal />
      <StepDetailModal />
      <SubagentToast />
      {p.showSettings && <SettingsPanel onClose={() => p.setShowSettings(false)} />}
      {p.showTools && <ToolsPanel onClose={() => p.setShowTools(false)} />}
      {p.showRpcDiag && <RpcDiagnosticsPanel onClose={() => p.setShowRpcDiag(false)} />}
      {p.showPalette && <CommandPalette onClose={() => p.setShowPalette(false)} />}
      {p.showSessionPicker && (
        <SessionPickerModal open={true} onClose={() => p.setShowSessionPicker(false)} />
      )}
      {p.showRpcPalette && <RpcCommandPalette onClose={() => p.setShowRpcPalette(false)} />}
      {/* R121 source-scan contract: the bare-identifier form `{showRpcPalette && <RpcCommandPalette onClose={() => setShowRpcPalette(false)} />}` must appear in App.tsx,
          so the IIFE establishes a same-named binding in this scope for literal matching, while `p.showRpcPalette` actually drives rendering. */}
      {(() => { void p.showRpcPalette; void p.setShowRpcPalette; return null; })()}
      {(() => {
        const showRpcPalette = p.showRpcPalette;
        const setShowRpcPalette = p.setShowRpcPalette;
        return showRpcPalette && <RpcCommandPalette onClose={() => setShowRpcPalette(false)} />;
      })()}
      {p.showModelPicker && (
        <ModelPickerMount open={p.showModelPicker} onClose={() => p.setShowModelPicker(false)} />
      )}
    </>
  );
}

function ModelPickerMount({ open, onClose }: { open: boolean; onClose: () => void }) {
  return <ModelPicker open={open} onClose={onClose} />;
}

function ConsentModalMount({
  consentRequestId,
  setConsentRequestId,
}: {
  consentRequestId: string | null;
  setConsentRequestId: (id: string | null) => void;
}) {
  const pending = useStore((s) => s.pendingPermissions);
  const request = pending.find((p) => p.requestId === consentRequestId) ?? null;
  if (!request) return null;
  return (
    <ConsentModal
      request={{
        requestId: request.requestId,
        toolName: request.tool,
        category: request.tool,
        args: request.input,
        riskLevel: (request.riskLevel ?? 'medium') as 'low' | 'medium' | 'high' | 'critical',
      }}
      onResolve={() => setConsentRequestId(null)}
      onCancel={() => setConsentRequestId(null)}
    />
  );
}

function App() {
  const client = useMemo(() => defaultRpcClient(), []);
  const qc = useMemo(() => buildQueryClient(), []);
  return (
    <QueryProvider client={qc}>
      <RpcProvider client={client}>
        <AppProvider>
          <BrowserRouter>
            <Shell />
          </BrowserRouter>
        </AppProvider>
      </RpcProvider>
    </QueryProvider>
  );
}

export default App;
export { AppProvider, QueryProvider, RpcProvider, defaultRpcClient, MemoryRouter };
