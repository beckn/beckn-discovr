grammar JsonPath;

/*
 * RFC 9535 (JSONPath) — realistic subset used by Beckn Discovr filters.
 *
 * Covers: root, child/descendant segments, member-name shorthand, wildcard,
 * index, slice and filter selectors; filter logical/comparison/existence
 * expressions; the RFC function extensions (length/count/match/search/value);
 * and the literal forms. Member names carrying special characters (e.g.
 * "schema:price") arrive via bracketed string selectors per the RFC, never as
 * dot shorthand.
 *
 * This grammar is transcribed from the RFC 9535 ABNF (Appendix A). It is the
 * single source of truth for "what is valid RFC 9535" — proven against the
 * JSONPath Compliance Test Suite.
 */

// ── Parser ───────────────────────────────────────────────────────────────────

jsonpath        : ROOT segments EOF ;

segments        : segment* ;

segment         : childSegment
                | descendantSegment
                ;

childSegment    : DOT memberName            # dotMember
                | DOT WILDCARD               # dotWildcard
                | bracketed                  # childBracketed
                ;

descendantSegment
                : DOTDOT memberName          # descMember
                | DOTDOT WILDCARD            # descWildcard
                | DOTDOT bracketed           # descBracketed
                ;

bracketed       : LBRACK selector (COMMA selector)* RBRACK ;

selector        : nameSelector              # selName
                | WILDCARD                   # selWildcard
                | sliceSelector              # selSlice
                | indexSelector              # selIndex
                | filterSelector             # selFilter
                ;

nameSelector    : STRING ;
indexSelector   : INT ;
sliceSelector   : start=INT? COLON end=INT? (COLON step=INT?)? ;

filterSelector  : QUESTION logicalExpr ;

logicalExpr     : logicalOr ;
logicalOr       : logicalAnd (OR logicalAnd)* ;
logicalAnd      : basicExpr (AND basicExpr)* ;

basicExpr       : NOT? LPAREN logicalExpr RPAREN     # parenExpr
                | comparisonExpr                      # compExpr
                | NOT? testExpr                       # existExpr
                ;

comparisonExpr  : comparable compareOp comparable ;

comparable      : literal                    # litComparable
                | singularQuery              # pathComparable
                | functionExpr               # funcComparable
                ;

testExpr        : filterQuery
                | functionExpr
                ;

filterQuery     : relQuery
                | jsonpathQuery
                ;

relQuery        : CURRENT segments ;
jsonpathQuery   : ROOT segments ;

// Singular query — only name/index segments (no wildcard/filter/slice).
singularQuery   : (CURRENT | ROOT) singularSegment* ;
singularSegment : DOT memberName
                | LBRACK STRING RBRACK
                | LBRACK INT RBRACK
                ;

functionExpr    : FUNCTION_NAME LPAREN (functionArg (COMMA functionArg)*)? RPAREN ;
functionArg     : literal
                | filterQuery
                | functionExpr
                ;

compareOp       : EQ | NE | LE | GE | LT | GT ;

memberName      : MEMBER_NAME | FUNCTION_NAME | TRUE | FALSE | NULL ;

literal         : INT          # intLiteral
                | NUMBER       # numLiteral
                | STRING       # strLiteral
                | TRUE         # trueLiteral
                | FALSE        # falseLiteral
                | NULL         # nullLiteral
                ;

// ── Lexer ────────────────────────────────────────────────────────────────────

TRUE     : 'true' ;
FALSE    : 'false' ;
NULL     : 'null' ;

// function names per RFC 9535 (length, count, match, search, value)
FUNCTION_NAME : 'length' | 'count' | 'match' | 'search' | 'value' ;

ROOT     : '$' ;
CURRENT  : '@' ;
WILDCARD : '*' ;
DOTDOT   : '..' ;
DOT      : '.' ;
LBRACK   : '[' ;
RBRACK   : ']' ;
LPAREN   : '(' ;
RPAREN   : ')' ;
COMMA    : ',' ;
COLON    : ':' ;
QUESTION : '?' ;

EQ  : '==' ;
NE  : '!=' ;
LE  : '<=' ;
GE  : '>=' ;
LT  : '<' ;
GT  : '>' ;
AND : '&&' ;
OR  : '||' ;
NOT : '!' ;

// NUMBER must precede INT so '12.5' is lexed whole; ANTLR longest-match also
// keeps '12' an INT (INT is shorter only when no fraction/exponent follows).
NUMBER  : '-'? ('0' | [1-9][0-9]*) ('.' [0-9]+) ([eE] [+\-]? [0-9]+)?
        | '-'? ('0' | [1-9][0-9]*) [eE] [+\-]? [0-9]+ ;
INT     : '-'? ('0' | [1-9][0-9]*) ;

MEMBER_NAME : [a-zA-Z_] [a-zA-Z0-9_]* ;

STRING  : '"' (ESC | ~["\\])* '"'
        | '\'' (ESC | ~['\\])* '\'' ;
fragment ESC : '\\' . ;

WS : [ \t\r\n]+ -> skip ;
