package org.beckn.discover.filter.rfc9535;

import org.beckn.discover.filter.FilterParseException;
import org.beckn.discover.filter.UnsupportedFilterException;
import org.beckn.discover.filter.rfc9535.gen.JsonPathBaseVisitor;
import org.beckn.discover.filter.rfc9535.gen.JsonPathParser.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Walks an RFC 9535 parse tree and emits an equivalent PostgreSQL SQL/JSON path
 * (SQL:2016) string. This is the database-specific half of the translation — the
 * grammar/AST is DB-neutral; everything PostgreSQL lives here.
 *
 * <p>Key structural mappings (RFC 9535 → PostgreSQL):</p>
 * <ul>
 *   <li>filter selector {@code [?expr]} → standalone {@code ? (expr)}</li>
 *   <li>recursive descent {@code ..} → {@code .**}</li>
 *   <li>bracketed name {@code ['k']} → dot accessor {@code ."k"}</li>
 *   <li>existence {@code @.a} → {@code exists(@.a)}</li>
 *   <li>{@code match()/search()} → {@code like_regex}</li>
 *   <li>single-quoted strings → double-quoted (+ escaped)</li>
 * </ul>
 *
 * <p>Constructs PostgreSQL cannot express (slice with step, etc.) raise
 * {@link UnsupportedFilterException} — the capability gate.</p>
 */
final class PgJsonPathEmitter extends JsonPathBaseVisitor<String> {

    private static final Pattern BARE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    protected String defaultResult() {
        return "";
    }

    @Override
    protected String aggregateResult(String aggregate, String nextResult) {
        return (aggregate == null ? "" : aggregate) + (nextResult == null ? "" : nextResult);
    }

    // ── Top level ─────────────────────────────────────────────────────────────

    @Override
    public String visitJsonpath(JsonpathContext ctx) {
        // Operations applied DIRECTLY to the root document — filter `$[?…]`, index
        // `$[0]`, slice `$[0:2]`, wildcard `$[*]`/`$.*` — depend on whether the root
        // is an array or object, which RFC handles type-agnostically but PostgreSQL
        // does not (lax-mode wrapping, object-vs-array wildcard). Discover paths
        // always begin with a member access (`$.catalogs…`), so we require that and
        // reject root-level operations rather than return a wrong result.
        List<SegmentContext> segs = ctx.segments().segment();
        if (!segs.isEmpty() && !isMemberAccess(segs.get(0))) {
            throw new UnsupportedFilterException(
                    "operations applied directly to the root ($) are not supported; begin with a member access");
        }
        return "$" + visit(ctx.segments());
    }

    /** A segment is a member access if it is `.name` or `['name']` (not wildcard/index/slice/filter). */
    private static boolean isMemberAccess(SegmentContext seg) {
        ChildSegmentContext child = seg.childSegment();
        if (child instanceof DotMemberContext) {
            return true;
        }
        if (child instanceof ChildBracketedContext cb) {
            List<SelectorContext> sels = cb.bracketed().selector();
            return sels.size() == 1 && sels.get(0) instanceof SelNameContext;
        }
        return false;
    }

    @Override
    public String visitSegments(SegmentsContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (SegmentContext s : ctx.segment()) {
            sb.append(visit(s));
        }
        return sb.toString();
    }

    // ── Child segments ──────────────────────────────────────────────────────--

    @Override
    public String visitDotMember(DotMemberContext ctx) {
        return "." + pgMember(ctx.memberName().getText());
    }

    @Override
    public String visitDotWildcard(DotWildcardContext ctx) {
        // RFC `*` selects children of arrays AND objects; PG `.*` is object-only and
        // `[*]` array-only. Discover uses bracket-wildcard `[*]` on known arrays
        // (resources[*], offers[*]). The dot-wildcard form is type-agnostic and
        // cannot be faithfully mapped → reject.
        throw new UnsupportedFilterException(
                "dot-wildcard ('.*') is not supported; use a bracket wildcard on an array (e.g. resources[*])");
    }

    @Override
    public String visitChildBracketed(ChildBracketedContext ctx) {
        return visit(ctx.bracketed());
    }

    // ── Descendant segments — rejected (PG cannot match RFC semantics) ──────────
    // RFC 9535 `..` visits each node once in document order. PostgreSQL `.**`
    // differs materially: it yields duplicate matches, a different ordering, and
    // includes intermediate nodes. There is no faithful PG rewrite, so we reject
    // rather than return wrong results (reject-over-guess).

    private static final String DESCENDANT_UNSUPPORTED =
            "recursive descent ('..') is not supported: PostgreSQL '.**' does not match RFC 9535 semantics";

    @Override
    public String visitDescMember(DescMemberContext ctx) {
        throw new UnsupportedFilterException(DESCENDANT_UNSUPPORTED);
    }

    @Override
    public String visitDescWildcard(DescWildcardContext ctx) {
        throw new UnsupportedFilterException(DESCENDANT_UNSUPPORTED);
    }

    @Override
    public String visitDescBracketed(DescBracketedContext ctx) {
        throw new UnsupportedFilterException(DESCENDANT_UNSUPPORTED);
    }

    // ── Bracketed selection ─────────────────────────────────────────────────--

    @Override
    public String visitBracketed(BracketedContext ctx) {
        List<SelectorContext> selectors = ctx.selector();
        if (selectors.size() == 1) {
            // Each labelled selector returns a full PG fragment incl. its own delimiters.
            return visit(selectors.get(0));
        }
        // Multiple selectors in one step: PostgreSQL supports only index/slice
        // lists. Name, filter, and wildcard selectors cannot be combined with
        // others in a single subscript (PG raises a syntax error) → reject.
        List<String> parts = new ArrayList<>(selectors.size());
        for (SelectorContext s : selectors) {
            if (s instanceof SelFilterContext || s instanceof SelNameContext || s instanceof SelWildcardContext) {
                throw new UnsupportedFilterException(
                        "PostgreSQL cannot combine name/filter/wildcard selectors with others in one step");
            }
            parts.add(stripBrackets(visit(s)));
        }
        return "[" + String.join(", ", parts) + "]";
    }

    @Override
    public String visitSelName(SelNameContext ctx) {
        return "." + pgMember(decodeString(ctx.nameSelector().STRING().getText()));
    }

    @Override
    public String visitSelWildcard(SelWildcardContext ctx) {
        return "[*]";
    }

    @Override
    public String visitSelIndex(SelIndexContext ctx) {
        return "[" + pgIndex(intArg(ctx.indexSelector().INT().getText())) + "]";
    }

    @Override
    public String visitSelSlice(SelSliceContext ctx) {
        return "[" + pgSlice(ctx.sliceSelector()) + "]";
    }

    @Override
    public String visitSelFilter(SelFilterContext ctx) {
        return " ? (" + visit(ctx.filterSelector().logicalExpr()) + ")";
    }

    // ── Filter logical structure ────────────────────────────────────────────--

    @Override
    public String visitLogicalExpr(LogicalExprContext ctx) {
        return visit(ctx.logicalOr());
    }

    @Override
    public String visitLogicalOr(LogicalOrContext ctx) {
        List<String> parts = new ArrayList<>();
        for (LogicalAndContext c : ctx.logicalAnd()) {
            parts.add(visit(c));
        }
        return String.join(" || ", parts);
    }

    @Override
    public String visitLogicalAnd(LogicalAndContext ctx) {
        List<String> parts = new ArrayList<>();
        for (BasicExprContext c : ctx.basicExpr()) {
            parts.add(visit(c));
        }
        return String.join(" && ", parts);
    }

    @Override
    public String visitParenExpr(ParenExprContext ctx) {
        String not = ctx.NOT() != null ? "!" : "";
        return not + "(" + visit(ctx.logicalExpr()) + ")";
    }

    @Override
    public String visitCompExpr(CompExprContext ctx) {
        return visit(ctx.comparisonExpr());
    }

    @Override
    public String visitExistExpr(ExistExprContext ctx) {
        String inner = visit(ctx.testExpr());
        // PG requires the operand of '!' to be parenthesised (e.g. `!(@.a like_regex …)`
        // is a syntax error otherwise). Always wrap when negating.
        return ctx.NOT() != null ? "!(" + inner + ")" : inner;
    }

    // ── Comparison ────────────────────────────────────────────────────────────

    @Override
    public String visitComparisonExpr(ComparisonExprContext ctx) {
        String left = visit(ctx.comparable(0));
        String op = ctx.compareOp().getText();   // ==, !=, <=, >=, <, > — all valid in PG
        String right = visit(ctx.comparable(1));
        boolean leftPath = ctx.comparable(0) instanceof PathComparableContext;
        boolean rightPath = ctx.comparable(1) instanceof PathComparableContext;

        // Path-vs-path comparison relies on RFC nodelist/deep-equality semantics
        // (and both-absent equality) that PostgreSQL scalar comparison cannot
        // reproduce → reject rather than return a wrong answer.
        if (leftPath && rightPath) {
            throw new UnsupportedFilterException(
                    "comparison of two paths is not supported (RFC deep/nodelist equality semantics)");
        }

        if ("!=".equals(op)) {
            // RFC 9535 §2.3.5.2.2: A != B is TRUE when the path operand is absent
            // ("Nothing") OR differs in TYPE OR differs in value. PostgreSQL lax mode
            // drops absent rows and treats a cross-type comparison as no-match, so a
            // bare `@.x != v` is wrong on both counts. Reconstruct RFC semantics:
            //   @.x != 1  →  (!exists(@.x) || @.x.type() != "number" || @.x != 1)
            String guard = leftPath ? neqGuard(left, litPgType(ctx.comparable(1)))
                    : rightPath ? neqGuard(right, litPgType(ctx.comparable(0)))
                    : null;
            if (guard != null) {
                return "(" + guard + " || " + left + " != " + right + ")";
            }
        }
        return left + " " + op + " " + right;
    }

    /** RFC `!=` guard for a singular-path operand: true when absent or of a different type. */
    private static String neqGuard(String path, String literalType) {
        if (literalType != null) {
            return "!exists(" + path + ") || " + path + ".type() != \"" + literalType + "\"";
        }
        return "!exists(" + path + ")";
    }

    /** PostgreSQL {@code .type()} string for a literal comparable, or {@code null} if not a literal. */
    private static String litPgType(ComparableContext c) {
        if (!(c instanceof LitComparableContext lit)) {
            return null;
        }
        LiteralContext l = lit.literal();
        if (l instanceof IntLiteralContext || l instanceof NumLiteralContext) return "number";
        if (l instanceof StrLiteralContext) return "string";
        if (l instanceof TrueLiteralContext || l instanceof FalseLiteralContext) return "boolean";
        if (l instanceof NullLiteralContext) return "null";
        return null;
    }

    @Override
    public String visitLitComparable(LitComparableContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public String visitPathComparable(PathComparableContext ctx) {
        return visit(ctx.singularQuery());
    }

    @Override
    public String visitFuncComparable(FuncComparableContext ctx) {
        return visit(ctx.functionExpr());
    }

    // ── Test expression (existence / boolean function) ──────────────────────--

    @Override
    public String visitTestExpr(TestExprContext ctx) {
        if (ctx.filterQuery() != null) {
            // RFC existence test allows a non-singular query (wildcard/slice/descendant):
            // it is true when the nodelist is non-empty. PG `exists()` over such a path
            // diverges (object-vs-array wildcard, descendant dups) → reject; a singular
            // existence test (@.a.b) maps faithfully.
            if (!isSingularQuery(ctx.filterQuery())) {
                throw new UnsupportedFilterException(
                        "non-singular existence test (wildcard/slice/descendant in a filter test) is not supported");
            }
            return "exists(" + visit(ctx.filterQuery()) + ")";
        }
        return visit(ctx.functionExpr());
    }

    /** A query is singular if every segment is a plain name or single index (no wildcard/slice/filter/descendant). */
    private static boolean isSingularQuery(FilterQueryContext fq) {
        SegmentsContext segs;
        if (fq.relQuery() != null) {
            segs = fq.relQuery().segments();
        } else {
            segs = fq.jsonpathQuery().segments();
        }
        for (SegmentContext seg : segs.segment()) {
            if (seg.descendantSegment() != null) {
                return false;
            }
            ChildSegmentContext child = seg.childSegment();
            if (child instanceof DotWildcardContext) {
                return false;
            }
            if (child instanceof ChildBracketedContext cb) {
                for (SelectorContext sel : cb.bracketed().selector()) {
                    // Only a single name or index keeps the query singular.
                    if (!(sel instanceof SelIndexContext) && !(sel instanceof SelNameContext)) {
                        return false;
                    }
                }
                if (cb.bracketed().selector().size() != 1) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String visitRelQuery(RelQueryContext ctx) {
        return "@" + visit(ctx.segments());
    }

    @Override
    public String visitJsonpathQuery(JsonpathQueryContext ctx) {
        return "$" + visit(ctx.segments());
    }

    // ── Singular query (comparison operand) ─────────────────────────────────--

    @Override
    public String visitSingularQuery(SingularQueryContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.CURRENT() != null ? "@" : "$");
        for (SingularSegmentContext s : ctx.singularSegment()) {
            sb.append(visit(s));
        }
        return sb.toString();
    }

    @Override
    public String visitSingularSegment(SingularSegmentContext ctx) {
        if (ctx.memberName() != null) {
            return "." + pgMember(ctx.memberName().getText());
        }
        if (ctx.STRING() != null) {
            return "." + pgMember(decodeString(ctx.STRING().getText()));
        }
        return "[" + pgIndex(intArg(ctx.INT().getText())) + "]";
    }

    // ── Functions (length/count/match/search/value) ─────────────────────────--

    @Override
    public String visitFunctionExpr(FunctionExprContext ctx) {
        String name = ctx.FUNCTION_NAME().getText();
        List<FunctionArgContext> args = ctx.functionArg();
        switch (name) {
            case "length":
                // RFC length() = element/char count of a single value → PG .size()
                // (array semantics). The argument must be a path (e.g. @.tags); a
                // literal arg would emit invalid PG like `1.size()` → reject.
                requireArgCount(name, args, 1);
                if (args.get(0).filterQuery() == null) {
                    throw new UnsupportedFilterException("length() requires a path argument");
                }
                return visit(args.get(0)) + ".size()";
            case "count":
                // RFC count() counts NODES in a nodelist — not array length. PG
                // jsonpath has no direct nodelist-count, so reject rather than emit
                // the wrong thing (.size() would be array length, a different value).
                throw new UnsupportedFilterException(
                        "count() has no direct PostgreSQL jsonpath equivalent");
            case "value":
                requireArgCount(name, args, 1);
                return visit(args.get(0));
            case "match":
            case "search": {
                requireArgCount(name, args, 2);
                String path = visit(args.get(0));
                String regex = rawStringArg(name, args.get(1));
                // RFC 9535 regex is I-Regexp (RFC 9485); PG like_regex is POSIX/XQuery.
                // They agree on common patterns but Unicode property classes (\p{..}/\P{..})
                // are I-Regexp-specific and PG rejects them → reject rather than emit
                // an invalid regex.
                if (regex.contains("\\p{") || regex.contains("\\P{")) {
                    throw new UnsupportedFilterException(
                            "regex Unicode property classes (\\p{..}) are not supported on PostgreSQL");
                }
                // match() = full match (anchored); search() = substring.
                String pattern = name.equals("match") ? "^(" + regex + ")$" : regex;
                return path + " like_regex " + pgString(pattern);
            }
            default:
                throw new UnsupportedFilterException("Unsupported function: " + name);
        }
    }

    @Override
    public String visitFunctionArg(FunctionArgContext ctx) {
        if (ctx.literal() != null) {
            return visit(ctx.literal());
        }
        if (ctx.filterQuery() != null) {
            return visit(ctx.filterQuery());
        }
        return visit(ctx.functionExpr());
    }

    // ── Literals ──────────────────────────────────────────────────────────────

    @Override
    public String visitIntLiteral(IntLiteralContext ctx) {
        return ctx.INT().getText();
    }

    @Override
    public String visitNumLiteral(NumLiteralContext ctx) {
        return ctx.NUMBER().getText();
    }

    @Override
    public String visitStrLiteral(StrLiteralContext ctx) {
        return pgString(decodeString(ctx.STRING().getText()));
    }

    @Override
    public String visitTrueLiteral(TrueLiteralContext ctx) {
        return "true";
    }

    @Override
    public String visitFalseLiteral(FalseLiteralContext ctx) {
        return "false";
    }

    @Override
    public String visitNullLiteral(NullLiteralContext ctx) {
        return "null";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Bare PG member if identifier-safe, else a double-quoted member (e.g. "schema:price"). */
    private static String pgMember(String name) {
        if (BARE_IDENT.matcher(name).matches()) {
            return name;
        }
        return "\"" + jsonEncode(name) + "\"";
    }

    /**
     * Parses an index/slice bound. RFC 9535 allows the full I-JSON integer range
     * (±(2^53-1)), but PostgreSQL subscripts are 32-bit. Out-of-int-range bounds
     * only ever address positions no array can hold (an empty result), so we
     * reject them rather than emit a value PostgreSQL would error on (reject-over-
     * guess). Non-integers are likewise rejected.
     */
    private static int intArg(String text) {
        long v;
        try {
            v = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new UnsupportedFilterException("index/slice bound is not an integer: " + text);
        }
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new UnsupportedFilterException("index/slice bound out of supported range: " + text);
        }
        return (int) v;
    }

    /** RFC index → PG index. Non-negative as-is; -1 → last; -n → last-(n-1). */
    private static String pgIndex(int idx) {
        if (idx >= 0) {
            return String.valueOf(idx);
        }
        if (idx == -1) {
            return "last";
        }
        return "last-" + (-idx - 1);
    }

    private static String pgSlice(SliceSelectorContext ctx) {
        if (ctx.step != null) {
            throw new UnsupportedFilterException("PostgreSQL jsonpath does not support slice step");
        }
        String start = ctx.start != null ? pgIndex(intArg(ctx.start.getText())) : "0";
        String end;
        if (ctx.end != null) {
            int e = intArg(ctx.end.getText());
            // RFC slice end is EXCLUSIVE; PG 'to' is INCLUSIVE → subtract one.
            //   positive e  → e-1
            //   negative e  → len+e exclusive  = last+e inclusive  = "last-(|e|)"
            //   e == 0      → empty selection; PG cannot express a guaranteed-empty
            //                 slice via a literal subscript → reject (never guess).
            if (e > 0) {
                end = String.valueOf(e - 1);
            } else if (e < 0) {
                end = "last-" + (-e);
            } else {
                throw new UnsupportedFilterException(
                        "slice with end 0 (empty selection) is not representable in PostgreSQL jsonpath");
            }
        } else {
            end = "last";
        }
        return start + " to " + end;
    }

    private static String stripBrackets(String fragment) {
        if (fragment.startsWith("[") && fragment.endsWith("]")) {
            return fragment.substring(1, fragment.length() - 1);
        }
        throw new UnsupportedFilterException("selector cannot be combined in a multi-selector step");
    }

    /**
     * Decodes an RFC 9535 string-literal token (single- or double-quoted) into its
     * real character value — handling {@code \" \' \\ \/ \b \f \n \r \t} and
     * {@code \\uXXXX} (incl. surrogate pairs). The naive "drop the backslash"
     * approach was wrong: it turned {@code "\n"} into the letter {@code n}.
     */
    private static String decodeString(String token) {
        String body = token.substring(1, token.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= body.length()) {
                break;
            }
            char e = body.charAt(i);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\'': sb.append('\''); break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (i + 5 > body.length()) {
                        throw new FilterParseException("truncated \\u escape in string literal");
                    }
                    try {
                        sb.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException nfe) {
                        throw new FilterParseException("invalid \\u escape in string literal");
                    }
                    i += 4;
                    break;
                default:
                    sb.append(e); // lenient: unknown escape → the literal char
            }
        }
        return sb.toString();
    }

    /** Emit a PG jsonpath double-quoted string literal from a decoded value. */
    private static String pgString(String value) {
        return "\"" + jsonEncode(value) + "\"";
    }

    /** JSON-string-encode the body of a PG string/member: escape quotes, backslash, control chars. */
    private static String jsonEncode(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b");  break;
                case '\f': b.append("\\f");  break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static String rawStringArg(String fn, FunctionArgContext arg) {
        if (!(arg.literal() instanceof StrLiteralContext)) {
            throw new UnsupportedFilterException(fn + "() requires a string-literal pattern argument");
        }
        return decodeString(((StrLiteralContext) arg.literal()).STRING().getText());
    }

    private static void requireArgCount(String fn, List<FunctionArgContext> args, int n) {
        if (args.size() != n) {
            throw new UnsupportedFilterException(fn + "() expects " + n + " argument(s), got " + args.size());
        }
    }
}
