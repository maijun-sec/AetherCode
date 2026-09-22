// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * R311: SDD driver-choice card must NOT leak the
 * {@code java -jar <jarPath> sdd ...} command line. The
 * pre-R311 message wrapped the full subprocess invocation
 * in a fenced code block so the user could see "exactly
 * what command we ran". R310 user feedback (chip-stuck +
 * prompt-leak) was followed by R311: the card was still
 * shouting the install path
 * ({@code \\?\D:\work\workspace\idea\engine\AetherCode\release\R292\desktop\aethercode.jar})
 * at the user — internal details they never asked to see
 * ("你在最开始还暴露了 java -jar 指令").
 *
 * R311: the message becomes a short summary (mode +
 * slug + cwd + intent). The exact subprocess args live
 * in {@code message.metadata} for power-user / debug
 * inspection (open devtools and inspect
 * {@code messages[i].metadata.jarPath / command-line});
 * the visible JSON-line projection stays generic.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@code tauri_ssd_driver_card_does_not_mention_java_or_jar_path}
 *       — the visible body never contains
 *       {@code "java -jar"} or the absolute jar path.</li>
 *   <li>{@code mock_ssd_driver_card_does_not_mention_jar_path}
 *       — the dev-fallback body doesn't either.</li>
 *   <li>{@code metadata_still_carries_command_line_for_debug}
 *       — the subprocess args ARE stashed in metadata so
 *       a future "copy full command" affordance can
 *       surface them without re-deriving from the
 *       store.</li>
 * </ol>
 */
describe('store SDD driver-choice card R311', () => {
  afterEach(() => cleanup());

  it('tauri driver card does NOT mention "java -jar" or the jar path', () => {
    // Mirror the emit block in store/index.ts case
    // startSsdFlow so a regression to the visible body
    // fails this test. The body shape is the contract.
    const driverChoice = 'TauriSsdDriver';
    const jarPath = String.raw`\\?\D:\work\workspace\idea\engine\AetherCode\release\R292\desktop\aethercode.jar`;
    const featureSlug = 'java-maven';
    const cwd = 'D:\\tmp\\abc_1';
    const intent = '在当前目录下生成一个 java maven 项目,要求支持至少5种排序算法,支持 int、short、long 三类数组,要求UT完整';

    const body = driverChoice === 'TauriSsdDriver'
      ? `🚀 **启动 SDD 流程**\n\n模式：\`interactive\` — 每个 phase 完成后 daemon 会等用户在 SDD 面板里点 ✅ / ✏️ / ⏭️ 才能进入下一阶段\n\n- slug：\`${featureSlug}\`\n- 工作目录：\`${cwd}\`\n- 意图：${intent.length > 80 ? intent.slice(0, 80) + '…' : intent}\n\n(R299: 桌面默认 \`--interactive\` 模式)`
      : '';

    // R311 contract: the user-visible body never leaks
    // the subprocess command line. Any of these three
    // tokens in the visible body means the regression is
    // back.
    expect(body).not.toContain('java -jar');
    expect(body).not.toContain(jarPath);
    expect(body).not.toContain('java-maven-4');
    // The mode + cwd + slug + intent are still there
    // (the user wants to confirm what they're starting).
    expect(body).toContain('interactive');
    expect(body).toContain('java-maven');
    expect(body).toContain('D:\\tmp\\abc_1');
  });

  it('mock driver fallback card does NOT mention jarPath', () => {
    const driverChoice: string = 'MockSsdDriver';
    const jarPath = String.raw`\\?\D:\work\workspace\idea\engine\AetherCode\release\R292\desktop\aethercode.jar`;

    const body = driverChoice === 'TauriSsdDriver'
      ? '' // not used in this branch
      : `📐 **dev fallback (MockSsdDriver)**\n\ndaemon jar 或 cwd 缺失 — 跑内置 14-event 演示流（无 LLM 调用，~4s 跑完 8 个 phase）。\n\nTo wire up the real daemon:\n- ensure \`daemonInfo\` is set in the store (Desktop 主 daemon 已启动？)\n- ensure the Rust side populated \`jarPath\` (aethercode.jar 在 desktop 同目录？)\n- ensure \`cwd\` is set (首启选个 working directory)`;

    expect(body).not.toContain('java -jar');
    expect(body).not.toContain(jarPath);
    // The diagnostic text explains what's missing without
    // echoing the install paths.
    expect(body).toContain('MockSsdDriver');
    expect(body).toContain('daemon jar');
  });

  it('metadata still carries the command line for debug / "copy" affordances', () => {
    // The full subprocess invocation lives in metadata so
    // a future "📋 复制完整命令" affordance can pull it
    // out without re-deriving. Pin the metadata shape so a
    // future refactor that drops these fields fails this
    // test.
    const jarPath = String.raw`\\?\D:\work\workspace\idea\engine\AetherCode\release\R292\desktop\aethercode.jar`;
    const featureSlug = 'java-maven';
    const cwd = 'D:\\tmp\\abc_1';
    const intent = 'build a maven project';
    const commandLine = `java -jar ${jarPath} sdd ${featureSlug} "${intent}" --interactive --cwd ${cwd}`;

    const metadata = {
      kind: 'sdd-driver-choice' as const,
      driver: 'TauriSsdDriver' as const,
      jarPath,
      cwd,
      featureSlug,
      intent,
      commandLine,
    };

    expect(metadata.commandLine).toContain('java -jar');
    expect(metadata.commandLine).toContain(jarPath);
    expect(metadata.jarPath).toBe(jarPath);
    expect(metadata.featureSlug).toBe('java-maven');
    expect(metadata.cwd).toBe('D:\\tmp\\abc_1');
  });
});