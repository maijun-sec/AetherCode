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

package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.TeamGeometryComposer.Agent;
import org.aethercode.orchestration.multiagent.TeamGeometryComposer.SkillVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamGeometryComposerTest {

    private static Agent a(String name, String... pairs) {
        Map<String, Double> m = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], Double.parseDouble(pairs[i + 1]));
        }
        return new Agent(name, new SkillVector(m));
    }

    @Test
    void singleAgentTeamHasZeroDistance() {
        var comp = new TeamGeometryComposer(0.5);
        var team = comp.compose(List.of(a("solo", "x", "1.0")), 1);
        assertEquals(0.0, team.meanPairwiseW2(), 1e-9);
    }

    @Test
    void twoIdenticalAgentsHaveZeroDistance() {
        var comp = new TeamGeometryComposer(0.5);
        var team = comp.compose(List.of(
            a("a", "x", "1.0"),
            a("b", "x", "1.0")
        ), 2);
        assertEquals(0.0, team.meanPairwiseW2(), 1e-9);
    }

    @Test
    void diverseTeamHasPositiveDistance() {
        var comp = new TeamGeometryComposer(0.5);
        var team = comp.compose(List.of(
            a("a", "x", "1.0"),
            a("b", "y", "1.0")
        ), 2);
        assertTrue(team.meanPairwiseW2() > 0.5);
    }

    @Test
    void synergyPeaksAtTargetRadius() {
        var comp = new TeamGeometryComposer(1.0);
        var team = comp.compose(List.of(
            a("a", "x", "1.0", "y", "0.5"),
            a("b", "x", "0.0", "y", "1.5")
        ), 2);
        // distance is sqrt(1 + 1) = sqrt(2) ≈ 1.41 — close to 1.0 target
        assertTrue(team.synergyScore() < 0, "synergy should be negative when off-target");
    }

    @Test
    void returnsAllAgentsWhenTeamSizeExceedsPool() {
        var comp = new TeamGeometryComposer(0.5);
        var team = comp.compose(List.of(a("a", "x", "1.0")), 5);
        assertEquals(1, team.members().size());
    }
}
