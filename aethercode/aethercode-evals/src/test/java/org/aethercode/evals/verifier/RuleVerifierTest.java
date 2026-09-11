package org.aethercode.evals.verifier;

import org.aethercode.evals.verifier.RuleVerifier.Kind;
import org.aethercode.evals.verifier.RuleVerifier.Rule;
import org.aethercode.evals.verifier.Verifier.Severity;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RuleVerifier}.
 */
class RuleVerifierTest {

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleVerifier("", List.of(new Rule(Kind.MUST_CONTAIN, "x", "x"))));
    }

    @Test
    void constructorRejectsEmptyRules() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleVerifier("v", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleVerifier("v", null));
    }

    @Test
    void ruleRecordRejectsNullKindOrPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> new Rule(null, "x", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new Rule(Kind.MUST_CONTAIN, null, "x"));
    }

    @Test
    void ruleRecordDefaultsDescription() {
        Rule r = new Rule(Kind.MUST_CONTAIN, "hello", null);
        assertEquals("MUST_CONTAIN:hello", r.description());
    }

    /* ----------------------- MUST_CONTAIN / MUST_NOT_CONTAIN ----------------------- */

    @Test
    void mustContainPassesWhenSubstringPresent() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_CONTAIN, "alice", "name")));
        assertTrue(v.verify("hi alice").passed());
    }

    @Test
    void mustContainFailsWhenSubstringMissing() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_CONTAIN, "alice", "name")));
        VerificationResult r = v.verify("hi bob");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("alice"), r.reason());
    }

    @Test
    void mustNotContainPassesWhenSubstringAbsent() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_NOT_CONTAIN, "secret", "no secrets")));
        assertTrue(v.verify("hello world").passed());
    }

    @Test
    void mustNotContainFailsWhenSubstringPresent() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_NOT_CONTAIN, "secret", "no secrets")));
        VerificationResult r = v.verify("the secret is out");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("secret"), r.reason());
    }

    /* ----------------------- MUST_MATCH / MUST_NOT_MATCH ----------------------- */

    @Test
    void mustMatchPassesWhenRegexFinds() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_MATCH, "^[A-Z][a-z]+$", "capitalized name")));
        assertTrue(v.verify("Alice").passed());
    }

    @Test
    void mustMatchFailsWhenRegexMisses() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_MATCH, "^[A-Z][a-z]+$", "capitalized name")));
        VerificationResult r = v.verify("alice");
        assertFalse(r.passed());
    }

    @Test
    void mustNotMatchPassesWhenRegexMisses() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_NOT_MATCH, "rm -rf", "no destructive commands")));
        assertTrue(v.verify("ls -la").passed());
    }

    @Test
    void mustNotMatchFailsWhenRegexFinds() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_NOT_MATCH, "rm\\s+-rf", "no destructive commands")));
        VerificationResult r = v.verify("rm -rf /");
        assertFalse(r.passed());
    }

    /* ----------------------- multiple rules ----------------------- */

    @Test
    void allRulesMustPassForCompositePass() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_CONTAIN, "alice", "name"),
                new Rule(Kind.MUST_CONTAIN, "30", "age"),
                new Rule(Kind.MUST_NOT_CONTAIN, "secret", "no secrets")));
        assertTrue(v.verify("alice is 30 years old").passed());
    }

    @Test
    void firstFailingRuleIsReported() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_CONTAIN, "alice", "name"),
                new Rule(Kind.MUST_CONTAIN, "30", "age")));
        VerificationResult r = v.verify("alice is 25 years old");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("30"), r.reason());
    }

    /* ----------------------- severity ----------------------- */

    @Test
    void defaultSeverityIsBlock() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_CONTAIN, "x", "x")));
        VerificationResult r = v.verify("missing");
        assertEquals(Severity.BLOCK, r.severity());
    }

    @Test
    void customSeverityIsHonored() {
        RuleVerifier v = new RuleVerifier("v",
                List.of(new Rule(Kind.MUST_CONTAIN, "x", "x")),
                Severity.WARN);
        VerificationResult r = v.verify("missing");
        assertEquals(Severity.WARN, r.severity());
    }

    /* ----------------------- input validation ----------------------- */

    @Test
    void nullInputFails() {
        RuleVerifier v = new RuleVerifier("v", List.of(
                new Rule(Kind.MUST_CONTAIN, "x", "x")));
        VerificationResult r = v.verify(null);
        assertFalse(r.passed());
        assertTrue(r.reason().contains("null"), r.reason());
    }

    @Test
    void nameAndDescription() {
        RuleVerifier v = new RuleVerifier("rule-1", List.of(
                new Rule(Kind.MUST_CONTAIN, "a", "a"),
                new Rule(Kind.MUST_CONTAIN, "b", "b")));
        assertEquals("rule-1", v.name());
        assertTrue(v.description().contains("2"), v.description());
    }

    @Test
    void rulesAccessorReturnsImmutable() {
        List<Rule> rules = List.of(new Rule(Kind.MUST_CONTAIN, "x", "x"));
        RuleVerifier v = new RuleVerifier("v", rules);
        assertEquals(1, v.rules().size());
        assertEquals(rules.get(0), v.rules().get(0));
    }
}
