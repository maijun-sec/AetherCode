package org.aethercode.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parsed {@code .aethercode/config.json}. Top-level schema:
 * <pre>
 * {
 *   "version": 1,
 *   "permissionMatrix": { ... },
 *   "skipConfirmation": false,
 *   "skipConfirmationRounds": 0,
 *   "workflow": "design-first" | "legacy",
 *   "phases": null | [ ... ]
 * }
 * </pre>
 *
 * <p>All fields are optional. {@link ConfigEngine#loadFrom} returns
 * {@link #defaults()} if the file is missing or unparseable, so a project
 * without a config file still gets the safe built-in matrix.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AetherCodeConfig {

    public int version = 1;
    public PermissionMatrix permissionMatrix = new PermissionMatrix();
    public boolean skipConfirmation = false;
    public int skipConfirmationRounds = 0;
    public String workflow = "design-first";
    public Object phases = null; // reserved; ignored at this layer
    // explicit permission mode the user wants as the boot default.
    // When non-null and the engine boots a fresh session (no persisted
    // mode on disk), this value is used INSTEAD of the suggester's
    // recommendation. When null (default), the suggester picks.
    //
    // Recognised values (case-insensitive):
    //   "DEFAULT"          → ask for every non-read-only tool call
    //   "ASK_BEFORE_TOOL"  → same as DEFAULT, more discoverable name (prior round)
    //   "ACCEPT_EDITS"     → auto-allow file edits, ask for bash / network
    //   "ACCEPT_TASK"      → auto-allow within a sub-task, ask at boundaries
    //   "BYPASS_PERMISSIONS" → auto-allow everything
    //   "PLAN"             → ask (but engine also short-circuits writes)
    //   "AUTO_READ_ONLY"   → auto-allow read-only tools, ask the rest
    public String defaultPermissionMode = null;
    // low-waterline for the skip-confirmation counter. When the
    // counter crosses DOWN to {@code <= skipLowWaterline} (was strictly
    // greater than it on the previous consume), the engine emits a
    // NOTIFY_SKIP_LOW notification so the UI can show "skip running
    // low, re-arm?". Default 5. Set to 0 (or negative) to disable.
    public int skipLowWaterline = 5;
    // project memory compression. The user can set a
    // custom threshold (default 50) and how many recent
    // entries to keep verbatim after a compression pass
    // (default 10). The brief: "record 20-50 entries (configurable)...
    // keep the most recent 10 modifications". The default of 50 sits in
    // the middle of the user-specified range; power users
    // can push to 100+ for big refactors or down to 5
    // for very chatty local projects.
    public MemoryConfig memory = new MemoryConfig();

    public AetherCodeConfig() {}

    public AetherCodeConfig(int version,
                            PermissionMatrix permissionMatrix,
                            boolean skipConfirmation,
                            int skipConfirmationRounds,
                            String workflow,
                            Object phases,
                            int skipLowWaterline,
                            MemoryConfig memory) {
        this.version = version;
        this.permissionMatrix = permissionMatrix == null ? new PermissionMatrix() : permissionMatrix;
        this.skipConfirmation = skipConfirmation;
        this.skipConfirmationRounds = Math.max(0, skipConfirmationRounds);
        this.workflow = workflow == null || workflow.isBlank() ? "design-first" : workflow;
        this.phases = phases;
        this.skipLowWaterline = skipLowWaterline;
        this.memory = memory == null ? new MemoryConfig() : memory;
    }

    /** project memory compression knobs. Lives at
     *  {@code .aethercode/config.json -> "memory": {...}}. */
    public static class MemoryConfig {
        /** When the project MEMORY.md crosses this many
         *  change-log lines, the oldest block is summarised
         *  by the LLM. Default 50 (the user said "20-50",
         *  we sit in the middle). */
        public int projectCompressThreshold = 50;
        /** How many of the most-recent entries survive a
         *  compression pass verbatim. Default 10 (the
         *  user's brief). The threshold - keepRecent
         *  oldest entries get summarised. */
        public int keepRecent = 10;
        /** Auto-compress on append. When false, compression
         *  is only manual (the {@code compressProjectMemory}
         *  RPC). Default true. */
        public boolean autoCompress = true;
    }

    /**
     * Built-in safe defaults. The {@link org.aethercode.config.defaults.DefaultMatrix}
     * is loaded lazily so the JSON is the single source of truth for changes.
     */
    public static AetherCodeConfig defaults() {
        AetherCodeConfig c = new AetherCodeConfig();
        c.version = 1;
        c.permissionMatrix = org.aethercode.config.defaults.DefaultMatrix.build();
        c.skipConfirmation = false;
        c.skipConfirmationRounds = 0;
        c.workflow = "design-first";
        c.phases = null;
        c.skipLowWaterline = 5;
        c.memory = new MemoryConfig();
        return c;
    }
}
