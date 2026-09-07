package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import org.noear.snack4.jsonpath.segment.DescendantSegment;
import org.noear.snack4.jsonpath.segment.FuncSegment;
import org.noear.snack4.jsonpath.segment.Segment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructException.UnsupportedConstruct;

/**
 * The denylist seam (see design doc "Files to create"). Pure predicate over the segment list —
 * kept separate from {@link Rfc9535SqlPredicateCompiler} so a future in-memory "B" fallback can
 * swap "detect → reject" for "detect → route to in-memory eval" without touching the compiler.
 */
@Component
public class UnsupportedConstructDetector {

    private static final Pattern FUNCTION_NAME = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\(");
    private final BracketSelectorClassifier classifier = new BracketSelectorClassifier();

    /** Returns the first denylisted construct found, or empty if the whole expression is supported. */
    public Optional<UnsupportedConstruct> firstUnsupported(List<Segment> segments) {
        for (Segment segment : segments) {
            Optional<UnsupportedConstruct> found = classify(segment);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<UnsupportedConstruct> classify(Segment segment) {
        if (segment instanceof DescendantSegment) {
            return Optional.of(UnsupportedConstruct.DESCENDANT_SEGMENT);
        }
        if (segment instanceof FuncSegment) {
            return classifyFunction(segment.getOriginalText());
        }
        String originalText = segment.getOriginalText();
        if (originalText == null || originalText.isEmpty() || originalText.charAt(0) != '[') {
            return Optional.empty();
        }
        BracketSelectorClassifier.Classification classification = classifier.classify(originalText);
        return classification.kind() == BracketSelectorClassifier.Kind.SLICE_WITH_STEP
                ? Optional.of(UnsupportedConstruct.SLICE_WITH_STEP)
                : Optional.empty();
    }

    private static Optional<UnsupportedConstruct> classifyFunction(String originalText) {
        // originalText is ".name(args)" — strip the leading '.' before matching the name.
        String withoutLeadingDot = originalText.startsWith(".") ? originalText.substring(1) : originalText;
        var matcher = FUNCTION_NAME.matcher(withoutLeadingDot);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String name = matcher.group(1).toLowerCase();
        return switch (name) {
            case "count" -> Optional.of(UnsupportedConstruct.COUNT_FUNCTION);
            case "value" -> Optional.of(UnsupportedConstruct.VALUE_FUNCTION);
            case "match", "search" -> Optional.of(UnsupportedConstruct.REGEX_FUNCTION);
            // Any other RFC 9535/Jayway-extension function (length, keys, avg, sum, ...) is
            // outside this pass's supported subset (see "Decided scope"); closest denylisted
            // bucket by shape is VALUE_FUNCTION (both compute a scalar from a node, not a
            // boolean/filterable predicate this compiler can express in typed jsonb SQL).
            default -> Optional.of(UnsupportedConstruct.VALUE_FUNCTION);
        };
    }
}
