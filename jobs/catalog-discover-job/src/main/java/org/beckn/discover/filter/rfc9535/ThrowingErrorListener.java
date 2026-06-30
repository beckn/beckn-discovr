package org.beckn.discover.filter.rfc9535;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * ANTLR error listener that fails fast on the first syntax error instead of
 * ANTLR's default recover-and-continue behaviour. A recognised-but-malformed
 * expression must be rejected, not silently repaired.
 */
final class ThrowingErrorListener extends BaseErrorListener {

    static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

    private ThrowingErrorListener() {
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg,
                            RecognitionException e) {
        throw new ParseCancellationException("at " + line + ":" + charPositionInLine + " " + msg);
    }
}
