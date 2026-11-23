/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R391 REPLAY test — the real plan evolution of the session the user
 * reported, fed through the engine and through a miniature of the
 * desktop's card reducer.
 *
 * <p>The data is the ACTUAL sequence of {@code todo_write} calls the
 * model made in session
 * {@code 26ee7d8d-f07b-4e11-97ea-a7dc13020794} (a 20-minute
 * "build a java maven project" run): the plan went 7 items → 5 items
 * → 6 items → 6 items, with the wording rewritten along the way.
 *
 * <p>Pre-R391 the user saw seven title-only cards whose titles were
 * all the FIRST wording, plus a {@code 结果 skipped} footer on the
 * last one. This test pins the POST-fix shape of the same data, so the
 * fix is checked against the report rather than against a toy.
 *
 * <p>The reducer below is a miniature of the desktop store's rules:
 * <ul>
 *   <li>{@code SubTaskStart} → create the card, or update an existing
 *       card's title AND status;</li>
 *   <li>{@code SubTaskEnd} with the "removed from todo list" summary
 *       → DROP the card;</li>
 *   <li>{@code SubTaskEnd} otherwise → set status + summary.</li>
 * </ul>
 */
class QueryEngineSubTaskR391ReplayTest {

    private AppState state;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        state = new AppState("sess", Path.of("/tmp"));
        ChatClient stub = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return Stream.empty();
            }
            @Override public String modelId() { return "stub"; }
        };
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        org.aethercode.core.engine.StreamingToolExecutor noopExec =
                new org.aethercode.core.engine.StreamingToolExecutor(allowAll, 1) {
                    @Override
                    public java.util.stream.Stream<Event> run(
                            List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                        List<Event> events = new ArrayList<>();
                        events.add(new Event.BatchEnd());
                        return events.stream();
                    }
                };
        engine = new QueryEngine(state, stub, allowAll, "", null, noopExec, null, null);
    }

    private static Map<String, Object> flat(String content, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", content);
        m.put("status", status);
        return m;
    }

    /** One card as the desktop renders it. */
    private record Card(String id, String title, String status, String summary) {}

    /** A miniature of the desktop store's sub-task reducer. */
    private static List<Card> renderCards(List<StreamEvent> events) {
        List<Card> cards = new ArrayList<>();
        for (StreamEvent e : events) {
            if (e instanceof StreamEvent.SubTaskStart s) {
                String id = s.taskId() + ":" + s.subTaskId();
                int idx = -1;
                for (int i = 0; i < cards.size(); i++) {
                    if (cards.get(i).id().equals(id)) { idx = i; break; }
                }
                if (idx < 0) {
                    cards.add(new Card(id, s.content(), s.status(), null));
                } else {
                    Card c = cards.get(idx);
                    cards.set(idx, new Card(c.id(), s.content(), s.status(), c.summary()));
                }
            } else if (e instanceof StreamEvent.SubTaskEnd en) {
                String id = en.taskId() + ":" + en.subTaskId();
                if ("sub-task removed from todo list".equals(en.summary())) {
                    cards.removeIf(c -> c.id().equals(id));
                    continue;
                }
                for (int i = 0; i < cards.size(); i++) {
                    if (cards.get(i).id().equals(id)) {
                        cards.set(i, new Card(cards.get(i).id(), cards.get(i).title(), en.status(), en.summary()));
                    }
                }
            }
        }
        return cards;
    }

    @Test
    void realPlanEvolution_rendersSixCurrentCardsWithNoSkippedGhost() {
        List<StreamEvent> events = new ArrayList<>();

        // --- plan write #1: seven items, the first one in progress ---
        state.setTodoList(List.of(
                flat("搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）", "in_progress"),
                flat("实现核心抽象层（PrimArray 适配器 + SortAlgorithm/SortOrder API）", "pending"),
                flat("实现 9 种排序算法（int/short/long 全支持）", "pending"),
                flat("实现 Sorters 注册表/门面", "pending"),
                flat("编写完整 UT（契约/边界/极值/注册表/性能冒烟）", "pending"),
                flat("运行 mvn test 直到全绿并复核 diff", "pending"),
                flat("补 README（中文）说明算法清单与用法", "pending")));
        engine.emitSubTaskTransitions(events::add);

        // --- plan write #2: seven → FIVE items (two merged), wording rewritten ---
        state.setTodoList(List.of(
                flat("搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）", "completed"),
                flat("实现 9 种排序算法 + int/short/long 三类数组适配", "completed"),
                flat("编写完整 JUnit 5 UT（契约/边界/极值/注册表/大数组）", "completed"),
                flat("跑通 mvn test 全绿并核对测试数量", "in_progress"),
                flat("补 README（用法 + 算法对比表）", "pending")));
        engine.emitSubTaskTransitions(events::add);

        // --- plan write #3: six items, a final verification item added ---
        state.setTodoList(List.of(
                flat("搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）", "completed"),
                flat("实现 9 种排序算法 + int/short/long 三类数组适配", "completed"),
                flat("编写完整 JUnit 5 UT（契约/边界/极值/注册表/大数组）", "completed"),
                flat("跑通 mvn test 全绿并核对测试数量（254 tests / 0 failures）", "completed"),
                flat("补 README（用法 + 算法对比表）", "completed"),
                flat("mvn clean test 从零全量验证 + 复核最终文件清单", "in_progress")));
        engine.emitSubTaskTransitions(events::add);

        // --- plan write #4: everything completed ---
        state.setTodoList(List.of(
                flat("搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）", "completed"),
                flat("实现 9 种排序算法 + int/short/long 三类数组适配", "completed"),
                flat("编写完整 JUnit 5 UT（契约/边界/极值/注册表/大数组）", "completed"),
                flat("跑通 mvn test 全绿并核对测试数量（254 tests / 0 failures）", "completed"),
                flat("补 README（用法 + 算法对比表）", "completed"),
                flat("mvn clean test 从零全量验证 + 复核最终文件清单", "completed")));
        engine.emitSubTaskTransitions(events::add);

        List<Card> cards = renderCards(events);

        // The plan ends with SIX items, so the chat must end with SIX
        // cards — not the seven the first write created, and not the
        // five the second write created.
        assertEquals(6, cards.size(),
                "R391: the chat must end with one card per item of the CURRENT plan "
                        + "(6), not the length of an earlier write. Cards: " + cards);

        // Every card's title must be the CURRENT wording, never the
        // first one. Pre-R391 they were all frozen at write #1.
        assertEquals(List.of(
                "搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）",
                "实现 9 种排序算法 + int/short/long 三类数组适配",
                "编写完整 JUnit 5 UT（契约/边界/极值/注册表/大数组）",
                "跑通 mvn test 全绿并核对测试数量（254 tests / 0 failures）",
                "补 README（用法 + 算法对比表）",
                "mvn clean test 从零全量验证 + 复核最终文件清单"),
                cards.stream().map(Card::title).toList(),
                "R391: every card must carry the CURRENT wording of its plan item");

        // No card may be left in a "skipped" state — that was the
        // ghost footer the user saw on the last card.
        for (Card c : cards) {
            assertNotEquals("skipped", c.status(),
                    "R391: no card may end up 'skipped' — a dropped plan item is dropped, "
                            + "not reported as a failure. Card: " + c);
            assertNull(c.summary(), "R391: no card may carry a leftover summary: " + c);
        }

        // And the last card — the one the user said the "skipped" had
        // nothing to do with — must be the final verification item.
        assertEquals("mvn clean test 从零全量验证 + 复核最终文件清单",
                cards.get(cards.size() - 1).title(),
                "R391: the last card must be the last plan item");
        assertEquals("completed", cards.get(cards.size() - 1).status());
    }

    @Test
    void realPlanEvolution_firstWriteStillProducesSevenCards() {
        List<StreamEvent> events = new ArrayList<>();
        state.setTodoList(List.of(
                flat("搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）", "in_progress"),
                flat("实现核心抽象层（PrimArray 适配器 + SortAlgorithm/SortOrder API）", "pending"),
                flat("实现 9 种排序算法（int/short/long 全支持）", "pending"),
                flat("实现 Sorters 注册表/门面", "pending"),
                flat("编写完整 UT（契约/边界/极值/注册表/性能冒烟）", "pending"),
                flat("运行 mvn test 直到全绿并复核 diff", "pending"),
                flat("补 README（中文）说明算法清单与用法", "pending")));
        engine.emitSubTaskTransitions(events::add);

        List<Card> cards = renderCards(events);
        assertEquals(7, cards.size(), "R391: the first write must open one card per item");
        assertEquals("搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）", cards.get(0).title());
        assertEquals("in_progress", cards.get(0).status());
        assertEquals("pending", cards.get(1).status());
        assertEquals("补 README（中文）说明算法清单与用法", cards.get(6).title());
    }
}
