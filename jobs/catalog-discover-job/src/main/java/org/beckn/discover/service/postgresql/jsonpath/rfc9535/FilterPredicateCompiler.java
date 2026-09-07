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

    /** One typed SQL boolean fragment plus its bound parameters, in left-to-right order. */
    record Sql(String fragment, List<Object> parameters) {
    }

    private final String elementAlias;
    private final List<Token> tokens;
    private int position;

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
            Sql inner = parseUnary();
            return new Sql("NOT (" + inner.fragment() + ")", inner.parameters());
        }
        return parseAtom();
    }

    private Sql parseAtom() {
        if (peekIs(TokenType.LPAREN)) {
            position++;
            Sql inner = parseOr();
            expect(TokenType.RPAREN);
            return new Sql("(" + inner.fragment() + ")", inner.parameters());
        }
        return parseComparisonOrExistence();
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

        return new Sql(castExtraction + " " + op + " ?", params);
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
                result.add(new Token(TokenType.LITERAL, content.substring(start, i)));
            }
        }
        return result;
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
