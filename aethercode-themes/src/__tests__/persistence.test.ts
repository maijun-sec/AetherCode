// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/**
 * T-408: theme.json atomic persistence.
 *
 *  - readActiveThemeFile returns null when the file is missing
 *  - readActiveThemeFile returns the parsed payload when valid
 *  - readActiveThemeFile throws ActiveThemeFileError on invalid JSON
 *  - writeActiveThemeFile creates the directory if missing
 *  - writeActiveThemeFile then readActiveThemeFile round-trips
 *  - the file is written atomically via tmp+rename (the tmp file
 *    does not exist on disk after a successful write)
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  readActiveThemeFile,
  writeActiveThemeFile,
  ActiveThemeFileError,
} from '../index.js';
import type { ActiveThemeFile } from '../types.js';

let tmp: string;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-persist-'));
});

afterEach(async () => {
  await fs.rm(tmp, { recursive: true, force: true });
});

describe('aethercode-themes — persistence (T-408)', () => {
  it('readActiveThemeFile returns null when the file is missing', async () => {
    const got = await readActiveThemeFile({ filePath: path.join(tmp, 'theme.json') });
    expect(got).toBeNull();
  });

  it('writeActiveThemeFile creates the parent dir if missing', async () => {
    const target = path.join(tmp, 'nested', 'deeper', 'theme.json');
    const payload: ActiveThemeFile = { schemaVersion: 1, active: 'dark' };
    await writeActiveThemeFile(payload, { filePath: target });
    const got = await readActiveThemeFile({ filePath: target });
    expect(got).toEqual(payload);
  });

  it('write → read round-trips the payload', async () => {
    const target = path.join(tmp, 'theme.json');
    const payload: ActiveThemeFile = { schemaVersion: 1, active: 'solarized-light' };
    await writeActiveThemeFile(payload, { filePath: target });
    const got = await readActiveThemeFile({ filePath: target });
    expect(got).toEqual(payload);
  });

  it('writeActiveThemeFile does not leave a .tmp file behind', async () => {
    const target = path.join(tmp, 'theme.json');
    await writeActiveThemeFile(
      { schemaVersion: 1, active: 'light' },
      { filePath: target },
    );
    await expect(fs.access(`${target}.tmp`)).rejects.toMatchObject({ code: 'ENOENT' });
  });

  it('writeActiveThemeFile overwrites an existing file', async () => {
    const target = path.join(tmp, 'theme.json');
    await writeActiveThemeFile(
      { schemaVersion: 1, active: 'dark' },
      { filePath: target },
    );
    await writeActiveThemeFile(
      { schemaVersion: 1, active: 'light' },
      { filePath: target },
    );
    const got = await readActiveThemeFile({ filePath: target });
    expect(got?.active).toBe('light');
  });

  it('readActiveThemeFile throws on invalid JSON', async () => {
    const target = path.join(tmp, 'theme.json');
    await fs.writeFile(target, '{ not json', 'utf8');
    await expect(readActiveThemeFile({ filePath: target })).rejects.toBeInstanceOf(
      ActiveThemeFileError,
    );
  });

  it('readActiveThemeFile throws on the wrong schema', async () => {
    const target = path.join(tmp, 'theme.json');
    await fs.writeFile(target, JSON.stringify({ schemaVersion: 2, active: 'dark' }), 'utf8');
    await expect(readActiveThemeFile({ filePath: target })).rejects.toBeInstanceOf(
      ActiveThemeFileError,
    );
  });

  it('readActiveThemeFile throws on missing active', async () => {
    const target = path.join(tmp, 'theme.json');
    await fs.writeFile(target, JSON.stringify({ schemaVersion: 1 }), 'utf8');
    await expect(readActiveThemeFile({ filePath: target })).rejects.toBeInstanceOf(
      ActiveThemeFileError,
    );
  });

  it('readActiveThemeFile throws on empty active', async () => {
    const target = path.join(tmp, 'theme.json');
    await fs.writeFile(target, JSON.stringify({ schemaVersion: 1, active: '' }), 'utf8');
    await expect(readActiveThemeFile({ filePath: target })).rejects.toBeInstanceOf(
      ActiveThemeFileError,
    );
  });
});
