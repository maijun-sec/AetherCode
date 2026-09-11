package org.aethercode.core.fs.backend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Shell command builders and stdout parsers for {@link BaseSandbox}.
 *
 * <p>Each {@code buildXxxCmd} returns the string the sandbox will execute
 * (most of them wrap a {@code python3 -c "..."} script with base64-encoded
 * arguments to avoid shell quoting pitfalls). The matching {@code parseXxxOutput}
 * converts the JSON or NUL-delimited response into a typed
 * {@code *Result} record.</p>
 *
 * <p>The Python source scripts inside the text blocks below are kept as
 * close to the upstream port as possible; only escaping (which Python's
 * {@code "} &rbrace; conflicts with Java text blocks) differs.</p>
 */
final class SandboxCmds {

    private static final String GLOB_COMMAND_TEMPLATE = """
            python3 -c "
            import fnmatch
            import os
            import json
            import base64
            import time

            # Decode base64-encoded parameters
            path = base64.b64decode('%s').decode('utf-8')
            pattern = base64.b64decode('%s').decode('utf-8')

            MAX_EXPANSIONS = 1000
            MAX_MATCHES = 10000
            TIME_BUDGET = 5.0


            def _find_group_end(pat, start):
                # Index of the '}}' closing the group opened at 'start', or -1 if unbalanced.
                depth = 0
                for index in range(start, len(pat)):
                    if pat[index] == '{':
                        depth += 1
                    elif pat[index] == '}':
                        depth -= 1
                        if depth == 0:
                            return index
                return -1


            def _split_alternatives(body):
                # Split on top-level commas only, so nested groups survive intact.
                parts = []
                depth = 0
                current = ''
                for ch in body:
                    if ch == '{':
                        depth += 1
                        current += ch
                    elif ch == '}':
                        depth -= 1
                        current += ch
                    elif ch == ',' and depth == 0:
                        parts.append(current)
                        current = ''
                    else:
                        current += ch
                parts.append(current)
                return parts


            def _brace_expand(pat):
                # pattern is model/user supplied, so expansion must be bounded: the full
                # Cartesian product is materialized in memory, and 2**n groups would
                # otherwise hang or OOM the sandbox before the walk starts. Returns None
                # past the budget, mirroring the expansion limit wcmatch enforces in
                # compile_grep_include_glob. Nested groups expand like wcmatch's BRACE.
                start = pat.find('{')
                if start < 0:
                    return [pat]
                end = _find_group_end(pat, start)
                if end < 0:
                    return [pat]
                prefix, body, suffix = pat[:start], pat[start + 1 : end], pat[end + 1 :]
                parts = _split_alternatives(body)
                if len(parts) < 2:
                    # A single-element group is literal, but the rest may still expand.
                    tails = _brace_expand(suffix)
                    if tails is None:
                        return None
                    return [prefix + '{' + body + '}' + tail for tail in tails]
                out = []
                for part in parts:
                    tails = _brace_expand(part + suffix)
                    if tails is None:
                        return None
                    for tail in tails:
                        out.append(prefix + tail)
                        if len(out) > MAX_EXPANSIONS:
                            return None
                return out


            def _normalize_classes(pat):
                # fnmatch reads a leading '^' in a bracket expression as a literal, while
                # wcmatch (and bash/ripgrep) read it as negation. Without this rewrite
                # '[^a]*.py' is inverted on the sandbox: it returns exactly the files the
                # caller meant to exclude.
                out = ''
                index = 0
                while index < len(pat):
                    if pat[index] != '[':
                        out += pat[index]
                        index += 1
                        continue
                    # Start at index + 2 so a literal ']' first in the set is kept ('[]]').
                    close = pat.find(']', index + 2)
                    if close < 0:
                        out += pat[index:]
                        break
                    body = pat[index + 1 : close]
                    if body.startswith('^'):
                        body = '!' + body[1:]
                    out += '[' + body + ']'
                    index = close + 1
                return out


            def _basename_match(name, candidates):
                for candidate in candidates:
                    # No DOTMATCH: leading-dot basenames need an explicit leading '.' pattern.
                    if name.startswith('.') and not candidate.startswith('.'):
                        continue
                    # fnmatchcase, not fnmatch: fnmatch applies os.path.normcase, which would
                    # make matching case-insensitive on a non-POSIX host. wcmatch is always
                    # case-sensitive here.
                    if fnmatch.fnmatchcase(name, candidate):
                        return True
                return False


            def _parts_match(rel_parts, pat_parts):
                # Memoized on (path index, pattern index): '**' otherwise backtracks
                # exponentially, so '**/*/**/*'-shaped patterns hang the sandbox.
                cache = {}

                def match_from(ri, pi):
                    key = (ri, pi)
                    if key in cache:
                        return cache[key]
                    result = _compute(ri, pi)
                    cache[key] = result
                    return result

                def _compute(ri, pi):
                    while pi < len(pat_parts):
                        if pat_parts[pi] == '**':
                            while pi < len(pat_parts) and pat_parts[pi] == '**':
                                pi += 1
                            if pi == len(pat_parts):
                                # A slash before a trailing ** requires at least one
                                # descendant; a.py/** must not match the file a.py.
                                return ri < len(rel_parts) and all(not part.startswith('.') for part in rel_parts[ri:])
                            while ri <= len(rel_parts):
                                if match_from(ri, pi):
                                    return True
                                if ri == len(rel_parts):
                                    break
                                # ** without DOTMATCH does not traverse leading-dot segments.
                                if rel_parts[ri].startswith('.'):
                                    return False
                                ri += 1
                            return False
                        if ri == len(rel_parts):
                            cache[key] = False
                            return False
                        if not _basename_match(rel_parts[ri], [pat_parts[pi]]):
                            cache[key] = False
                            return False
                        ri += 1
                        pi += 1
                    return ri == len(rel_parts)

                return match_from(0, 0)


            def _path_match(rel, candidates):
                rel_parts = rel.split('/')
                for pat in candidates:
                    norm = _normalize_classes(pat)
                    if norm.startswith('**/'):
                        pat_parts = norm[3:].split('/')
                        if _parts_match(rel_parts, pat_parts):
                            return True
                    else:
                        pat_parts = norm.split('/')
                        if _parts_match(rel_parts, pat_parts):
                            return True
                return False


            def _include_match(rel, pat, candidates):
                if not pat:
                    return True
                if '/' in pat:
                    return _path_match(rel, [pat])
                return _basename_match(rel.rsplit('/', 1)[-1], [pat])


            def _on_walk_error(err):
                # `os.walk` can yield a permission error on a parent the agent can read
                # but not list (e.g. another user's home). Surface those as warnings rather
                # than failing the whole search: the read-restricted subtree just doesn't
                # contribute matches.
                print(json.dumps({'warning': 'skipped: ' + str(err)}))
            """;

    private static final String GREP_PATH_GLOB_TEMPLATE = """
            python3 -c "
            import os
            import re
            import sys
            import fnmatch
            import json
            import base64

            path = base64.b64decode('%s').decode('utf-8')
            glob_pat = base64.b64decode('%s').decode('utf-8')
            pat = base64.b64decode('%s').decode('utf-8')
            max_count = %s
            matches = []
            truncated = False
            try:
                for root, _dirs, files in os.walk(path):
                    for name in files:
                        fp = os.path.join(root, name)
                        rel = os.path.relpath(fp, path)
                        if not _include_match(rel, glob_pat, [glob_pat]):
                            continue
                        try:
                            with open(fp, 'r', encoding='utf-8', errors='strict') as f:
                                for line_num, line in enumerate(f, 1):
                                    if pat in line:
                                        if max_count is not None and len(matches) >= max_count:
                                            print(json.dumps({'warning': 'truncated'}))
                                            truncated = True
                                            break
                                        record = {'path': fp, 'line': line_num, 'text': line.rstrip('\\\\n')}
                                        print(json.dumps(record))
                                        matches.append(record)
                                if truncated:
                                    sys.exit(0)
                        except (UnicodeDecodeError, OSError):
                            continue
            except OSError as err:
                print(json.dumps({'error': str(err)}))
            "
            """;

    private static final String READ_COMMAND_TEMPLATE = """
            python3 -c "
            import os
            import sys
            import json
            import base64
            import time

            file_path = base64.b64decode('%s').decode('utf-8')
            file_type = '%s'
            offset = %d
            limit = %d

            try:
                if not os.path.exists(file_path) and not os.path.islink(file_path):
                    print(json.dumps({'error': 'file_not_found'}))
                    sys.exit(0)
                if not os.path.isfile(file_path) and not os.path.islink(file_path):
                    print(json.dumps({'error': 'not_a_file'}))
                    sys.exit(0)

                if file_type != 'text':
                    with open(file_path, 'rb') as f:
                        raw = f.read()
                    encoded = base64.b64encode(raw).decode('ascii')
                    print(json.dumps({'content': encoded, 'encoding': 'base64'}))
                    sys.exit(0)

                with open(file_path, 'r', encoding='utf-8', errors='strict') as f:
                    content = f.read()
                if not content:
                    print(json.dumps({'content': '', 'encoding': 'utf-8'}))
                    sys.exit(0)
                lines = content.split('\\\\n')
                if limit <= 0:
                    print(json.dumps({'no_lines_requested': True, 'encoding': 'utf-8'}))
                    sys.exit(0)
                start = max(0, min(offset, len(lines)))
                end = min(start + limit, len(lines))
                sliced = '\\\\n'.join(lines[start:end])
                if not sliced and start >= len(lines):
                    # Offset past EOF: return content untouched, no line window.
                    print(json.dumps({'content': content, 'encoding': 'utf-8'}))
                    sys.exit(0)
                print(json.dumps({
                    'content': sliced,
                    'encoding': 'utf-8',
                    'start_line': start + 1,
                    'end_line': end,
                    'total_lines': len(lines),
                    'next_offset': end,
                }))
            except (OSError, UnicodeDecodeError) as err:
                print(json.dumps({'error': str(err)}))
            "
            """;

    private static final String WRITE_CHECK_TEMPLATE = """
            python3 -c "
            import os
            import sys
            import base64

            file_path = base64.b64decode('%s').decode('utf-8')
            parent = os.path.dirname(file_path) or '.'
            try:
                os.makedirs(parent, exist_ok=True)
            except OSError as err:
                print('Error: ' + str(err))
                sys.exit(1)
            print('ok')
            "
            """;

    private static final String EDIT_COMMAND_TEMPLATE = """
            python3 -c "
            import os
            import sys
            import json
            import base64

            payload = json.loads(base64.b64decode('%s').decode('utf-8'))
            file_path = payload['path']
            old = payload['old']
            new = payload['new']
            replace_all = payload['replace_all']

            if not os.path.isfile(file_path):
                print(json.dumps({'error': 'file_not_found'}))
                sys.exit(0)
            try:
                with open(file_path, 'r', encoding='utf-8', errors='strict') as f:
                    content = f.read()
            except (OSError, UnicodeDecodeError) as err:
                print(json.dumps({'error': 'permission_denied'}))
                sys.exit(0)

            occurrences = content.count(old)
            if occurrences == 0:
                # Try CRLF/LF normalization.
                old_lf = old.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')
                new_lf = new.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')
                content_lf = content.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')
                occurrences = content_lf.count(old_lf)
                if occurrences == 0:
                    print(json.dumps({'error': 'string_not_found'}))
                    sys.exit(0)
                if not replace_all and occurrences > 1:
                    print(json.dumps({'error': 'multiple_occurrences'}))
                    sys.exit(0)
                updated = content_lf.replace(old_lf, new_lf) if replace_all else content_lf.replace(old_lf, new_lf, 1)
            else:
                if not replace_all and occurrences > 1:
                    print(json.dumps({'error': 'multiple_occurrences'}))
                    sys.exit(0)
                updated = content.replace(old, new) if replace_all else content.replace(old, new, 1)

            try:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(updated)
            except OSError as err:
                print(json.dumps({'error': 'permission_denied'}))
                sys.exit(0)
            print(json.dumps({'count': occurrences}))
            "
            """;

    private static final String EDIT_TMPFILE_TEMPLATE = """
            python3 -c "
            import os
            import sys
            import json

            old_path = '%s'
            new_path = '%s'
            target = '%s'
            replace_all = %s

            try:
                with open(old_path, 'r', encoding='utf-8', errors='strict') as f:
                    old = f.read()
                with open(new_path, 'r', encoding='utf-8', errors='strict') as f:
                    new = f.read()
            except (OSError, UnicodeDecodeError):
                print(json.dumps({'error': 'permission_denied'}))
                sys.exit(0)

            if not os.path.isfile(target):
                print(json.dumps({'error': 'file_not_found'}))
                sys.exit(0)
            with open(target, 'r', encoding='utf-8', errors='strict') as f:
                content = f.read()

            occurrences = content.count(old)
            if occurrences == 0:
                old_lf = old.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')
                new_lf = new.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')
                content_lf = content.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')
                occurrences = content_lf.count(old_lf)
                if occurrences == 0:
                    print(json.dumps({'error': 'string_not_found'}))
                    sys.exit(0)
                if not replace_all and occurrences > 1:
                    print(json.dumps({'error': 'multiple_occurrences'}))
                    sys.exit(0)
                updated = content_lf.replace(old_lf, new_lf) if replace_all else content_lf.replace(old_lf, new_lf, 1)
            else:
                if not replace_all and occurrences > 1:
                    print(json.dumps({'error': 'multiple_occurrences'}))
                    sys.exit(0)
                updated = content.replace(old, new) if replace_all else content.replace(old, new, 1)

            try:
                with open(target, 'w', encoding='utf-8') as f:
                    f.write(updated)
            except OSError:
                print(json.dumps({'error': 'permission_denied'}))
                sys.exit(0)
            finally:
                try:
                    os.remove(old_path)
                except OSError:
                    pass
                try:
                    os.remove(new_path)
                except OSError:
                    pass
            print(json.dumps({'count': occurrences}))
            "
            """;

    private static final String GLOB_WALK_PRELUDE =
            GLOB_COMMAND_TEMPLATE
                    + "\n" + GREP_PATH_GLOB_TEMPLATE
                    + "\n# Walk with the shared backend contract applied via the in-script helpers.\n"
                    + "results = []\n"
                    + "truncated = False\n"
                    + "deadline = time.monotonic() + TIME_BUDGET\n"
                    + "try:\n"
                    + "    for root, _dirs, files in os.walk(path):\n"
                    + "        if time.monotonic() > deadline:\n"
                    + "            truncated = True\n"
                    + "            break\n"
                    + "        for name in files:\n"
                    + "            fp = os.path.join(root, name)\n"
                    + "            rel = os.path.relpath(fp, path)\n"
                    + "            for pat in _brace_expand(pattern) or []:\n"
                    + "                if _path_match(rel, [pat]):\n"
                    + "                    if len(results) >= MAX_MATCHES:\n"
                    + "                        truncated = True\n"
                    + "                        break\n"
                    + "                    is_dir = os.path.isdir(fp)\n"
                    + "                    print(json.dumps({'path': rel, 'is_dir': is_dir}))\n"
                    + "                    results.append((rel, is_dir))\n"
                    + "            if truncated:\n"
                    + "                break\n"
                    + "        if truncated:\n"
                    + "            break\n"
                    + "except OSError as err:\n"
                    + "    print(json.dumps({'error': str(err)}))\n"
                    + "\"";

    private static final String EXECUTE_CAPTURE_CMD_TEMPLATE = """
            # ===== deepagents capture-at-source offload (auto-generated wrapper) =====
            # Runs the requested command below, capturing its combined output to a file in
            # the sandbox: returned inline when small, or as a head/tail preview when large
            # (the full result stays at the path for read_file). Disable this wrapping with
            # BaseSandbox.enable_capture_offload = False.
            __da_f=__PATH_Q__
            __da_ecf="$__da_f.ec"
            mkdir -p "$(dirname "$__da_f")" 2>/dev/null
            # ----- requested command (verbatim, between the heredoc markers) -----
            __da_cmd=$(cat <<'__DELIM__'
            __COMMAND__
            __DELIM__
            )
            # ----- end requested command; everything below is offload machinery -----
            { ( eval "$__da_cmd" ); echo "$?" > "$__da_ecf"; } 2>&1 | { head -c __MAXBYTES__ > "$__da_f"; cat > /dev/null; }
            __da_ec=$(cat "$__da_ecf" 2>/dev/null)
            : "${__da_ec:=1}"
            rm -f "$__da_ecf"
            __da_bytes=$(wc -c < "$__da_f" 2>/dev/null | tr -d ' ')
            : "${__da_bytes:=0}"
            __da_capped=0
            [ "$__da_bytes" -ge __MAXBYTES__ ] && __da_capped=1
            if [ "$__da_bytes" -le __BUDGET__ ]; then
              printf '%s %s %s %s\\n' '__SENTINEL__' "$__da_ec" 0 0
              cat "$__da_f"
              rm -f "$__da_f"
            else
              __da_lines=$(wc -l < "$__da_f" 2>/dev/null | tr -d ' ')
              : "${__da_lines:=0}"
              __da_omitted=$((__da_lines - __HEADLINES__ - __TAILLINES__))
              printf '%s %s %s %s\\n' '__SENTINEL__' "$__da_ec" 1 "$__da_capped"
              if [ "$__da_omitted" -gt 0 ]; then
                head -c __HEAD__ "$__da_f" | head -n __HEADLINES__
                printf '... [%s lines truncated] ...\\n' "$__da_omitted"
                tail -c __TAIL__ "$__da_f" | tail -n __TAILLINES__
              else
                head -c $((__HEAD__ + __TAIL__)) "$__da_f"
              fi
            fi
            """;

    private SandboxCmds() {}

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String shQuote(String s) {
        // POSIX shell single-quote, with embedded single quotes handled.
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    static String randomEditUid() {
        byte[] buf = new byte[10];
        new Random().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf).toLowerCase();
    }

    // -----------------------------------------------------------------
    //  ls
    // -----------------------------------------------------------------

    static String buildLsCmd(String path) {
        String b64 = b64(path);
        return "python3 -c \"\n"
                + "import os\n"
                + "import json\n"
                + "import base64\n\n"
                + "path = base64.b64decode('" + b64 + "').decode('utf-8')\n\n"
                + "try:\n"
                + "    with os.scandir(path) as it:\n"
                + "        for entry in it:\n"
                + "            result = {\n"
                + "                'path': os.path.join(path, entry.name),\n"
                + "                'is_dir': entry.is_dir(follow_symlinks=False)\n"
                + "            }\n"
                + "            print(json.dumps(result))\n"
                + "except FileNotFoundError:\n"
                + "    print(json.dumps({'error': 'path_not_found'}))\n"
                + "except NotADirectoryError:\n"
                + "    print(json.dumps({'error': 'not_a_directory'}))\n"
                + "except PermissionError:\n"
                + "    print(json.dumps({'error': 'permission_denied'}))\n"
                + "\" 2>/dev/null";
    }

    static LsResult parseLsOutput(String output, String path) {
        List<FileInfo> fileInfos = new ArrayList<>();
        String error = null;
        for (String line : output.strip().split("\n")) {
            if (line.isEmpty()) continue;
            Object parsed;
            try {
                parsed = MiniJson.parse(line);
            } catch (Exception e) {
                continue;
            }
            if (!(parsed instanceof Map<?, ?> map)) continue;
            Object err = map.get("error");
            if (err instanceof String) {
                error = (String) err;
                continue;
            }
            Object p = map.get("path");
            Object d = map.get("is_dir");
            if (p instanceof String) {
                boolean isDir = (d instanceof Boolean) && (Boolean) d;
                fileInfos.add(isDir ? FileInfo.directory((String) p) : FileInfo.file((String) p));
            }
        }
        if (error != null) {
            return LsResult.error("Path '" + path + "': " + error);
        }
        return LsResult.of(fileInfos);
    }

    // -----------------------------------------------------------------
    //  read
    // -----------------------------------------------------------------

    static String buildReadCmd(String filePath, int offset, int limit) {
        String fileType = "text";
        // Approximate the Python get_backend_read_file_type: anything not text
        // is treated as binary. The Python port consults an extension map; the
        // Java port's BackendUtils.getFileType() does the same.
        switch (BackendUtils.getFileType(filePath)) {
            case BINARY: fileType = "video"; break; // close-enough marker; details are not used.
            default: fileType = "text";
        }
        if (offset < 0) offset = 0;
        if (limit < 0) limit = 0;
        String b64 = b64(filePath);
        return "python3 -c \"\n"
                + "import os\nimport sys\nimport json\nimport base64\n\n"
                + "file_path = base64.b64decode('" + b64 + "').decode('utf-8')\n"
                + "file_type = '" + fileType + "'\n"
                + "offset = " + offset + "\n"
                + "limit = " + limit + "\n\n"
                + "try:\n"
                + "    if not os.path.exists(file_path) and not os.path.islink(file_path):\n"
                + "        print(json.dumps({'error': 'file_not_found'}))\n"
                + "        sys.exit(0)\n"
                + "    if not os.path.isfile(file_path) and not os.path.islink(file_path):\n"
                + "        print(json.dumps({'error': 'not_a_file'}))\n"
                + "        sys.exit(0)\n\n"
                + "    if file_type != 'text':\n"
                + "        with open(file_path, 'rb') as f:\n"
                + "            raw = f.read()\n"
                + "        encoded = base64.b64encode(raw).decode('ascii')\n"
                + "        print(json.dumps({'content': encoded, 'encoding': 'base64'}))\n"
                + "        sys.exit(0)\n\n"
                + "    with open(file_path, 'r', encoding='utf-8', errors='strict') as f:\n"
                + "        content = f.read()\n"
                + "    if not content:\n"
                + "        print(json.dumps({'content': '', 'encoding': 'utf-8'}))\n"
                + "        sys.exit(0)\n"
                + "    lines = content.split('\\\\n')\n"
                + "    if limit <= 0:\n"
                + "        print(json.dumps({'no_lines_requested': True, 'encoding': 'utf-8'}))\n"
                + "        sys.exit(0)\n"
                + "    start = max(0, min(offset, len(lines)))\n"
                + "    end = min(start + limit, len(lines))\n"
                + "    sliced = '\\\\n'.join(lines[start:end])\n"
                + "    if not sliced and start >= len(lines):\n"
                + "        print(json.dumps({'content': content, 'encoding': 'utf-8'}))\n"
                + "        sys.exit(0)\n"
                + "    print(json.dumps({\n"
                + "        'content': sliced, 'encoding': 'utf-8',\n"
                + "        'start_line': start + 1, 'end_line': end,\n"
                + "        'total_lines': len(lines), 'next_offset': end,\n"
                + "    }))\n"
                + "except (OSError, UnicodeDecodeError) as err:\n"
                + "    print(json.dumps({'error': str(err)}))\n"
                + "\"";
    }

    static ReadResult parseReadOutput(String output, String filePath) {
        String trimmed = output.replaceAll("\\s+$", "");
        Object parsed;
        try {
            parsed = MiniJson.parse(trimmed);
        } catch (Exception e) {
            String detail = trimmed.isEmpty() ? "(empty)" : trimmed.substring(0, Math.min(200, trimmed.length()));
            return ReadResult.error("File '" + filePath + "': unexpected server response: " + detail);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            String detail = trimmed.isEmpty() ? "(empty)" : trimmed.substring(0, Math.min(200, trimmed.length()));
            return ReadResult.error("File '" + filePath + "': unexpected server response: " + detail);
        }
        Object err = map.get("error");
        if (err instanceof String) {
            return ReadResult.error("File '" + filePath + "': " + err);
        }
        Object content = map.get("content");
        Object encoding = map.get("encoding");
        try {
            FileData fd = FileData.of(
                    content == null ? "" : content.toString(),
                    encoding instanceof String ? (String) encoding : "utf-8");
            boolean noLines = Boolean.TRUE.equals(map.get("no_lines_requested"));
            if (noLines) {
                return ReadResult.empty();
            }
            Object total = map.get("total_lines");
            Object start = map.get("start_line");
            Object end = map.get("end_line");
            Object next = map.get("next_offset");
            if (total == null && start == null && end == null) {
                return ReadResult.of(fd);
            }
            return new ReadResult(
                    Optional.empty(),
                    Optional.of(fd),
                    Optional.ofNullable(toInt(total)),
                    Optional.ofNullable(toInt(start)),
                    Optional.ofNullable(toInt(end)),
                    Optional.ofNullable(toInt(next)),
                    false);
        } catch (Exception ex) {
            return ReadResult.error("File '" + filePath + "': unexpected server response: " + ex.getMessage());
        }
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  write preflight
    // -----------------------------------------------------------------

    static String buildWritePreflightCmd(String filePath) {
        return "python3 -c \"\n"
                + "import os, sys, base64\n"
                + "p = base64.b64decode('" + b64(filePath) + "').decode('utf-8')\n"
                + "parent = os.path.dirname(p) or '.'\n"
                + "try:\n"
                + "    os.makedirs(parent, exist_ok=True)\n"
                + "except OSError as e:\n"
                + "    print('Error: ' + str(e))\n"
                + "    sys.exit(1)\n"
                + "print('ok')\n\"";
    }

    static WriteResult checkPreflightResult(ExecuteResponse result, String filePath) {
        if (result.exitCode().isPresent() && result.exitCode().get() != 0
                || result.output().contains("Error:")) {
            String err = result.output().strip();
            if (err.isEmpty()) err = "Failed to write file '" + filePath + "'";
            return WriteResult.failure(err);
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  grep
    // -----------------------------------------------------------------

    static String buildGrepCmd(String pattern, String path, String glob, Integer maxCount) {
        String searchPath = shQuote(path != null ? path : ".");
        String patEsc = shQuote(pattern);
        String maxCountStr = maxCount == null ? "None" : String.valueOf(maxCount.intValue());
        if (glob != null && glob.contains("/")) {
            String pathB64 = b64(path != null ? path : ".");
            String globB64 = b64(glob);
            String patB64 = b64(pattern);
            return "python3 -c \"\n"
                    + "import os, sys, base64\n"
                    + "p = base64.b64decode('" + pathB64 + "').decode('utf-8')\n"
                    + "g = base64.b64decode('" + globB64 + "').decode('utf-8')\n"
                    + "pat = base64.b64decode('" + patB64 + "').decode('utf-8')\n"
                    + "max_count = " + maxCountStr + "\n"
                    + "match_count = 0\n"
                    + "try:\n"
                    + "    for root, _dirs, files in os.walk(p):\n"
                    + "        for name in files:\n"
                    + "            fp = os.path.join(root, name)\n"
                    + "            rel = os.path.relpath(fp, p)\n"
                    + "            if not _include_match(rel, g, [g]):\n"
                    + "                continue\n"
                    + "            try:\n"
                    + "                with open(fp, 'r', encoding='utf-8', errors='ignore') as f:\n"
                    + "                    for line_num, line in enumerate(f, 1):\n"
                    + "                        if pat in line:\n"
                    + "                            sys.stdout.write(fp + chr(0) + str(line_num) + ':' + line.rstrip(chr(10)) + chr(10))\n"
                    + "                            match_count += 1\n"
                    + "                            if max_count is not None and match_count > max_count:\n"
                    + "                                sys.exit(0)\n"
                    + "            except OSError:\n"
                    + "                continue\n"
                    + "except OSError:\n"
                    + "    pass\n"
                    + "\" 2>/dev/null";
        }
        String globOpt = glob != null ? "--include=" + shQuote(glob) : "";
        String base = "grep -rHnFZ " + globOpt + " -e " + patEsc + " " + searchPath + " 2>/dev/null";
        if (maxCount != null) {
            return base + " | head -n " + (maxCount + 1) + " || true";
        }
        return base + " || true";
    }

    static GrepResult parseGrepOutput(ExecuteResponse result, String path, Integer maxCount) {
        String output = result.output().replaceAll("\\n$", "");
        if (result.exitCode().isPresent() && result.exitCode().get() != 0) {
            String detail = output.strip().isEmpty() ? "exit code " + result.exitCode().get() : output.strip();
            return GrepResult.error("Path '" + (path == null ? "." : path) + "': " + detail);
        }
        if (output.isEmpty()) {
            return GrepResult.of(List.of());
        }
        List<GrepMatch> matches = new ArrayList<>();
        String parseError = null;
        for (String line : output.split("\n")) {
            int nul = line.indexOf('\0');
            if (nul < 0) { parseError = line; continue; }
            String filePath = line.substring(0, nul);
            String rest = line.substring(nul + 1);
            int colon = rest.indexOf(':');
            if (colon < 0) { parseError = line; continue; }
            int lineNum;
            try {
                lineNum = Integer.parseInt(rest.substring(0, colon));
            } catch (NumberFormatException e) {
                parseError = line; continue;
            }
            String text = rest.substring(colon + 1);
            matches.add(new GrepMatch(filePath, lineNum, text, Optional.empty(), Optional.empty()));
        }
        if (parseError != null && matches.isEmpty()) {
            return GrepResult.error("Path '" + (path == null ? "." : path) + "': " + parseError);
        }
        if (maxCount != null && matches.size() > maxCount) {
            return new GrepResult(Optional.empty(), Optional.of(matches.subList(0, maxCount)), true);
        }
        return GrepResult.of(matches);
    }

    // -----------------------------------------------------------------
    //  glob
    // -----------------------------------------------------------------

    static String buildGlobCmd(String pattern, String searchPath) {
        String pathB64 = b64(searchPath);
        String patB64 = b64(pattern);
        return "python3 -c \"\n"
                + "import os, sys, json, base64, time, fnmatch\n\n"
                + "path = base64.b64decode('" + pathB64 + "').decode('utf-8')\n"
                + "pattern = base64.b64decode('" + patB64 + "').decode('utf-8')\n\n"
                + "MAX_EXPANSIONS = 1000\n"
                + "MAX_MATCHES = 10000\n"
                + "TIME_BUDGET = 5.0\n\n"
                + "def _find_group_end(pat, start):\n"
                + "    depth = 0\n"
                + "    for index in range(start, len(pat)):\n"
                + "        if pat[index] == '{': depth += 1\n"
                + "        elif pat[index] == '}':\n"
                + "            depth -= 1\n"
                + "            if depth == 0: return index\n"
                + "    return -1\n\n"
                + "def _split_alternatives(body):\n"
                + "    parts = []\n"
                + "    depth = 0\n"
                + "    current = ''\n"
                + "    for ch in body:\n"
                + "        if ch == '{': depth += 1\n"
                + "        elif ch == '}': depth -= 1\n"
                + "        elif ch == ',' and depth == 0:\n"
                + "            parts.append(current); current = ''\n"
                + "        else: current += ch\n"
                + "    parts.append(current)\n"
                + "    return parts\n\n"
                + "def _brace_expand(pat):\n"
                + "    start = pat.find('{')\n"
                + "    if start < 0: return [pat]\n"
                + "    end = _find_group_end(pat, start)\n"
                + "    if end < 0: return [pat]\n"
                + "    prefix, body, suffix = pat[:start], pat[start + 1 : end], pat[end + 1 :]\n"
                + "    parts = _split_alternatives(body)\n"
                + "    if len(parts) < 2:\n"
                + "        tails = _brace_expand(suffix)\n"
                + "        if tails is None: return None\n"
                + "        return [prefix + '{' + body + '}' + tail for tail in tails]\n"
                + "    out = []\n"
                + "    for part in parts:\n"
                + "        tails = _brace_expand(part + suffix)\n"
                + "        if tails is None: return None\n"
                + "        for tail in tails:\n"
                + "            out.append(prefix + tail)\n"
                + "            if len(out) > MAX_EXPANSIONS: return None\n"
                + "    return out\n\n"
                + "def _normalize_classes(pat):\n"
                + "    out = ''\n"
                + "    index = 0\n"
                + "    while index < len(pat):\n"
                + "        if pat[index] != '[':\n"
                + "            out += pat[index]; index += 1; continue\n"
                + "        close = pat.find(']', index + 2)\n"
                + "        if close < 0:\n"
                + "            out += pat[index:]; break\n"
                + "        body = pat[index + 1 : close]\n"
                + "        if body.startswith('^'): body = '!' + body[1:]\n"
                + "        out += '[' + body + ']'\n"
                + "        index = close + 1\n"
                + "    return out\n\n"
                + "def _basename_match(name, candidates):\n"
                + "    for candidate in candidates:\n"
                + "        if name.startswith('.') and not candidate.startswith('.'):\n"
                + "            continue\n"
                + "        if fnmatch.fnmatchcase(name, candidate): return True\n"
                + "    return False\n\n"
                + "_parts_cache = {}\n"
                + "def _parts_match(rel_parts, pat_parts):\n"
                + "    def _match_from(ri, pi):\n"
                + "        key = (ri, pi)\n"
                + "        if key in _parts_cache: return _parts_cache[key]\n"
                + "        def _compute():\n"
                + "            while pi < len(pat_parts):\n"
                + "                if pat_parts[pi] == '**':\n"
                + "                    pi2 = pi\n"
                + "                    while pi2 < len(pat_parts) and pat_parts[pi2] == '**': pi2 += 1\n"
                + "                    if pi2 == len(pat_parts):\n"
                + "                        return ri < len(rel_parts) and all(not p.startswith('.') for p in rel_parts[ri:])\n"
                + "                    ri2 = ri\n"
                + "                    while ri2 <= len(rel_parts):\n"
                + "                        if _match_from(ri2, pi2): return True\n"
                + "                        if ri2 == len(rel_parts): break\n"
                + "                        if rel_parts[ri2].startswith('.'): return False\n"
                + "                        ri2 += 1\n"
                + "                    return False\n"
                + "                if ri == len(rel_parts): return False\n"
                + "                if not _basename_match(rel_parts[ri], [pat_parts[pi]]): return False\n"
                + "                ri += 1; pi += 1\n"
                + "            return ri == len(rel_parts)\n"
                + "        r = _compute()\n"
                + "        _parts_cache[key] = r\n"
                + "        return r\n"
                + "    return _match_from(0, 0)\n\n"
                + "def _path_match(rel, candidates):\n"
                + "    rel_parts = rel.split('/')\n"
                + "    for pat in candidates:\n"
                + "        norm = _normalize_classes(pat)\n"
                + "        if norm.startswith('**/'):\n"
                + "            if _parts_match(rel_parts, norm[3:].split('/')): return True\n"
                + "        else:\n"
                + "            if _parts_match(rel_parts, norm.split('/')): return True\n"
                + "    return False\n\n"
                + "results = []\n"
                + "truncated = False\n"
                + "deadline = time.monotonic() + TIME_BUDGET\n"
                + "try:\n"
                + "    for root, _dirs, files in os.walk(path):\n"
                + "        if time.monotonic() > deadline:\n"
                + "            truncated = True; break\n"
                + "        for name in files:\n"
                + "            fp = os.path.join(root, name)\n"
                + "            rel = os.path.relpath(fp, path)\n"
                + "            for pat in _brace_expand(pattern) or []:\n"
                + "                if _path_match(rel, [pat]):\n"
                + "                    if len(results) >= MAX_MATCHES:\n"
                + "                        truncated = True; break\n"
                + "                    is_dir = os.path.isdir(fp)\n"
                + "                    print(json.dumps({'path': rel, 'is_dir': is_dir}))\n"
                + "                    results.append((rel, is_dir))\n"
                + "            if truncated: break\n"
                + "        if truncated: break\n"
                + "except OSError as err:\n"
                + "    print(json.dumps({'error': str(err)}))\n"
                + "\"";
    }

    static GlobResult parseGlobOutput(ExecuteResponse result, String searchPath) {
        String output = result.output().strip();
        if (output.isEmpty()) {
            return GlobResult.of(List.of());
        }
        List<FileInfo> fileInfos = new ArrayList<>();
        boolean truncated = result.truncated();
        String[] lines = output.split("\n");
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            if (line.strip().isEmpty()) continue;
            Object parsed;
            try {
                parsed = MiniJson.parse(line);
            } catch (Exception e) {
                if (!(truncated && idx == lines.length - 1)) {
                    String preview = line.substring(0, Math.min(200, line.length()));
                    return GlobResult.error("Path '" + searchPath
                            + "': glob helper emitted unexpected output: " + preview);
                }
                continue;
            }
            if (!(parsed instanceof Map<?, ?> map)) continue;
            Object warning = map.get("warning");
            if (warning != null) {
                truncated = true;
                continue;
            }
            Object err = map.get("error");
            if (err instanceof String) {
                return GlobResult.error("Path '" + searchPath + "': " + err);
            }
            Object p = map.get("path");
            Object d = map.get("is_dir");
            if (p instanceof String) {
                String rel = (String) p;
                String abs = rel.startsWith("/") ? rel : searchPath.replaceAll("/$", "") + "/" + rel;
                fileInfos.add(FileInfo.file(abs));
            }
        }
        return new GlobResult(Optional.empty(), Optional.of(fileInfos), truncated);
    }

    // -----------------------------------------------------------------
    //  edit
    // -----------------------------------------------------------------

    static String buildEditInlineCmd(String filePath, String oldString, String newString, boolean replaceAll) {
        Map<String, Object> payload = Map.of(
                "path", filePath,
                "old", oldString,
                "new", newString,
                "replace_all", replaceAll);
        String json = writeJsonString(payload);
        String payloadB64 = b64(json);
        return "python3 -c \"\n"
                + "import os, sys, json, base64\n"
                + "p = json.loads(base64.b64decode('" + payloadB64 + "').decode('utf-8'))\n"
                + "fp, old, new, ra = p['path'], p['old'], p['new'], p['replace_all']\n"
                + "if not os.path.isfile(fp):\n"
                + "    print(json.dumps({'error': 'file_not_found'})); sys.exit(0)\n"
                + "try:\n"
                + "    with open(fp, 'r', encoding='utf-8', errors='strict') as f: content = f.read()\n"
                + "except (OSError, UnicodeDecodeError):\n"
                + "    print(json.dumps({'error': 'permission_denied'})); sys.exit(0)\n"
                + "occ = content.count(old)\n"
                + "if occ == 0:\n"
                + "    old_lf = old.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')\n"
                + "    new_lf = new.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')\n"
                + "    content_lf = content.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')\n"
                + "    occ = content_lf.count(old_lf)\n"
                + "    if occ == 0:\n"
                + "        print(json.dumps({'error': 'string_not_found'})); sys.exit(0)\n"
                + "    if not ra and occ > 1:\n"
                + "        print(json.dumps({'error': 'multiple_occurrences'})); sys.exit(0)\n"
                + "    updated = content_lf.replace(old_lf, new_lf) if ra else content_lf.replace(old_lf, new_lf, 1)\n"
                + "else:\n"
                + "    if not ra and occ > 1:\n"
                + "        print(json.dumps({'error': 'multiple_occurrences'})); sys.exit(0)\n"
                + "    updated = content.replace(old, new) if ra else content.replace(old, new, 1)\n"
                + "try:\n"
                + "    with open(fp, 'w', encoding='utf-8') as f: f.write(updated)\n"
                + "except OSError:\n"
                + "    print(json.dumps({'error': 'permission_denied'})); sys.exit(0)\n"
                + "print(json.dumps({'count': occ}))\n\"";
    }

    static EditResult parseEditOutput(String output, String filePath, String oldString) {
        return parseEditOutput(output, filePath, oldString, null);
    }

    static EditResult parseEditOutput(String output, String filePath, String oldString,
                                     Runnable cleanup) {
        String trimmed = output.replaceAll("\\s+$", "");
        Object parsed;
        try {
            parsed = MiniJson.parse(trimmed);
        } catch (Exception e) {
            if (cleanup != null) cleanup.run();
            String detail = trimmed.isEmpty() ? "(empty)" : trimmed.substring(0, Math.min(200, trimmed.length()));
            return EditResult.failure("Error editing file '" + filePath + "': unexpected server response: " + detail);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            if (cleanup != null) cleanup.run();
            String detail = trimmed.isEmpty() ? "(empty)" : trimmed.substring(0, Math.min(200, trimmed.length()));
            return EditResult.failure("Error editing file '" + filePath + "': unexpected server response: " + detail);
        }
        Object err = map.get("error");
        if (err instanceof String code) {
            if (cleanup != null) cleanup.run();
            return mapEditError(code, filePath, oldString);
        }
        Object count = map.get("count");
        int occurrences = (count instanceof Number n) ? n.intValue() : 1;
        return EditResult.success(filePath, occurrences);
    }

    static EditResult mapEditError(String error, String filePath, String oldString) {
        String msg;
        switch (error) {
            case "file_not_found":        msg = "Error: File '" + filePath + "' not found"; break;
            case "permission_denied":     msg = "Error: Permission denied editing file '" + filePath + "'"; break;
            case "not_a_file":            msg = "Error: '" + filePath + "' is not a regular file"; break;
            case "not_a_text_file":       msg = "Error: File '" + filePath + "' is not a text file"; break;
            case "string_not_found":      msg = "Error: String not found in file: '" + oldString + "'"; break;
            case "multiple_occurrences":  msg = "Error: String '" + oldString + "' appears multiple times. Use replace_all=True to replace all occurrences."; break;
            default:                       msg = "Error editing file '" + filePath + "': " + error;
        }
        return EditResult.failure(msg);
    }

    static String buildEditTmpfileCmd(String filePath, String oldTmp, String newTmp, boolean replaceAll) {
        return "python3 -c \"\n"
                + "import os, sys, json\n"
                + "old_path = '" + oldTmp + "'\n"
                + "new_path = '" + newTmp + "'\n"
                + "target = '" + filePath + "'\n"
                + "ra = " + (replaceAll ? "True" : "False") + "\n"
                + "try:\n"
                + "    with open(old_path, 'r', encoding='utf-8', errors='strict') as f: old = f.read()\n"
                + "    with open(new_path, 'r', encoding='utf-8', errors='strict') as f: new = f.read()\n"
                + "except (OSError, UnicodeDecodeError):\n"
                + "    print(json.dumps({'error': 'permission_denied'})); sys.exit(0)\n"
                + "if not os.path.isfile(target):\n"
                + "    print(json.dumps({'error': 'file_not_found'})); sys.exit(0)\n"
                + "with open(target, 'r', encoding='utf-8', errors='strict') as f: content = f.read()\n"
                + "occ = content.count(old)\n"
                + "if occ == 0:\n"
                + "    old_lf = old.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')\n"
                + "    new_lf = new.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')\n"
                + "    content_lf = content.replace('\\\\r\\\\n', '\\\\n').replace('\\\\r', '\\\\n')\n"
                + "    occ = content_lf.count(old_lf)\n"
                + "    if occ == 0:\n"
                + "        print(json.dumps({'error': 'string_not_found'})); sys.exit(0)\n"
                + "    if not ra and occ > 1:\n"
                + "        print(json.dumps({'error': 'multiple_occurrences'})); sys.exit(0)\n"
                + "    updated = content_lf.replace(old_lf, new_lf) if ra else content_lf.replace(old_lf, new_lf, 1)\n"
                + "else:\n"
                + "    if not ra and occ > 1:\n"
                + "        print(json.dumps({'error': 'multiple_occurrences'})); sys.exit(0)\n"
                + "    updated = content.replace(old, new) if ra else content.replace(old, new, 1)\n"
                + "try:\n"
                + "    with open(target, 'w', encoding='utf-8') as f: f.write(updated)\n"
                + "except OSError:\n"
                + "    print(json.dumps({'error': 'permission_denied'})); sys.exit(0)\n"
                + "finally:\n"
                + "    try: os.remove(old_path)\n"
                + "    except OSError: pass\n"
                + "    try: os.remove(new_path)\n"
                + "    except OSError: pass\n"
                + "print(json.dumps({'count': occ}))\n\"";
    }

    // -----------------------------------------------------------------
    //  execute capture / offload
    // -----------------------------------------------------------------

    static String buildCaptureExecuteCmd(String command, String capturePath,
                                        int inlineBudget, int maxCaptureBytes) {
        String delim = newHeredocDelim();
        while (command.contains(delim)) {
            delim = newHeredocDelim();
        }
        return EXECUTE_CAPTURE_CMD_TEMPLATE
                .replace("__PATH_Q__", shQuote(capturePath))
                .replace("__DELIM__", delim)
                .replace("__MAXBYTES__", String.valueOf(maxCaptureBytes))
                .replace("__BUDGET__", String.valueOf(inlineBudget))
                .replace("__SENTINEL__", BaseSandbox._EXECUTE_CAPTURE_SENTINEL)
                .replace("__HEADLINES__", String.valueOf(BaseSandbox._EXECUTE_CAPTURE_HEAD_LINES))
                .replace("__TAILLINES__", String.valueOf(BaseSandbox._EXECUTE_CAPTURE_TAIL_LINES))
                .replace("__HEAD__", String.valueOf(BaseSandbox._EXECUTE_CAPTURE_HEAD_BYTES))
                .replace("__TAIL__", String.valueOf(BaseSandbox._EXECUTE_CAPTURE_TAIL_BYTES))
                .replace("__COMMAND__", command);
    }

    static ExecuteOffloadResult parseCaptureExecuteOutput(String output, boolean backendTruncated) {
        int nl = output.indexOf('\n');
        String first = nl < 0 ? output : output.substring(0, nl);
        String body = nl < 0 ? "" : output.substring(nl + 1);
        String[] parts = first.split(" ");
        if (parts.length != 4 || !BaseSandbox._EXECUTE_CAPTURE_SENTINEL.equals(parts[0])) {
            return new ExecuteOffloadResult(false,
                    new ExecuteResponse(output, Optional.empty(), backendTruncated));
        }
        int exitCode;
        try { exitCode = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) {
            return new ExecuteOffloadResult(false,
                    new ExecuteResponse(output, Optional.empty(), backendTruncated));
        }
        boolean offloaded = "1".equals(parts[2]);
        boolean capped = "1".equals(parts[3]) || backendTruncated;
        return new ExecuteOffloadResult(offloaded,
                new ExecuteResponse(body, Optional.of(exitCode), capped));
    }

    private static String newHeredocDelim() {
        byte[] buf = new byte[10];
        new Random().nextBytes(buf);
        return "__DEEPAGENTS_CMD_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf) + "__";
    }

    // -----------------------------------------------------------------
    //  helpers
    // -----------------------------------------------------------------

    /** Minimal JSON object writer for the small payloads we need. */
    @SuppressWarnings("unchecked")
    private static String writeJsonString(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJsonValue(sb, o);
        return sb.toString();
    }

    private static void writeJsonValue(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (o instanceof Number n) { sb.append(n); return; }
        if (o instanceof String s) { writeJsonString(sb, s); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                writeJsonString(sb, String.valueOf(e.getKey()));
                sb.append(":");
                writeJsonValue(sb, e.getValue());
            }
            sb.append("}");
            return;
        }
        if (o instanceof List<?> l) {
            sb.append("[");
            boolean first = true;
            for (Object e : l) {
                if (!first) sb.append(",");
                first = false;
                writeJsonValue(sb, e);
            }
            sb.append("]");
            return;
        }
        writeJsonString(sb, String.valueOf(o));
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
