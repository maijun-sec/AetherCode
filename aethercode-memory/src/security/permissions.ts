/**
 * Cross-platform 0600 file permissions helper (T-507).
 *
 * Mirrors the Java side:
 *   org.aethercode.core.config.SecureFilePermissions
 *   aethercode/aethercode-permission/.../grants/SecureFilePermissions.java
 *
 * design.md §7 cross-cutting: every file that holds user
 * secrets under `<UserHome>/.aethercode/` must be readable
 * and writable by the owner only. AetherCode stores the
 * following:
 *
 *   - grants.json (consent decisions)            — T-213
 *   - grants.log.jsonl (audit log)               — T-260
 *   - theme.json  + font.yaml                    — §5.1.1
 *   - sessions.db + sessions/<sid>.jsonl         — §1.2
 *   - MEMORY.md (per-project + per-user)
 *   - The task log (PersistentTaskRegistry's jsonl)
 *
 * On POSIX (Linux / macOS) the helper applies 0o600 via
 * `chmod`. On Windows the underlying ACL is more nuanced
 * (DACL/ICACLS), so the helper is a best-effort no-op:
 * the data is still written, but a per-user ACL is the
 * OS's responsibility. The chmod call NEVER throws — a
 * failure is logged via the optional `onWarn` callback
 * so callers can wire it into their logger.
 *
 * The helper is intentionally synchronous (Node's fs
 * operations are async; we expose both). The async
 * variant `chmodOwnerReadWriteOnlyAsync` is the one
 * that the production write path should use, right
 * after the atomic-rename step.
 */

import { chmod as fsChmodAsync } from 'node:fs/promises';
import { chmodSync, existsSync, statSync } from 'node:fs';
import { platform } from 'node:os';

/** Canonical 0600 = owner rw, group/other nothing. Exposed for callers that need to compare. */
export const OWNER_RW_ONLY = 0o600;

/** Optional sink for warnings. Defaults to a no-op. */
export type PermissionWarn = (msg: string) => void;

const noopWarn: PermissionWarn = () => {
  /* best-effort */
};

/**
 * Probe once at module load: POSIX chmod is meaningful
 * on Linux / macOS. On Windows the call either throws
 * or silently no-ops depending on the runtime; we treat
 * Windows as "not POSIX" and skip the chmod so callers
 * don't see a flood of EPERM warnings in the log.
 */
const IS_POSIX: boolean = platform() !== 'win32';

/**
 * @returns true if the runtime supports POSIX chmod
 *     (Linux / macOS). On Windows the helper is a no-op.
 */
export function isPosixSupported(): boolean {
  return IS_POSIX;
}

/**
 * Apply 0600 to `file` if POSIX is supported. Async
 * variant — the production write path should call this
 * immediately after the atomic-rename step so a partial
 * write is never over-shared. Failures are reported via
 * `onWarn` and never thrown.
 */
export async function chmodOwnerReadWriteOnlyAsync(
  file: string,
  onWarn: PermissionWarn = noopWarn,
): Promise<boolean> {
  if (!file) return false;
  if (!existsSync(file)) {
    onWarn(`chmodOwnerReadWriteOnly: file does not exist: ${file}`);
    return false;
  }
  if (!IS_POSIX) {
    // Windows: silently skip. The data is still written;
    // the per-user ACL is the OS's responsibility.
    return false;
  }
  try {
    await fsChmodAsync(file, OWNER_RW_ONLY);
    return true;
  } catch (err) {
    onWarn(
      `chmodOwnerReadWriteOnly: could not chmod 0600 ${file}: ${(err as Error).message ?? String(err)}`,
    );
    return false;
  }
}

/**
 * Sync variant of {@link chmodOwnerReadWriteOnlyAsync}.
 * Useful for tests and for CLI shutdown paths where
 * we'd rather block the event loop than add another
 * `await`. Failures are reported via `onWarn` and never
 * thrown.
 */
export function chmodOwnerReadWriteOnly(
  file: string,
  onWarn: PermissionWarn = noopWarn,
): boolean {
  if (!file) return false;
  if (!existsSync(file)) {
    onWarn(`chmodOwnerReadWriteOnly: file does not exist: ${file}`);
    return false;
  }
  if (!IS_POSIX) {
    return false;
  }
  try {
    chmodSync(file, OWNER_RW_ONLY);
    return true;
  } catch (err) {
    onWarn(
      `chmodOwnerReadWriteOnly: could not chmod 0600 ${file}: ${(err as Error).message ?? String(err)}`,
    );
    return false;
  }
}

/**
 * Apply 0600 to a directory's immediate children. The
 * directory itself is left at 0700 (owner-only) so
 * `ls` does not show the contents to other users on
 * a shared host. Errors on individual children are
 * reported but do not stop the loop.
 */
export async function chmodDirectoryOwnerOnlyAsync(
  dir: string,
  onWarn: PermissionWarn = noopWarn,
): Promise<{ chmoded: number; skipped: number }> {
  if (!IS_POSIX) return { chmoded: 0, skipped: 0 };
  // Lazy import so this helper stays cheap on the
  // hot path (jsonl-writer doesn't need it).
  const { readdir } = await import('node:fs/promises');
  let chmoded = 0;
  let skipped = 0;
  try {
    const entries = await readdir(dir, { withFileTypes: true });
    for (const e of entries) {
      const p = `${dir}/${e.name}`;
      try {
        const st = statSync(p);
        const mode = e.isDirectory() ? 0o700 : OWNER_RW_ONLY;
        if (st.isDirectory()) {
          chmodSync(p, mode);
        } else {
          chmodSync(p, mode);
        }
        chmoded += 1;
      } catch (err) {
        skipped += 1;
        onWarn(
          `chmodDirectoryOwnerOnly: could not chmod ${p}: ${(err as Error).message ?? String(err)}`,
        );
      }
    }
  } catch (err) {
    onWarn(
      `chmodDirectoryOwnerOnly: could not read dir ${dir}: ${(err as Error).message ?? String(err)}`,
    );
  }
  return { chmoded, skipped };
}

/**
 * `mode` (0o600) is exposed as a constant for callers
 * that need it (e.g. tests asserting on a file's mode).
 */
export const SECURE_FILE_MODE = OWNER_RW_ONLY;
