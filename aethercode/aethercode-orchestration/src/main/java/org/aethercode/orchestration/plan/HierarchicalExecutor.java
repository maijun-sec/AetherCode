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

package org.aethercode.orchestration.plan;

import org.aethercode.orchestration.plan.GlobalPlan.Skill;
import org.aethercode.orchestration.plan.GlobalPlan.Step;

import java.util.Objects;
import java.util.function.Function;

/**
 * GoalAct-style hierarchical executor: routes a {@link GlobalPlan.Step} to the
 * correct executor by {@link Skill}.
 * <p>
 * Paper: 2504.16563 GoalAct. Each skill has its own action space (e.g. text/json
 * for searching, Python code for coding). This class provides the dispatcher.
 */
public final class HierarchicalExecutor {

    /** Strategy interface for skill-specific execution. */
    @FunctionalInterface
    public interface SkillStrategy {
        /** Execute the step and return the result as a string. */
        String execute(Step step);
    }

    private final SkillStrategy searching;
    private final SkillStrategy coding;
    private final SkillStrategy writing;
    private final SkillStrategy reasoning;
    private final SkillStrategy research;
    private final Function<Step, String> finish;

    private HierarchicalExecutor(Builder b) {
        this.searching = Objects.requireNonNull(b.searching, "searching");
        this.coding = Objects.requireNonNull(b.coding, "coding");
        this.writing = Objects.requireNonNull(b.writing, "writing");
        this.reasoning = Objects.requireNonNull(b.reasoning, "reasoning");
        this.research = Objects.requireNonNull(b.research, "research");
        this.finish = b.finish != null ? b.finish : step -> "OK";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Execute one step, dispatching by skill. */
    public String execute(Step step) {
        Objects.requireNonNull(step, "step");
        return switch (step.skill()) {
            case SEARCHING -> searching.execute(step);
            case CODING -> coding.execute(step);
            case WRITING -> writing.execute(step);
            case REASONING -> reasoning.execute(step);
            case RESEARCH -> research.execute(step);
            case FINISH -> finish.apply(step);
        };
    }

    /** Execute the whole plan in order. Returns concatenated results. */
    public String executeAll(GlobalPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Step step : plan.steps()) {
            sb.append("[").append(step.skill().name()).append("] ");
            sb.append(execute(step));
            sb.append("\n");
        }
        return sb.toString();
    }

    public static final class Builder {
        private SkillStrategy searching = step -> "search:" + step.description();
        private SkillStrategy coding = step -> "code:" + step.description();
        private SkillStrategy writing = step -> "write:" + step.description();
        private SkillStrategy reasoning = step -> "reason:" + step.description();
        private SkillStrategy research = step -> "research:" + step.description();
        private Function<Step, String> finish;

        public Builder searching(SkillStrategy s) { this.searching = s; return this; }
        public Builder coding(SkillStrategy s) { this.coding = s; return this; }
        public Builder writing(SkillStrategy s) { this.writing = s; return this; }
        public Builder reasoning(SkillStrategy s) { this.reasoning = s; return this; }
        public Builder research(SkillStrategy s) { this.research = s; return this; }
        public Builder finish(Function<Step, String> f) { this.finish = f; return this; }

        public HierarchicalExecutor build() {
            return new HierarchicalExecutor(this);
        }
    }
}
