package com.edupulse.retrievalpre.normalize;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cleans a user question before searching: removes greetings, polite request
 * prefixes ("can you please tell me", "could you explain") and trailing
 * question marks / thanks.
 *
 * Rules are precompiled and applied in a fixed order. They are written without
 * nested quantifiers (ReDoS-safe), and the input length is capped.
 * The result is idempotent: normalizing an already normalized query changes nothing.
 */
@Component
public class QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizer.class);

    // Longer inputs are cut. Keeps regex work small even for adversarial input.
    static final int MAX_QUERY_LENGTH = 1000;

    // Rules can expose new text to other rules, so we repeat until nothing changes.
    private static final int MAX_PASSES = 5;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Rules in order. Each one removes the part of the text it matches.
    private static final List<Pattern> RULES = List.of(
            // "Hi," / "Hello!" / "Good morning," at the start
            Pattern.compile("^(?:hi|hello|hey|good morning|good afternoon|good evening)\\b[\\s,.!:;-]*",
                    Pattern.CASE_INSENSITIVE),
            // "Please ..." at the start
            Pattern.compile("^please\\b[\\s,.:;-]*", Pattern.CASE_INSENSITIVE),
            // "Can you (please) tell me / explain / describe / show me ..." at the start
            Pattern.compile("^(?:can|could|would|will) you(?:\\s+please)?\\s+"
                            + "(?:tell me|explain|describe|show me|help me understand)\\b[\\s,.:;-]*",
                    Pattern.CASE_INSENSITIVE),
            // "I would like to know ..." / "I want to know ..." at the start
            Pattern.compile("^i\\s+(?:would like|want|need)\\s+to know\\b[\\s,.:;-]*",
                    Pattern.CASE_INSENSITIVE),
            // trailing question marks and spaces
            Pattern.compile("[?\\s]+$"),
            // trailing "please" / "thanks" / "thank you"
            Pattern.compile("[\\s,;:-]+(?:please|thanks|thank you)\\W*$", Pattern.CASE_INSENSITIVE));

    /**
     * Returns the normalized query. Never returns null. If cleaning would leave
     * nothing (e.g. the query was only "please?"), the trimmed original is returned.
     */
    public String normalize(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }

        String original = collapseWhitespace(truncate(rawQuery));
        String current = original;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String next = applyRules(current);
            if (next.equals(current)) {
                break; // nothing changed, we are done
            }
            current = next;
        }

        if (current.isEmpty()) {
            current = original;
        }

        // Log both versions (new lines removed so nobody can fake log lines).
        log.info("Query normalized: raw='{}' normalized='{}'", forLog(rawQuery), forLog(current));
        return current;
    }

    private String applyRules(String text) {
        String result = text;
        for (Pattern rule : RULES) {
            result = rule.matcher(result).replaceFirst("");
        }
        return collapseWhitespace(result);
    }

    private static String truncate(String text) {
        return text.length() > MAX_QUERY_LENGTH ? text.substring(0, MAX_QUERY_LENGTH) : text;
    }

    private static String collapseWhitespace(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    private static String forLog(String text) {
        return truncate(text).replace('\n', ' ').replace('\r', ' ');
    }
}
