package org.beckn.discover.filter.rfc9535;

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
        return "$" + visit(ctx.segments());
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
        return ".*";
    }

    @Override
    public String visitChildBracketed(ChildBracketedContext ctx) {
        return visit(ctx.bracketed());
    }

    // ── Descendant segments ('..' → '.**') ─────────────────────────────────────

    @Override
    public String visitDescMember(DescMemberContext ctx) {
        return ".**." + pgMember(ctx.memberName().getText());
    }

    @Override
    public String visitDescWildcard(DescWildcardContext ctx) {
        return ".**";
    }

    @Override
    public String visitDescBracketed(DescBracketedContext ctx) {
        return ".**" + visit(ctx.bracketed());
    }

    // ── Bracketed selection ─────────────────────────────────────────────────--

    @Override
    public String visitBracketed(BracketedContext ctx) {
        List<SelectorContext> selectors = ctx.selector();
        if (selectors.size() == 1) {
            // Each labelled selector returns a full PG fragment incl. its own delimiters.
            return visit(selectors.get(0));
        }
        // Multiple selectors in one step: PG supports only index/slice/wildcard lists.
        List<String> parts = new ArrayList<>(selectors.size());
        for (SelectorContext s : selectors) {
            if (s instanceof SelFilterContext || s instanceof SelNameContext) {
                throw new UnsupportedFilterException(
                        "PostgreSQL cannot combine name/filter selectors with others in one step");
            }
            parts.add(stripBrackets(visit(s)));
        }
        return "[" + String.join(", ", parts) + "]";
    }

    @Override
    public String visitSelName(SelNameContext ctx) {
        return "." + pgMember(unquote(ctx.nameSelector().STRING().getText()));
    }

    @Override
    public String visitSelWildcard(SelWildcardContext ctx) {
        return "[*]";
    }

    @Override
    public String visitSelIndex(SelIndexContext ctx) {
        return "[" + pgIndex(Integer.parseInt(ctx.indexSelector().INT().getText())) + "]";
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
        String not = ctx.NOT() != null ? "!" : "";
        return not + visit(ctx.testExpr());
    }

    // ── Comparison ────────────────────────────────────────────────────────────

    @Override
    public String visitComparisonExpr(ComparisonExprContext ctx) {
        String left = visit(ctx.comparable(0));
        String op = ctx.compareOp().getText();   // ==, !=, <=, >=, <, > — all valid in PG
        String right = visit(ctx.comparable(1));
        return left + " " + op + " " + right;
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
            return "exists(" + visit(ctx.filterQuery()) + ")";
        }
        return visit(ctx.functionExpr());
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
            return "." + pgMember(unquote(ctx.STRING().getText()));
        }
        return "[" + pgIndex(Integer.parseInt(ctx.INT().getText())) + "]";
    }

    // ── Functions (length/count/match/search/value) ─────────────────────────--

    @Override
    public String visitFunctionExpr(FunctionExprContext ctx) {
        String name = ctx.FUNCTION_NAME().getText();
        List<FunctionArgContext> args = ctx.functionArg();
        switch (name) {
            case "length":
                // RFC length() = element/char count of a single value → PG .size()
                // (array semantics). String/object length differs; arrays only here.
                requireArgCount(name, args, 1);
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
        return pgString(unquote(ctx.STRING().getText()));
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
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
        String start = ctx.start != null ? pgIndex(Integer.parseInt(ctx.start.getText())) : "0";
        String end;
        if (ctx.end != null) {
            int e = Integer.parseInt(ctx.end.getText());
            // RFC slice end is exclusive; PG 'to' is inclusive.
            end = e >= 0 ? String.valueOf(e - 1) : pgIndex(e);
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

    /** Strip surrounding quotes and unescape an RFC string literal token. */
    private static String unquote(String token) {
        String body = token.substring(1, token.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                sb.append(body.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Emit a PG jsonpath double-quoted string with proper escaping. */
    private static String pgString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String rawStringArg(String fn, FunctionArgContext arg) {
        if (!(arg.literal() instanceof StrLiteralContext)) {
            throw new UnsupportedFilterException(fn + "() requires a string-literal pattern argument");
        }
        return unquote(((StrLiteralContext) arg.literal()).STRING().getText());
    }

    private static void requireArgCount(String fn, List<FunctionArgContext> args, int n) {
        if (args.size() != n) {
            throw new UnsupportedFilterException(fn + "() expects " + n + " argument(s), got " + args.size());
        }
    }
}
