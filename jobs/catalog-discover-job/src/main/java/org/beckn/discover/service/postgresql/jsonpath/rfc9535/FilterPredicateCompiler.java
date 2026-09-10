package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compiles the content of an RFC 9535 filter selector ({@code ?(...)}) into a typed,
 * parameterized SQL boolean expression evaluated against a correlated {@code jsonb} element
 * alias (e.g. {@code e0}).
 *
 * <p>{@code snack4-jsonpath} evaluates filter predicates internally but does not expose their
 * parsed structure (see {@link BracketSelectorClassifier}'s class doc for the same gap at the
 * segment level) — this is the compiler's own small tokenizer/parser for the supported subset:
 * comparisons ({@code == != < <= > >=}), logical {@code && || !}, and grouping parens.</p>
 *
 * <p><b>Every path segment name and every literal value is bound as a {@code ?} SQL parameter —
 * never concatenated into the SQL text</b> (hard rule; see design doc "Translation mapping
 * table"). A hostile member name containing {@code ' " { } ,} cannot break out of the generated
 * query because it is never written into the SQL string at all.</p>
 */
final class FilterPredicateCompiler {

    /**
     * Nesting-depth cap on the recursive-descent parser (parens and {@code !} chains), mirroring
     * {@code Rfc9535SqlPredicateCompiler.MAX_PATH_DEPTH}. Without this, a filter predicate with a
     * few thousand nested parens (e.g. {@code ?(((((...)))))}) can exhaust the JVM stack via an
     * uncaught {@link StackOverflowError} — which extends {@link Error}, not {@link Exception},
     * so it is never caught by the global NACK handler. This throws a clean, cacheable
     * {@link InvalidRfc9535SyntaxException} well before the JVM stack limit is at risk.
     */
    private static final int MAX_NESTING_DEPTH = 20;

    /** One typed SQL boolean fragment plus its bound parameters, in left-to-right order. */
    record Sql(String fragment, List<Object> parameters) {
    }

    private final String elementAlias;
    private final List<Token> tokens;
    private int position;
    private int nestingDepth;

    FilterPredicateCompiler(String elementAlias, String filterContent) {
        this.elementAlias = elementAlias;
        this.tokens = tokenize(filterContent);
    }

    Sql compile() {
        Sql result = parseOr();
        if (position != tokens.size()) {
            throw new InvalidRfc9535SyntaxException("Unexpected trailing content in filter predicate", null);
        }
        return result;
    }

    // ── Recursive-descent parser: || lowest, && next, ! highest, then atoms ────

    private Sql parseOr() {
        Sql left = parseAnd();
        while (peekIs(TokenType.OR)) {
            position++;
            Sql right = parseAnd();
            left = combine(left, right, "OR");
        }
        return left;
    }

    private Sql parseAnd() {
        Sql left = parseUnary();
        while (peekIs(TokenType.AND)) {
            position++;
            Sql right = parseUnary();
            left = combine(left, right, "AND");
        }
        return left;
    }

    private Sql parseUnary() {
        if (peekIs(TokenType.NOT)) {
            position++;
            enterNestingLevel();
            try {
                Sql inner = parseUnary();
                return new Sql("NOT (" + inner.fragment() + ")", inner.parameters());
            } finally {
                nestingDepth--;
            }
        }
        return parseAtom();
    }

    private Sql parseAtom() {
        if (peekIs(TokenType.LPAREN)) {
            position++;
            enterNestingLevel();
            try {
                Sql inner = parseOr();
                expect(TokenType.RPAREN);
                return new Sql("(" + inner.fragment() + ")", inner.parameters());
            } finally {
                nestingDepth--;
            }
        }
        return parseComparisonOrExistence();
    }

    /** Increments the nesting-depth counter, rejecting the predicate once it exceeds the cap. */
    private void enterNestingLevel() {
        if (++nestingDepth > MAX_NESTING_DEPTH) {
            throw new InvalidRfc9535SyntaxException(
                    "Filter predicate exceeds max supported nesting depth (" + MAX_NESTING_DEPTH + ")", null);
        }
    }

    private Sql parseComparisonOrExistence() {
        Token pathToken = expect(TokenType.PATH);
        List<String> pathComponents = parsePathComponents(pathToken.value());

        if (peekIs(TokenType.OPERATOR)) {
            Token op = tokens.get(position++);
            Token literalToken = expect(TokenType.LITERAL);
            return compileComparison(pathComponents, op.value(), literalToken.value());
        }
        return compileExistence(pathComponents);
    }

    private Sql compileComparison(List<String> path, String op, String rawLiteral) {
        List<Object> params = new ArrayList<>();
        String fieldExtraction = elementAlias + " #>> ?::text[]";
        params.add(path.toArray(String[]::new));

        LiteralValue literal = LiteralValue.infer(rawLiteral);
        String castExtraction = "(" + fieldExtraction + ")::" + literal.sqlCast();
        params.add(literal.boundValue());

        return new Sql(castExtraction + " " + toSqlOperator(op) + " ?", params);
    }

    /**
     * Translates an RFC 9535 comparison operator token into valid Postgres SQL syntax.
     *
     * <p>RFC 9535 uses {@code ==} for equality, which Postgres does not accept ({@code =} is
     * required instead). Every other comparison operator ({@code != < <= > >=}) is already
     * identical between RFC 9535 and Postgres SQL syntax, so this is the only remapping needed.</p>
     */
    private static String toSqlOperator(String rfc9535Operator) {
        return "==".equals(rfc9535Operator) ? "=" : rfc9535Operator;
    }

    private Sql compileExistence(List<String> path) {
        List<Object> params = new ArrayList<>();
        params.add(path.toArray(String[]::new));
        return new Sql("(" + elementAlias + " #>> ?::text[]) IS NOT NULL", params);
    }

    private static Sql combine(Sql left, Sql right, String operator) {
        List<Object> params = new ArrayList<>(left.parameters().size() + right.parameters().size());
        params.addAll(left.parameters());
        params.addAll(right.parameters());
        return new Sql("(" + left.fragment() + " " + operator + " " + right.fragment() + ")", params);
    }

    /** Splits {@code @.a.b} / {@code @['a']['b']} into {@code ["a", "b"]}. */
    private static List<String> parsePathComponents(String rawPath) {
        if (!rawPath.startsWith("@")) {
            throw new InvalidRfc9535SyntaxException("Filter path must start with '@': " + rawPath, null);
        }
        List<String> components = new ArrayList<>();
        int i = 1;
        while (i < rawPath.length()) {
            char c = rawPath.charAt(i);
            if (c == '.') {
                // Descendant segment ('..') is denylisted at the FuncSegment level by
                // UnsupportedConstructDetector, but it can also appear inside a filter
                // predicate's own path (e.g. ?(@..name == "x")) — must be caught here too,
                // rather than silently mis-parsed as two empty-named path components.
                if (i + 1 < rawPath.length() && rawPath.charAt(i + 1) == '.') {
                    throw new UnsupportedConstructException(UnsupportedConstructException.UnsupportedConstruct.DESCENDANT_SEGMENT,
                            "Descendant segment ('..') is not supported: " + rawPath);
                }
                int start = ++i;
                while (i < rawPath.length() && rawPath.charAt(i) != '.' && rawPath.charAt(i) != '[') {
                    i++;
                }
                components.add(rawPath.substring(start, i));
            } else if (c == '[') {
                int close = rawPath.indexOf(']', i);
                if (close < 0) {
                    throw new InvalidRfc9535SyntaxException("Unclosed '[' in filter path: " + rawPath, null);
                }
                String inner = rawPath.substring(i + 1, close);
                components.add(stripQuotes(inner));
                i = close + 1;
            } else {
                throw new InvalidRfc9535SyntaxException("Unexpected character in filter path: " + rawPath, null);
            }
        }
        if (components.isEmpty()) {
            throw new InvalidRfc9535SyntaxException("Filter path has no field components: " + rawPath, null);
        }
        return components;
    }

    private static String stripQuotes(String s) {
        String trimmed = s.trim();
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"')) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    // ── Tiny tokenizer ──────────────────────────────────────────────────────────

    private enum TokenType { PATH, LITERAL, OPERATOR, AND, OR, NOT, LPAREN, RPAREN }

    private record Token(TokenType type, String value) {
    }

    private static final Pattern MULTI_CHAR_OPERATOR = Pattern.compile("^(==|!=|<=|>=)");

    private static List<Token> tokenize(String content) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        int len = content.length();
        while (i < len) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                result.add(new Token(TokenType.LPAREN, "("));
                i++;
            } else if (c == ')') {
                result.add(new Token(TokenType.RPAREN, ")"));
                i++;
            } else if (content.startsWith("&&", i)) {
                result.add(new Token(TokenType.AND, "&&"));
                i += 2;
            } else if (content.startsWith("||", i)) {
                result.add(new Token(TokenType.OR, "||"));
                i += 2;
            } else if (c == '!' && !content.startsWith("!=", i)) {
                result.add(new Token(TokenType.NOT, "!"));
                i++;
            } else if (MULTI_CHAR_OPERATOR.matcher(content.substring(i)).find()) {
                result.add(new Token(TokenType.OPERATOR, content.substring(i, i + 2)));
                i += 2;
            } else if (c == '<' || c == '>') {
                result.add(new Token(TokenType.OPERATOR, String.valueOf(c)));
                i++;
            } else if (c == '\'' || c == '"') {
                int close = content.indexOf(c, i + 1);
                if (close < 0) {
                    throw new InvalidRfc9535SyntaxException("Unterminated string literal in filter", null);
                }
                result.add(new Token(TokenType.LITERAL, content.substring(i, close + 1)));
                i = close + 1;
            } else if (c == '@') {
                int start = i;
                i++;
                while (i < len && !isTokenBoundary(content.charAt(i)) && !Character.isWhitespace(content.charAt(i))) {
                    i++;
                }
                result.add(new Token(TokenType.PATH, content.substring(start, i)));
            } else {
                int start = i;
                while (i < len && !isTokenBoundary(content.charAt(i)) && !Character.isWhitespace(content.charAt(i))) {
                    i++;
                }
                String bareWord = content.substring(start, i);
                rejectIfFunctionCall(bareWord, content, i);
                result.add(new Token(TokenType.LITERAL, bareWord));
            }
        }
        return result;
    }

    private static final Pattern FUNCTION_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * RFC 9535's normative syntax for function extensions is a function call embedded inside a
     * filter predicate — e.g. {@code ?(count(@.offers) > 2)} — not just the Jayway-style {@code
     * .count()} path segment {@link UnsupportedConstructDetector} already catches. A bare word
     * immediately followed by {@code (} can only be a function call here (no other supported
     * term has that shape), so this is an unambiguous, denylisted construct — it must never fall
     * through to a generic {@link InvalidRfc9535SyntaxException} "unexpected token" failure.
     */
    private static void rejectIfFunctionCall(String bareWord, String content, int position) {
        if (position < content.length() && content.charAt(position) == '('
                && FUNCTION_NAME.matcher(bareWord).matches()) {
            var construct = UnsupportedConstructDetector.classifyFunctionName(bareWord);
            throw new UnsupportedConstructException(construct,
                    "Function call ('" + bareWord + "(...)') is not supported inside a filter predicate");
        }
    }

    private static boolean isTokenBoundary(char c) {
        return c == '(' || c == ')' || c == '&' || c == '|' || c == '!'
                || c == '=' || c == '<' || c == '>';
    }

    private boolean peekIs(TokenType type) {
        return position < tokens.size() && tokens.get(position).type() == type;
    }

    private Token expect(TokenType type) {
        if (!peekIs(type)) {
            throw new InvalidRfc9535SyntaxException(
                    "Expected " + type + " at position " + position + " in filter predicate", null);
        }
        return tokens.get(position++);
    }
}
