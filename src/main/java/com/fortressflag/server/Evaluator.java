package com.fortressflag.server;

import com.fortressflag.server.Ruleset.Decoded;
import com.fortressflag.server.Ruleset.FlagCondition;
import com.fortressflag.server.Ruleset.FlagConfig;
import com.fortressflag.server.Ruleset.FlagRule;
import java.util.Map;

/**
 * Local evaluation — the reason this SDK exists (Founding §3), and a byte-for-byte
 * behavioural PORT of the backend's clientapi evaluateValue walk via the Go SDK. The
 * implementations are pinned to each other by vectors/evaluation.json; a semantic
 * difference here is a wire-contract bug, never a local judgement call. No I/O, no
 * logging, no allocation beyond the walk — this sits on the customer's hot path.
 *
 * <p>The walk, exactly as the contract states it: rules in order, first match wins; AND
 * within a rule with an EMPTY condition list holding vacuously (the terminal "everyone
 * else" rule); an absent tag key does not hold; eq/neq exact and contains substring
 * (backend ADR-0023), all case-sensitive and untrimmed;
 * semver via {@link Semver#parse} with an unparseable value on EITHER side not holding;
 * an unknown operator failing closed; the rollout gate computing the bucket at most once
 * per flag with a gated-out context falling THROUGH to later rules; a serve value that
 * does not follow the kind failed closed past the rule.
 */
final class Evaluator {
    private Evaluator() {}

    static Decoded evaluate(
            FlagConfig config, Map<String, String> tags, String contextKey, String flagKey) {
        int contextBucket = -1;
        for (FlagRule rule : config.rules()) {
            if (!ruleMatches(rule, tags)) {
                continue;
            }
            if (rule.rolloutPercentage() != null) {
                if (contextBucket < 0) {
                    contextBucket = Bucket.of(contextKey, flagKey);
                }
                if (contextBucket >= rule.rolloutPercentage()) {
                    continue;
                }
            }
            Decoded served = Ruleset.decodeValue(rule.serve(), config.kind());
            if (served.ok()) {
                return served;
            }
        }
        return Ruleset.decodeValue(config.defaultValue(), config.kind());
    }

    private static boolean ruleMatches(FlagRule rule, Map<String, String> tags) {
        for (FlagCondition condition : rule.conditions()) {
            String tagValue = tags.get(condition.tagKey());
            if (tagValue == null) {
                return false;
            }
            if (!conditionHolds(condition, tagValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionHolds(FlagCondition condition, String tagValue) {
        switch (condition.operator()) {
            case "eq":
                return tagValue.equals(condition.value());
            case "neq":
                return !tagValue.equals(condition.value());
            case "contains":
                return tagValue.contains(condition.value());
            case "semver_eq":
            case "semver_gt":
            case "semver_gte":
            case "semver_lt":
            case "semver_lte": {
                long[] context = Semver.parse(tagValue);
                if (context == null) {
                    return false;
                }
                long[] target = Semver.parse(condition.value());
                if (target == null) {
                    return false;
                }
                int cmp = Semver.compare(context, target);
                return switch (condition.operator()) {
                    case "semver_eq" -> cmp == 0;
                    case "semver_gt" -> cmp > 0;
                    case "semver_gte" -> cmp >= 0;
                    case "semver_lt" -> cmp < 0;
                    default -> cmp <= 0; // semver_lte
                };
            }
            default:
                return false;
        }
    }
}
