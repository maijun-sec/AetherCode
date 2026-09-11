package org.aethercode.code.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * User-facing presentation for Hooks v2 execution.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.presenter} module. The presenter turns
 * reduced decisions into user-facing notices and transient status
 * updates; it is held by the {@code HooksManager} and shared with
 * every runtime the manager loads.</p>
 */
public final class Presenter {

    private static final Logger LOG = LoggerFactory.getLogger(Presenter.class);

    /** Severity carried in {@link HookNoticeCallback} invocations. */
    public enum Severity { INFORMATION, WARNING, ERROR }

    /** Callback that surfaces a user-visible hook notice. */
    @FunctionalInterface
    public interface HookNoticeCallback {
        void call(String message, Severity severity);
    }

    /** Callback that updates hook-owned transient status text. */
    @FunctionalInterface
    public interface HookStatusCallback {
        void call(String message);
    }

    /** Callback that receives lifecycle updates for running hook handlers. */
    @FunctionalInterface
    public interface HookProgressCallback {
        void call(HookProgress progress);
    }

    /**
     * Lifecycle update for one running hook handler.
     */
    public record HookProgress(
            String operationId,
            String handlerId,
            HookEvent event,
            boolean active,
            String message) {}

    private HookNoticeCallback notice;
    private HookStatusCallback status;
    private final Map<String, String> activeStatuses = new LinkedHashMap<>();

    public Presenter() {}

    public Presenter(HookNoticeCallback notice, HookStatusCallback status) {
        this.notice = notice;
        this.status = status;
    }

    /** Rebind the output sinks without replacing the presenter. */
    public void attach(HookNoticeCallback notice, HookStatusCallback status) {
        this.notice = notice;
        this.status = status;
    }

    /** Present common side effects from a reduced hook decision. */
    public void presentDecision(org.aethercode.code.hooks.HookDomainEvents.Decision decision) {
        if (decision == null) return;
        presentDiagnostics(decision.diagnostics());
        for (String notice : decision.userNotices()) {
            notify(notice, Severity.INFORMATION);
        }
        for (String sequence : decision.terminalSequences()) {
            System.out.print(sequence);
        }
        if (!decision.terminalSequences().isEmpty()) {
            System.out.flush();
        }
    }

    /** Log diagnostics and surface each warning or error once per call. */
    public void presentDiagnostics(List<HookDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return;
        Set<DiagnosticKey> delivered = new LinkedHashSet<>();
        for (HookDiagnostic diagnostic : diagnostics) {
            logDiagnostic(diagnostic);
            if (diagnostic.severity() == HookDiagnostic.Severity.DEBUG) continue;
            DiagnosticKey key = new DiagnosticKey(
                    diagnostic.code(), diagnostic.severity().name().toLowerCase(),
                    diagnostic.message(), diagnostic.handlerId(), diagnostic.field());
            if (!delivered.add(key)) continue;
            Severity severity = diagnostic.severity() == HookDiagnostic.Severity.ERROR
                    ? Severity.ERROR : Severity.WARNING;
            if (notify("Hook " + severity.name().toLowerCase() + ": " + diagnostic.message(),
                    severity)) {
                /* delivered */
            }
        }
    }

    /** Update the currently visible hook-owned status. */
    public void updateProgress(HookProgress progress) {
        if (progress.active()) {
            activeStatuses.put(progress.operationId(), statusText(progress));
        } else {
            activeStatuses.remove(progress.operationId());
        }
        String message = activeStatuses.isEmpty() ? "" :
                activeStatuses.get(activeStatuses.size() - 1);
        setStatus(message);
    }

    /** Attribute a hook-owned permission decision to the hook. */
    public void presentPermission(String toolName, PermissionEffect permission) {
        String target = (toolName == null || toolName.isEmpty()) ? "tool request" : toolName;
        if (permission.behavior() == PermissionEffect.Behavior.ALLOW) {
            notify("PermissionRequest hook allowed " + target + ".", Severity.INFORMATION);
        } else if (permission.behavior() == PermissionEffect.Behavior.DENY) {
            String suffix = permission.reason() == null || permission.reason().isEmpty()
                    ? "." : ": " + permission.reason();
            notify("PermissionRequest hook denied " + target + suffix, Severity.WARNING);
        }
    }

    private boolean notify(String message, Severity severity) {
        if (notice == null) {
            LOG.warn("Hook notice (no sink attached): {}", message);
            return true;
        }
        try {
            notice.call(message, severity);
        } catch (Exception ex) {
            LOG.warn("Failed to surface hook notice", ex);
            return false;
        }
        return true;
    }

    private void setStatus(String message) {
        if (status == null) return;
        try {
            status.call(message);
        } catch (Exception ex) {
            LOG.warn("Failed to update hook status", ex);
        }
    }

    private static String statusText(HookProgress progress) {
        if (progress.message() != null && !progress.message().isEmpty()) {
            return progress.message();
        }
        return "Running " + progress.event().wireName() + " hook…";
    }

    private static void logDiagnostic(HookDiagnostic diagnostic) {
        String message = "Hook diagnostic %s: %s";
        switch (diagnostic.severity()) {
            case ERROR -> LOG.error(message, diagnostic.code(), diagnostic.message());
            case WARNING -> LOG.warn(message, diagnostic.code(), diagnostic.message());
            default -> LOG.debug(message, diagnostic.code(), diagnostic.message());
        }
    }

    /** Composite key used to dedupe diagnostics within one presentation call. */
    public record DiagnosticKey(String code, String severity, String message,
                                String handlerId, String field) {}
}
