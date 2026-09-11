/**
 * T-401 extension: user font config.
 *
 *  - missing file → returns DEFAULT_FONT
 *  - partial file → fills missing keys from DEFAULT_FONT
 *  - bad fields → InvalidFontConfigError
 *  - write → round-trip through read
 *  - the file path defaults to <home>/.aethercode/font.yaml
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  loadFontConfig,
  writeFontConfig,
  coerceFont,
  defaultFontConfigPath,
  InvalidFontConfigError,
  DEFAULT_FONT,
} from '../index.js';

let tmp: string;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-fontcfg-'));
});

afterEach(async () => {
  await fs.rm(tmp, { recursive: true, force: true });
});

describe('aethercode-themes — font config (T-401-ext)', () => {
  it('default path is <home>/.aethercode/font.yaml', () => {
    expect(defaultFontConfigPath('/h')).toBe(path.join('/h', '.aethercode', 'font.yaml'));
  });

  it('loadFontConfig returns DEFAULT_FONT when the file is missing', async () => {
    const ghost = path.join(tmp, 'does-not-exist.yaml');
    const font = await loadFontConfig(ghost);
    expect(font).toEqual(DEFAULT_FONT);
  });

  it('coerceFont fills missing keys from DEFAULT_FONT', () => {
    const got = coerceFont({ family: 'Fira Code' });
    expect(got.family).toBe('Fira Code');
    expect(got.size).toBe(DEFAULT_FONT.size);
    expect(got.lineHeight).toBe(DEFAULT_FONT.lineHeight);
    expect(got.ligatures).toBe(DEFAULT_FONT.ligatures);
  });

  it('coerceFont honours every key when present', () => {
    const got = coerceFont({
      family: 'Iosevka',
      size: 16,
      lineHeight: 1.6,
      ligatures: false,
    });
    expect(got).toEqual({
      family: 'Iosevka',
      size: 16,
      lineHeight: 1.6,
      ligatures: false,
    });
  });

  it('coerceFont rejects non-string family', () => {
    expect(() => coerceFont({ family: 42 })).toThrow(InvalidFontConfigError);
  });

  it('coerceFont rejects non-positive size', () => {
    expect(() => coerceFont({ size: 0 })).toThrow(InvalidFontConfigError);
    expect(() => coerceFont({ size: -3 })).toThrow(InvalidFontConfigError);
    expect(() => coerceFont({ size: Number.POSITIVE_INFINITY })).toThrow(InvalidFontConfigError);
  });

  it('coerceFont rejects non-boolean ligatures', () => {
    expect(() => coerceFont({ ligatures: 'yes' })).toThrow(InvalidFontConfigError);
  });

  it('coerceFont rejects a non-mapping top-level', () => {
    expect(() => coerceFont(null)).toThrow(InvalidFontConfigError);
    expect(() => coerceFont(42)).toThrow(InvalidFontConfigError);
  });

  it('writeFontConfig → loadFontConfig round-trips', async () => {
    const target = path.join(tmp, 'font.yaml');
    const custom = {
      family: 'Cascadia Code',
      size: 15,
      lineHeight: 1.5,
      ligatures: false,
    };
    await writeFontConfig(custom, target);
    const read = await loadFontConfig(target);
    expect(read).toEqual(custom);
  });

  it('loadFontConfig surfaces YAML parse errors as InvalidFontConfigError', async () => {
    const target = path.join(tmp, 'broken.yaml');
    // Unbalanced brace is invalid YAML.
    await fs.writeFile(target, 'family: "oops\n  :: ][', 'utf8');
    await expect(loadFontConfig(target)).rejects.toBeInstanceOf(InvalidFontConfigError);
  });
});
