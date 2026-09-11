package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

/**
 * Thrown when an expression is not valid RFC 9535 syntax at all — distinct from
 * {@link UnsupportedConstructException} (valid syntax, unsupported construct).
 */
public class InvalidRfc9535SyntaxException extends RuntimeException {

    public InvalidRfc9535SyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
