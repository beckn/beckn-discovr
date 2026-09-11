package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

/**
 * Thrown when an expression is valid RFC 9535 syntax but uses a construct this pass's T2
 * (typed {@code jsonb}-operator SQL) translator deliberately does not support — see
 * docs/design/DESIGN-rfc9535-jsonpath-grammar.md "DECISION — pure T2".
 */
public class UnsupportedConstructException extends RuntimeException {

    /** One denylisted RFC 9535 construct, 1:1 mapped onto its own {@code ErrorCode}. */
    public enum UnsupportedConstruct {
        DESCENDANT_SEGMENT,
        SLICE_WITH_STEP,
        COUNT_FUNCTION,
        VALUE_FUNCTION,
        REGEX_FUNCTION
    }

    private final UnsupportedConstruct construct;

    public UnsupportedConstructException(UnsupportedConstruct construct, String message) {
        super(message);
        this.construct = construct;
    }

    public UnsupportedConstruct construct() {
        return construct;
    }
}
