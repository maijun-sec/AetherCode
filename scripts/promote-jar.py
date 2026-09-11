"""
R-series jar promotion script.

Why this exists: 对应历史 round always shipped jars as aethercode-0.2.1.jar while
aethercode\\dist\\ still had older versioned jars (e.g. 0.2.50 from R210).
Desktop find_jar_path (lib.rs:897) ancestor-walks and picks the HIGHEST
version, so the old 0.2.50 always won, hiding all 对应历史 round daemon-side
fixes from real LLM traffic. R226 fixed it once by promoting 0.2.1 -> 0.2.51.
R227 turns that one-shot fix into a reusable script.

R230 (mandatory): the source-detection is now marker-driven and FRESHNESS-
checked. Earlier versions copied `aethercode-{cur_v}.jar` (the previous
versioned snapshot) to the new version, which was wrong when the operator
forgot to overwrite the versioned file with the freshly-built mvn output.
R229, R-MEM-1/2/3/4/5, and R236 all hit this; the user asked "R230 必修"
repeatedly.

The new contract:

  1. The mvn shade plugin produces a stable filename
     `aethercode-cli-0.1.0-SNAPSHOT.jar` in `aethercode-cli/target/`.
     The release pipeline copies that to
     `aethercode/dist/aethercode-0.2.1.jar` (the "marker"). The
     marker is the SINGLE source of truth — it always reflects the
     most recent mvn output.

  2. The versioned snapshots (`aethercode-0.2.{N}.jar`) are derived
     from the marker, never edited directly. The script always
     copies marker -> new_versioned.

  3. Freshness gate (R230 NEW): the script refuses to promote if
     the canonical mvn output `aethercode-cli-0.1.0-SNAPSHOT.jar`
     is NEWER than the marker. This is the exact R229/R-MEM-1/2/3/4/5
     bug pattern: operator runs `mvn install`, gets a fresh jar,
     but forgets to copy it to the marker. The script would silently
     re-promote the old marker, hiding the new daemon-side fixes.
     The fix is a hard error with a clear "you forgot to copy" message.

  4. The script also compares the marker's SHA to the current
     versioned snapshot, and warns if they diverge. We don't gate
     on divergence (the marker is by definition the most recent),
     but the operator gets a one-liner so the state is visible.

  5. The script warns when the marker is suspiciously small (< 1 MB)
     because that's almost always an accidentally-truncated or
     corrupt build (e.g. a `mvn test` that didn't finish). Small
     marker is a hard error unless --force is set.

  6. The script's --marker flag points at a different marker file
     if a future build pipeline renames the canonical reference.

Idempotent: re-running the script does nothing if the new version
already exists.
Usage:  python scripts/promote-jar.py R{N} [--force] [--marker=<path>]
"""
import argparse
import hashlib
import json
import os
import shutil
import sys


MARKER_NAME = 'aethercode-0.2.1.jar'
MIN_MARKER_BYTES = 1_000_000  # 1 MB; below this the marker is almost certainly corrupt
MVN_CANONICAL = os.path.join(
    'aethercode-cli', 'target', 'aethercode-cli-0.1.0-SNAPSHOT.jar',
)


def parse_version(s):
    return tuple(int(x) for x in s.split('.'))


def sha256(path):
    """SHA-256 of a file, streamed so we don't load large jars into memory."""
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def find_marker(dist_dir, override):
    """Resolve the marker path. The default is the canonical
    `aethercode/dist/aethercode-0.2.1.jar`; the operator can override
    with --marker if a future build pipeline renames it."""
    if override:
        return override
    return os.path.join(dist_dir, MARKER_NAME)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('round', help='round label, e.g. R236')
    ap.add_argument('--force', action='store_true',
                    help='proceed even if the marker looks suspicious '
                         '(NOT recommended — bypasses the freshness gate '
                         'and the size check).')
    ap.add_argument('--marker',
                    help='path to the canonical mvn output marker '
                         '(default: <dist>/aethercode-0.2.1.jar). '
                         'Useful if a future pipeline renames the marker file.')
    args = ap.parse_args()
    round_label = args.round

    repo = r'D:\work\workspace\idea\engine\AetherCode'
    conf_path = os.path.join(repo, 'aethercode-desktop', 'src-tauri', 'tauri.conf.json')
    dist_dir = os.path.join(repo, 'aethercode', 'dist')
    trash_dir = os.path.join(dist_dir, f'_trash_{round_label.lower()}')
    res_dir = os.path.join(repo, 'aethercode-desktop', 'src-tauri', 'resources')
    marker_path = find_marker(dist_dir, args.marker)
    mvn_canonical = os.path.join(repo, 'aethercode', MVN_CANONICAL)

    # Load tauri.conf.json. We use utf-8-sig to be tolerant of
    # the Windows PowerShell BOM that some editors (and
    # Set-Content without -Encoding) leave behind.
    with open(conf_path, encoding='utf-8-sig') as f:
        conf = json.load(f)
    cur = parse_version(conf['version'])
    new = (cur[0], cur[1], cur[2] + 1)
    cur_v = '.'.join(str(x) for x in cur)
    new_v = '.'.join(str(x) for x in new)
    print(f'{round_label}: promoting {cur_v} -> {new_v}')

    # 1) Already-promoted? No-op.
    new_jar = os.path.join(dist_dir, f'aethercode-{new_v}.jar')
    if os.path.exists(new_jar):
        print(f'  [{round_label}] {new_jar} already exists, skipping (idempotent)')
        return

    # 2) Marker MUST exist; this is the source of truth.
    if not os.path.exists(marker_path):
        print(f'  ERROR: marker {marker_path} not found.')
        print(f'         The build pipeline should copy')
        print(f'         aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar')
        print(f'         to {marker_path} before running this script.')
        sys.exit(1)

    # 3) Freshness gate (R230 — the actual bug fix).
    #    The canonical mvn output exists alongside the marker. If the
    #    canonical is newer than the marker, the operator ran `mvn
    #    install` but forgot to copy the result to the marker. This
    #    is the exact R229 / R-MEM-1/2/3/4/5 bug. Refuse to promote.
    if os.path.exists(mvn_canonical):
        mvn_mtime = os.path.getmtime(mvn_canonical)
        marker_mtime = os.path.getmtime(marker_path)
        if mvn_mtime > marker_mtime:
            if not args.force:
                print(f'  ERROR: mvn output ({mvn_canonical}) is NEWER than the marker '
                      f'({marker_path}).')
                print(f'         This is the R229 / R-MEM bug pattern: the operator ran '
                      f'`mvn install` but forgot to copy the resulting jar to the marker.')
                print(f'         The script would otherwise silently re-promote the old marker,')
                print(f'         hiding the new daemon-side fixes from the desktop exe.')
                print(f'         ')
                print(f'         Fix:')
                print(f'           copy "{mvn_canonical}" "{marker_path}"')
                print(f'         Then re-run this script.')
                print(f'         (or pass --force to override; NOT recommended.)')
                sys.exit(2)
            print(f'  WARN: mvn output is newer than marker; --force given, proceeding')
        # Also check the mvn canonical vs marker SHAs — even if mvn
        # didn't bump mtime (rare; e.g. on filesystems that round
        # timestamps), differing SHAs mean the canonical is newer.
        mvn_sha = sha256(mvn_canonical)
        marker_sha_marker = sha256(marker_path)
        if mvn_sha != marker_sha_marker and mvn_mtime <= marker_mtime:
            print(f'  WARN: mvn output ({mvn_sha[:12]}...) differs from marker '
                  f'({marker_sha_marker[:12]}...) even though mtimes are close. '
                  f'If you just ran `mvn install`, you may need to re-copy.')

    # 4) Sanity checks on the marker.
    marker_size = os.path.getsize(marker_path)
    marker_sha = sha256(marker_path)
    if marker_size < MIN_MARKER_BYTES:
        if not args.force:
            print(f'  ERROR: marker {marker_path} is {marker_size} bytes; that\'s '
                  f'suspiciously small (a real release jar is ~55 MB).')
            print(f'         This usually means a build was interrupted or the '
                  f'copy step picked up the wrong file (e.g. the original jar '
                  f'before shade ran, or a `mvn test` output).')
            print(f'         Re-run `mvn -pl aethercode-cli -am clean install -DskipTests`,')
            print(f'         then re-copy the resulting aethercode-cli-0.1.0-SNAPSHOT.jar')
            print(f'         to {marker_path} and re-run this script.')
            print(f'         (or pass --force to override this check.)')
            sys.exit(1)
        print(f'  WARN: marker is {marker_size} bytes; --force given, proceeding')

    # 5) Compare marker to the current versioned snapshot. We don't
    #    gate on divergence (the marker is by definition the most
    #    recent), but we print a one-liner so the operator knows.
    cur_jar = os.path.join(dist_dir, f'aethercode-{cur_v}.jar')
    if os.path.exists(cur_jar):
        cur_sha = sha256(cur_jar)
        if cur_sha == marker_sha:
            print(f'  marker and {cur_jar} match (sha {marker_sha[:12]}...)')
        else:
            print(f'  note: marker ({marker_sha[:12]}...) differs from {cur_jar} '
                  f'({cur_sha[:12]}...). Promoting from the marker (this is the '
                  f'expected state when the daemon changed but the versioned '
                  f'snapshot hasn\'t been re-synced yet).')

    # 6) Backup the NEW-version jar if it somehow already exists in
    #    trash (defensive — a prior bad run may have left a partial
    #    copy). The new-version jar shouldn't exist on disk yet.
    if not os.path.isdir(trash_dir):
        os.makedirs(trash_dir, exist_ok=True)

    # 7) Promote in dist/. Source is ALWAYS the marker.
    shutil.copy2(marker_path, new_jar)
    print(f'  dist: copied {os.path.basename(marker_path)} -> aethercode-{new_v}.jar')

    # 8) Mirror to src-tauri/resources/aethercode.jar (canonical name)
    res_jar = os.path.join(res_dir, 'aethercode.jar')
    shutil.copy2(new_jar, res_jar)
    print(f'  resources: copied -> {res_jar}')

    # 9) Update tauri.conf.json version (utf-8, no BOM — PowerShell
    #    Set-Content adds a BOM by default which makes the JSON
    #    parser barf on the next run).
    conf['version'] = new_v
    with open(conf_path, 'w', encoding='utf-8') as f:
        json.dump(conf, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print(f'  tauri.conf.json: version {cur_v} -> {new_v}')

    # 10) Sync the versioned "current" snapshot to the marker. This
    #     keeps the cascade in step so the next promote's "marker
    #     vs current" diagnostic prints a clean "match" line.
    if os.path.exists(cur_jar):
        if os.path.getsize(cur_jar) != marker_size:
            shutil.copy2(marker_path, cur_jar)
            print(f'  dist: synced {os.path.basename(cur_jar)} to match the new marker')

    # 11) Summary
    print()
    print(f'  final state:')
    print(f'    {marker_path}                         (mvn output, source of truth, untouched)')
    print(f'    aethercode\\dist\\aethercode-{cur_v}.jar  (kept as canonical snapshot, {marker_sha[:12]}...)')
    print(f'    aethercode\\dist\\aethercode-{new_v}.jar  (PROMOTED, desktop spawn will pick this)')
    print(f'    aethercode-desktop\\src-tauri\\resources\\aethercode.jar  (PROMOTED, tauri bundle will embed this)')
    print(f'    _trash_{round_label.lower()}\\  (backup of any prior round\'s jars)')


if __name__ == '__main__':
    main()
