package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.ExceptionKind;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.ExceptionRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies timing exceptions with a fixed, documented priority.
 *
 * <p>Priority rules (see {@code docs/STA.md}):
 * <ol>
 *   <li>Declared rules always beat the implicit single-cycle default.</li>
 *   <li>{@code false_path} beats every multicycle rule that matches the same path.</li>
 *   <li>Among rules of the same kind, the more specific pattern wins; specificity is the
 *       number of fixed clauses: {@code through} pins (in order) &gt; {@code from}+{@code to}
 *       &gt; {@code from} or {@code to} alone.</li>
 *   <li>If two rules of the same kind have equal specificity but demand different effects,
 *       analysis fails with {@code CONSTRAINT_CONFLICT}. Declaration order never breaks
 *       ties, so reordering declarations cannot change the result.</li>
 * </ol>
 */
final class ExceptionResolver {

    static final class Decision {
        boolean falsePath;
        int setupCycles = 1;
        int holdCycles = 0;
        ExceptionRule setupRule;
        ExceptionRule holdRule;
        ExceptionRule falsePathRule;
    }

    private ExceptionResolver() {
    }

    static boolean matches(ExceptionRule rule, String start, String end, List<String> path) {
        if (rule.from() != null && !rule.from().equals(start)) {
            return false;
        }
        if (rule.to() != null && !rule.to().equals(end)) {
            return false;
        }
        if (rule.through() != null && !containsInOrder(path, rule.through())) {
            return false;
        }
        return true;
    }

    private static boolean containsInOrder(List<String> path, List<String> through) {
        int cursor = 0;
        for (String node : path) {
            if (cursor < through.size() && node.equals(through.get(cursor))) {
                cursor++;
            }
        }
        return cursor == through.size();
    }

    static int specificity(ExceptionRule rule) {
        int score = 0;
        if (rule.from() != null) {
            score += 1;
        }
        if (rule.to() != null) {
            score += 1;
        }
        if (rule.through() != null) {
            score += 2 * rule.through().size();
        }
        return score;
    }

    static Decision resolve(List<ExceptionRule> rules, String start, String end,
                            List<String> path) {
        Decision decision = new Decision();
        List<ExceptionRule> falsePaths = new ArrayList<>();
        List<ExceptionRule> setupRules = new ArrayList<>();
        List<ExceptionRule> holdRules = new ArrayList<>();

        for (ExceptionRule rule : rules) {
            if (!matches(rule, start, end, path)) {
                continue;
            }
            if (rule.kind() == ExceptionKind.FALSE_PATH) {
                falsePaths.add(rule);
            } else if (rule.setupCycles() != null) {
                setupRules.add(rule);
            } else {
                holdRules.add(rule);
            }
        }

        ExceptionRule falseRule = pickMostSpecific(falsePaths);
        if (falseRule != null) {
            decision.falsePath = true;
            decision.falsePathRule = falseRule;
            return decision;
        }

        ExceptionRule setupRule = pickMostSpecific(setupRules);
        ExceptionRule holdRule = pickMostSpecific(holdRules);
        if (setupRule != null) {
            decision.setupCycles = setupRule.setupCycles();
            decision.setupRule = setupRule;
        }
        if (holdRule != null) {
            decision.holdCycles = holdRule.holdCycles();
            decision.holdRule = holdRule;
        }
        return decision;
    }

    private static ExceptionRule pickMostSpecific(List<ExceptionRule> matched) {
        if (matched.isEmpty()) {
            return null;
        }
        matched.sort((a, b) -> {
            int cmp = Integer.compare(specificity(b), specificity(a));
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a.line(), b.line());
        });
        ExceptionRule winner = matched.get(0);
        int winnerScore = specificity(winner);
        Object winnerEffect = winner.setupCycles() != null
                ? winner.setupCycles() : winner.holdCycles();
        for (int i = 1; i < matched.size(); i++) {
            ExceptionRule other = matched.get(i);
            if (specificity(other) != winnerScore) {
                break;
            }
            Object otherEffect = other.setupCycles() != null
                    ? other.setupCycles() : other.holdCycles();
            if (!java.util.Objects.equals(winnerEffect, otherEffect)) {
                throw new StaException(StaException.Code.CONSTRAINT_CONFLICT,
                        "conflicting " + winner.kind() + " exceptions at lines "
                                + winner.line() + " and " + other.line()
                                + " match the same paths with equal specificity ("
                                + winnerScore + ") but demand " + winnerEffect + " vs "
                                + otherEffect + " cycles");
            }
        }
        return winner;
    }
}
