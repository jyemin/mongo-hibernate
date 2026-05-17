/*
 * Copyright 2025-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hibernate.internal.translate.mqlv2;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.mqlv2.ast.Assignment;
import com.mongodb.mqlv2.ast.BinaryOpType;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.SortDirection;
import com.mongodb.mqlv2.ast.SortSpec;
import com.mongodb.mqlv2.ast.Stage;
import com.mongodb.mqlv2.ast.UnaryOpType;
import com.mongodb.mqlv2.ast.Value;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.query.common.TemporalUnit;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.BinaryArithmeticOperator;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.EntityValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.ExtractUnit;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.expression.Star;
import org.hibernate.sql.ast.tree.expression.UnparsedNumericLiteral;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.predicate.BooleanExpressionPredicate;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.NullnessPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectClause;
import org.jspecify.annotations.Nullable;

/**
 * Translates Hibernate SQL AST {@link Expression} nodes into driver-mqlv2 {@link Expr} nodes. Currently scoped to the
 * cases used by Group-A array function arguments (literals, column references, JDBC parameters, arithmetic). Extended
 * in subsequent migration phases.
 *
 * <p>Stateless on purpose: qualifier rules (joins, unnest aliases) are carried by the {@link Mqlv2TranslationContext}
 * passed to each method.
 *
 * @hidden
 */
public final class Mqlv2IrEmitters {

    private Mqlv2IrEmitters() {}

    /**
     * Foundation: convert a Hibernate {@link Expression} into a driver-mqlv2 {@link Expr}. Covers the leaves Group-A
     * functions can pass as arguments. Throws for unsupported shapes — callers should not reach those (function
     * descriptors validate argument types).
     *
     * @param e the Hibernate expression to translate.
     * @param ctx translation context carrying the parameter binder list and qualifier rules; {@link JdbcParameter}
     *     nodes allocate an index via {@link Mqlv2TranslationContext#allocateParameter}.
     * @return the equivalent driver-mqlv2 {@link Expr} subtree.
     */
    public static Expr translateExpression(Expression e, Mqlv2TranslationContext ctx) {
        if (e instanceof BasicValuedPathInterpretation<?> bvpi) {
            return translateExpression(bvpi.getColumnReference(), ctx);
        }
        if (e instanceof ColumnReference cr) {
            return translateColumnRef(cr, ctx);
        }
        if (e instanceof QueryLiteral<?> ql) {
            return new Expr.ValueLit(translateLiteralValue(ql.getLiteralValue()));
        }
        if (e instanceof UnparsedNumericLiteral<?> unl) {
            return new Expr.ValueLit(translateLiteralValue(unl.getLiteralValue()));
        }
        if (e instanceof SqmParameterInterpretation spi) {
            return translateExpression(spi.getResolvedExpression(), ctx);
        }
        if (e instanceof JdbcParameter jp) {
            return new Expr.VarRef("p" + ctx.allocateParameter(jp.getParameterBinder()));
        }
        if (e instanceof BinaryArithmeticExpression bae) {
            return new Expr.BinaryOp(
                    translateArithmeticOp(bae.getOperator()),
                    translateExpression(bae.getLeftHandOperand(), ctx),
                    translateExpression(bae.getRightHandOperand(), ctx));
        }
        if (e instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
            var fnName = fn.getFunctionName();
            if (AGGREGATE_FUNCTION_NAMES.contains(fnName)) {
                var sig = aggSignature(fn, ctx.hasJoins());
                var alias = ctx.aggSignatureToName().get(sig);
                if (alias == null) {
                    throw new FeatureNotSupportedException(
                            "Aggregate function in expression not found in SELECT: " + fnName + "()");
                }
                return translateAggregateReference(alias);
            }
            if ("array".equals(fnName) || "array_list".equals(fnName)) {
                return translateArrayConstructor(fn, ctx);
            }
            if ("array_length".equals(fnName)) {
                return translateArrayLength(fn, ctx);
            }
            if ("array_get".equals(fnName)) {
                return translateArrayGet(fn, ctx);
            }
            if ("extract".equals(fnName)) {
                return translateExtract(fn, ctx);
            }
            throw new FeatureNotSupportedException("Unsupported function in IR translation: " + fnName);
        }
        throw new FeatureNotSupportedException(
                "Unsupported expression in IR translation: " + e.getClass().getSimpleName());
    }

    private static final Set<String> AGGREGATE_FUNCTION_NAMES = Set.of("count", "sum", "avg", "min", "max");

    /**
     * Compute the aggregate signature key used to look up the assigned {@code _aggN} alias. Mirrors the logic in
     * {@code Mqlv2SelectTranslator.aggSignature}.
     */
    private static String aggSignature(SelfRenderingFunctionSqlAstExpression<?> fn, boolean hasJoins) {
        var args = fn.getArguments();
        if (args.isEmpty() || args.get(0) instanceof Star || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            return fn.getFunctionName() + ":*";
        }
        if (args.get(0) instanceof Expression argExpr) {
            return fn.getFunctionName() + ":" + aggColumnSignature(argExpr, hasJoins);
        }
        return fn.getFunctionName() + ":?";
    }

    private static String aggColumnSignature(Expression expr, boolean hasJoins) {
        if (expr instanceof ColumnReference cr) {
            if (hasJoins && cr.getQualifier() != null && !cr.getQualifier().isEmpty()) {
                return cr.getQualifier() + "." + cr.getColumnExpression();
            }
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return aggColumnSignature(bvpi.getColumnReference(), hasJoins);
        } else {
            throw new FeatureNotSupportedException("Expected simple column reference in aggregate; got: "
                    + expr.getClass().getSimpleName());
        }
    }

    private static Value translateLiteralValue(Object v) {
        if (v == null) {
            return new Value.VNull();
        }
        if (v instanceof String s) {
            return new Value.VString(s);
        }
        if (v instanceof Boolean b) {
            return new Value.VBool(b);
        }
        if (v instanceof Number n) {
            if (v instanceof Double || v instanceof Float) {
                return new Value.VDouble(n.doubleValue());
            }
            return new Value.VInt(n.longValue());
        }
        throw new FeatureNotSupportedException(
                "Unsupported literal type in IR translation: " + v.getClass().getSimpleName());
    }

    private static BinaryOpType translateArithmeticOp(BinaryArithmeticOperator op) {
        return switch (op) {
            case ADD -> BinaryOpType.ADD;
            case SUBTRACT -> BinaryOpType.SUB;
            case MULTIPLY -> BinaryOpType.MUL;
            case DIVIDE, DIVIDE_PORTABLE, QUOT -> BinaryOpType.DIV;
            default ->
                throw new FeatureNotSupportedException("Unsupported arithmetic operator in IR translation: " + op);
        };
    }

    /**
     * Translate a column reference into a {@code FieldAccess} chain, honoring the qualifier rules carried by
     * {@code ctx}.
     *
     * <ul>
     *   <li>Unnest alias: {@code li.sku} → {@code lineItems.sku} (uses the context's alias→path map).
     *   <li>Joined qualifier: {@code o.id} → {@code o.id} (preserves the alias dot column).
     *   <li>Bare column: {@code id} (no qualifier).
     * </ul>
     */
    private static Expr translateColumnRef(ColumnReference cr, Mqlv2TranslationContext ctx) {
        String qualifier = cr.getQualifier();
        String column = cr.getColumnExpression();
        if (qualifier != null && ctx.unnestAliasToFieldPath().containsKey(qualifier)) {
            return buildFieldChain(ctx.unnestAliasToFieldPath().get(qualifier) + "." + column);
        }
        if (ctx.hasJoins() && qualifier != null && !qualifier.isEmpty()) {
            return buildFieldChain(qualifier + "." + column);
        }
        return new Expr.FieldAccess(new Expr.CurrentValue(), column);
    }

    /**
     * Build a left-leaning {@code FieldAccess} chain from a dot-separated path. For example, {@code "a.b.c"} becomes
     * {@code FieldAccess(FieldAccess(FieldAccess($, "a"), "b"), "c")}, which the Serializer renders as {@code a.b.c}.
     */
    private static Expr buildFieldChain(String dotPath) {
        Expr e = new Expr.CurrentValue();
        int start = 0;
        int dot;
        while ((dot = dotPath.indexOf('.', start)) != -1) {
            e = new Expr.FieldAccess(e, dotPath.substring(start, dot));
            start = dot + 1;
        }
        e = new Expr.FieldAccess(e, dotPath.substring(start));
        return e;
    }

    /**
     * Translate {@code array_get(arr, i)} → {@code arr[(i - 1)]}.
     *
     * <p>HQL is 1-based; MQLv2 is 0-based. The subtraction is placed inside the index brackets, producing the canonical
     * {@code arr[(i - 1)]} form (D2 paren drift vs. the legacy hand-rolled {@code arr[(i) - 1]}).
     *
     * @param fn the function call AST node.
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    public static Expr translateArrayGet(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var args = fn.getArguments();
        if (!(args.get(0) instanceof Expression arr) || !(args.get(1) instanceof Expression idx)) {
            throw new FeatureNotSupportedException("Non-expression argument in " + fn.getFunctionName() + "()");
        }
        return new Expr.ArrayIndex(
                translateExpression(arr, ctx),
                new Expr.BinaryOp(
                        BinaryOpType.SUB, translateExpression(idx, ctx), new Expr.ValueLit(new Value.VInt(1))));
    }

    /**
     * Translate {@code array(e1, …, en)} / {@code array_list(...)} → {@code [e1, …, en]}.
     *
     * @param fn the function call AST node.
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    public static Expr translateArrayConstructor(
            SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var args = fn.getArguments();
        List<Expr> elements = new ArrayList<>(args.size());
        for (var arg : args) {
            if (!(arg instanceof Expression elemExpr)) {
                throw new FeatureNotSupportedException("Non-expression argument in " + fn.getFunctionName() + "()");
            }
            elements.add(translateExpression(elemExpr, ctx));
        }
        return new Expr.ArrayConstructor(elements);
    }

    /**
     * Translate {@code array_length(arr)} / {@code cardinality(arr)} → {@code count(arr)}.
     *
     * @param fn the function call AST node.
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    public static Expr translateArrayLength(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        if (!(fn.getArguments().get(0) instanceof Expression arg)) {
            throw new FeatureNotSupportedException("Non-expression argument in " + fn.getFunctionName() + "()");
        }
        return new Expr.FunctionCall("count", List.of(translateExpression(arg, ctx)));
    }

    /**
     * Translate {@code array_intersects(a, b)} family (including {@code array_overlaps} aliases collapsed to canonical
     * name; {@code _nullable} variants too) to a nested MQLv2 {@code any} expression with a {@code let} binding.
     *
     * <ul>
     *   <li>{@code array_intersects(a, b)} → {@code a any (let $__x = $ in b any ($ == $__x))} (non-nullable uses
     *       {@code ==})
     *   <li>{@code array_intersects_nullable(a, b)} → {@code a any (let $__x = $ in b any ($ is $__x))} (nullable uses
     *       {@code is})
     * </ul>
     *
     * <p>D3b paren drift: the Serializer double-wraps the inner {@code any} body binop, producing {@code any (($ op
     * $__x))} vs. the old {@code any ($ op $__x)}.
     *
     * @param fn the function call AST node.
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    public static Expr translateArrayIntersects(
            SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var name = fn.getFunctionName();
        var args = fn.getArguments();
        if (!(args.get(0) instanceof Expression aE) || !(args.get(1) instanceof Expression bE)) {
            throw new FeatureNotSupportedException("Non-expression argument in " + name + "()");
        }
        BinaryOpType eqOp = name.endsWith("_nullable") ? BinaryOpType.IS : BinaryOpType.EQ;
        return new Expr.Any(
                translateExpression(aE, ctx),
                new Expr.LetExpr(
                        List.of(Map.entry("__x", (Expr) new Expr.CurrentValue())),
                        new Expr.Any(
                                translateExpression(bE, ctx),
                                new Expr.BinaryOp(eqOp, new Expr.CurrentValue(), new Expr.VarRef("__x")))));
    }

    /**
     * Translate {@code array_contains(arr, x)} / {@code array_contains_nullable(arr, x)} to an MQLv2 {@code any}
     * expression.
     *
     * <ul>
     *   <li>{@code array_contains(arr, x)} → {@code arr any ($ == x)} (non-nullable uses {@code ==})
     *   <li>{@code array_contains_nullable(arr, x)} → {@code arr any ($ is x)} (nullable uses {@code is})
     * </ul>
     *
     * <p>D3 paren drift vs. the legacy hand-rolled emission: the Serializer does not add outer parens around the
     * predicate at the match stage, but does double-wrap the {@code any} body (producing {@code any (($ op x))} vs. the
     * old {@code (arr any ($ op x))}).
     *
     * @param fn the function call AST node.
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    public static Expr translateArrayContains(
            SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var name = fn.getFunctionName();
        var args = fn.getArguments();
        if (!(args.get(0) instanceof Expression haystack) || !(args.get(1) instanceof Expression needle)) {
            throw new FeatureNotSupportedException("Non-expression argument in " + name + "()");
        }
        BinaryOpType eqOp = name.endsWith("_nullable") ? BinaryOpType.IS : BinaryOpType.EQ;
        return new Expr.Any(
                translateExpression(haystack, ctx),
                new Expr.BinaryOp(eqOp, new Expr.CurrentValue(), translateExpression(needle, ctx)));
    }

    /**
     * Translate a reference to an aggregate function whose alias has been resolved by the surrounding translator (e.g.,
     * {@code _agg0}). Emits a bare field-access shape so the Serializer renders the alias without a {@code $} prefix.
     *
     * <p>Example: after a {@code | group} or {@code | agg} stage assigns {@code _agg0} to a count aggregate, a
     * downstream {@code | format} or {@code | match} that refers back to that aggregate emits {@code _agg0} (bare),
     * which corresponds to {@code FieldAccess(CurrentValue(), "_agg0")}.
     *
     * @param aggName the alias name assigned to the aggregate (e.g., {@code "_agg0"}).
     * @return a {@code FieldAccess} expression that the Serializer renders as the bare alias.
     */
    public static Expr translateAggregateReference(String aggName) {
        return new Expr.FieldAccess(new Expr.CurrentValue(), aggName);
    }

    /**
     * Translate {@code extract(UNIT FROM dateExpr)} to a MQLv2 date-part function call, e.g. {@code extract(YEAR FROM
     * orderDate)} → {@code year(orderDate)}.
     *
     * @param fn the function call AST node (function name must be {@code "extract"}).
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    public static Expr translateExtract(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var args = fn.getArguments();
        if (args.size() != 2 || !(args.get(0) instanceof ExtractUnit eu)) {
            throw new FeatureNotSupportedException("Unsupported extract() form");
        }
        if (!(args.get(1) instanceof Expression dateExpr)) {
            throw new FeatureNotSupportedException("Non-expression date argument in extract()");
        }
        return new Expr.FunctionCall(mqlv2ExtractName(eu.getUnit()), List.of(translateExpression(dateExpr, ctx)));
    }

    private static String mqlv2ExtractName(TemporalUnit unit) {
        return switch (unit) {
            case YEAR -> "year";
            case MONTH -> "month";
            case DAY, DAY_OF_MONTH -> "dayOfMonth";
            case DAY_OF_YEAR -> "dayOfYear";
            case DAY_OF_WEEK -> "dayOfWeek";
            case HOUR -> "hour";
            case MINUTE -> "minute";
            case SECOND -> "second";
            default -> throw new FeatureNotSupportedException("Unsupported extract() unit: " + unit);
        };
    }

    /**
     * Convert a Hibernate {@link Predicate} to a driver-mqlv2 {@link Expr}. Phase C scope: leaf-shape predicates
     * (Comparison, Nullness, BooleanExpression, Grouped, SelfRendering array functions), conjunctions/disjunctions
     * (Junction), and negation (NegatedPredicate). InList, InSubQuery, Exists deferred to subsequent tasks.
     *
     * <p>Junction with N predicates builds a left-associative {@link Expr.BinaryOp} chain: {@code (p1 op p2 op p3)}
     * becomes {@code ((p1 op p2) op p3)}. The Serializer adds inner parentheses around each binary sub-expression,
     * producing {@code ((p1 op p2) op p3)} — identical output to the old hand-rolled emission for 2-predicate cases; 3+
     * predicate cases get extra nesting (D6 drift).
     *
     * <p>NegatedPredicate emits {@code UnaryOp(NOT, inner)}. The Serializer's UnaryOp branch wraps an {@code Any}
     * argument in extra parens, preserving correct precedence for negated array-function predicates.
     *
     * @param p the Hibernate predicate to translate.
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     * @return the equivalent driver-mqlv2 {@link Expr} subtree.
     */
    public static Expr translatePredicate(Predicate p, Mqlv2TranslationContext ctx) {
        if (p instanceof Junction j) {
            BinaryOpType op = j.getNature() == Junction.Nature.CONJUNCTION ? BinaryOpType.AND : BinaryOpType.OR;
            var preds = j.getPredicates();
            if (preds.isEmpty()) {
                // Identity element: AND-empty = true, OR-empty = false (SQL semantics).
                return new Expr.ValueLit(new Value.VBool(op == BinaryOpType.AND));
            }
            if (preds.size() == 1) {
                return translatePredicate(preds.get(0), ctx);
            }
            Expr acc = translatePredicate(preds.get(0), ctx);
            for (int i = 1; i < preds.size(); i++) {
                acc = new Expr.BinaryOp(op, acc, translatePredicate(preds.get(i), ctx));
            }
            return acc;
        }
        if (p instanceof NegatedPredicate np) {
            return new Expr.UnaryOp(UnaryOpType.NOT, translatePredicate(np.getPredicate(), ctx));
        }
        if (p instanceof ComparisonPredicate cp) {
            return new Expr.BinaryOp(
                    translateComparisonOp(cp.getOperator()),
                    translateExpression(cp.getLeftHandExpression(), ctx),
                    translateExpression(cp.getRightHandExpression(), ctx));
        }
        if (p instanceof GroupedPredicate gp) {
            return translatePredicate(gp.getSubPredicate(), ctx);
        }
        if (p instanceof NullnessPredicate np) {
            var inner = translateExpression(np.getExpression(), ctx);
            String fn = np.isNegated() ? "notNullish" : "isNullish";
            return new Expr.FunctionCall(fn, List.of(inner));
        }
        if (p instanceof BooleanExpressionPredicate bp) {
            return new Expr.BinaryOp(
                    BinaryOpType.EQ,
                    translateExpression(bp.getExpression(), ctx),
                    new Expr.ValueLit(new Value.VBool(!bp.isNegated())));
        }
        if (p instanceof InListPredicate ilp) {
            var exprs = ilp.getListExpressions();
            boolean negated = ilp.isNegated();
            BinaryOpType cmpOp = negated ? BinaryOpType.NE : BinaryOpType.EQ;
            BinaryOpType logicOp = negated ? BinaryOpType.AND : BinaryOpType.OR;
            if (exprs.isEmpty()) {
                return new Expr.ValueLit(new Value.VBool(negated));
            }
            Expr testIr = translateExpression(ilp.getTestExpression(), ctx);
            Expr acc = new Expr.BinaryOp(cmpOp, testIr, translateExpression(exprs.get(0), ctx));
            for (int i = 1; i < exprs.size(); i++) {
                Expr cmp = new Expr.BinaryOp(cmpOp, testIr, translateExpression(exprs.get(i), ctx));
                acc = new Expr.BinaryOp(logicOp, acc, cmp);
            }
            return acc;
        }
        if (p instanceof SelfRenderingPredicate srp
                && srp.getSelfRenderingExpression() instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
            var name = fn.getFunctionName();
            if ("array_contains".equals(name) || "array_contains_nullable".equals(name)) {
                return translateArrayContains(fn, ctx);
            }
            if ("array_intersects".equals(name) || "array_intersects_nullable".equals(name)) {
                return translateArrayIntersects(fn, ctx);
            }
        }
        throw new FeatureNotSupportedException(
                "Unsupported predicate in IR translation: " + p.getClass().getSimpleName());
    }

    private static BinaryOpType translateComparisonOp(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> BinaryOpType.EQ;
            case NOT_EQUAL -> BinaryOpType.NE;
            case LESS_THAN -> BinaryOpType.LT;
            case LESS_THAN_OR_EQUAL -> BinaryOpType.LE;
            case GREATER_THAN -> BinaryOpType.GT;
            case GREATER_THAN_OR_EQUAL -> BinaryOpType.GE;
            default ->
                throw new FeatureNotSupportedException("Unsupported comparison operator in IR translation: " + op);
        };
    }

    // ---- Stage-level translation helpers (Phase D2) ----

    /**
     * Return value of {@link #translateFormat}: the updated pipeline {@link Stage} and the ordered list of field names
     * for the JdbcOperationQuerySelect result-set mapping.
     *
     * @hidden
     */
    public record FormatTranslation(Stage stage, List<String> fieldNames) {}

    /**
     * Build a {@link Stage.FromStageSimple} or {@link Stage.FromStageNested} for the primary table reference of the
     * FROM clause root.
     *
     * <ul>
     *   <li>No joins: {@code from $collName} — uses {@link Stage.FromStageSimple}.
     *   <li>Has joins: {@code from alias=$collName} — uses {@link Stage.FromStageNested} so that subsequent join stages
     *       can address fields by qualifier.
     * </ul>
     *
     * @param ntr the primary {@link NamedTableReference} of the FROM root.
     * @param hasJoins true iff the query has at least one non-unnest join (determines which from-stage shape to emit).
     * @return the from {@link Stage} node.
     */
    public static Stage translateFromStage(NamedTableReference ntr, boolean hasJoins) {
        var collName = ntr.getTableExpression();
        if (!hasJoins) {
            return new Stage.FromStageSimple(new Expr.VarRef(collName));
        }
        var alias = ntr.getIdentificationVariable();
        return new Stage.FromStageNested(List.of(Map.entry(alias, (Expr) new Expr.VarRef(collName))));
    }

    /**
     * Append a {@link Stage.MatchStage} if the {@code QuerySpec} has a WHERE clause; otherwise return {@code prev}
     * unchanged.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec whose WHERE clause is consulted.
     * @param ctx translation context for column-reference qualifier rules.
     * @return the updated stage (either a new {@link Stage.MatchStage} or {@code prev} unchanged).
     */
    public static Stage translateMatch(Stage prev, QuerySpec qs, Mqlv2TranslationContext ctx) {
        var pred = qs.getWhereClauseRestrictions();
        if (pred == null || pred.isEmpty()) {
            return prev;
        }
        return new Stage.MatchStage(prev, translatePredicate(pred, ctx));
    }

    /**
     * Append a {@link Stage.SortStage} if the {@code QuerySpec} has ORDER BY specifications; otherwise return
     * {@code prev} unchanged.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec whose sort specifications are consulted.
     * @param ctx translation context for column-reference qualifier rules.
     * @return the updated stage (either a new {@link Stage.SortStage} or {@code prev} unchanged).
     */
    public static Stage translateSort(Stage prev, QuerySpec qs, Mqlv2TranslationContext ctx) {
        if (!qs.hasSortSpecifications()) {
            return prev;
        }
        var specs = qs.getSortSpecifications();
        var sortSpecs = new ArrayList<SortSpec>(specs.size());
        for (var spec : specs) {
            var expr = translateExpression(spec.getSortExpression(), ctx);
            var dir = spec.getSortOrder() == org.hibernate.query.SortDirection.DESCENDING
                    ? SortDirection.DESC
                    : SortDirection.ASC;
            sortSpecs.add(new SortSpec(expr, dir));
        }
        return new Stage.SortStage(prev, sortSpecs);
    }

    /**
     * Append a {@link Stage.LimitStage} if the {@code QuerySpec} has a FETCH clause or the {@code QueryOptions} specify
     * a max-rows limit; otherwise return {@code prev} unchanged.
     *
     * <p>When a dynamic max-rows limit is active the caller-supplied {@code onSetMaxResults} callback is invoked so the
     * translator can allocate the {@link org.hibernate.sql.ast.tree.expression.JdbcParameter} binder and record the
     * {@code limitJdbcParameter} field before this method reads the resulting binder index from the context.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec (consulted for the HQL FETCH clause).
     * @param queryOptions dynamic query options; may be {@code null} for sub-queries.
     * @param ctx translation context (parameter binders are appended via {@code allocateParameter}).
     * @param onSetMaxResults callback invoked when {@code queryOptions.getLimit().getMaxRows()} is non-null; the
     *     callback must push the limit binder to {@code ctx}'s parameter list before returning.
     * @return the updated stage (either a new {@link Stage.LimitStage} or {@code prev} unchanged).
     */
    public static Stage translateLimit(
            Stage prev,
            QuerySpec qs,
            @Nullable QueryOptions queryOptions,
            Mqlv2TranslationContext ctx,
            Runnable onSetMaxResults) {
        if (queryOptions != null) {
            var limit = queryOptions.getLimit();
            if (limit != null && limit.getFirstRow() != null) {
                throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
            }
            if (limit != null && limit.getMaxRows() != null) {
                onSetMaxResults.run();
                // The callback pushed the LimitJdbcParameter binder as the last entry; its index
                // is therefore (size - 1).
                int idx = ctx.parameterBinders().size() - 1;
                return new Stage.LimitStage(prev, new Expr.VarRef("p" + idx));
            }
        }
        // Fall through to the HQL FETCH clause.
        var fetchExpr = qs.getFetchClauseExpression();
        if (fetchExpr == null) {
            return prev;
        }
        if (qs.getOffsetClauseExpression() != null) {
            throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
        }
        return new Stage.LimitStage(prev, translateExpression(fetchExpr, ctx));
    }

    /**
     * Build a {@link Stage.FormatStage} wrapping {@code prev} and return a {@link FormatTranslation} that also carries
     * the ordered field-name list for the result-set mapping.
     *
     * <p>Field-name assignment mirrors
     * {@link com.mongodb.hibernate.internal.translate.Mqlv2SelectTranslator#appendFormat}:
     *
     * <ul>
     *   <li>Aggregate selection (aggName non-null): synthetic {@code _fN} key; value is a bare field reference to the
     *       aggregate alias.
     *   <li>{@link ColumnReference} / {@link BasicValuedPathInterpretation}: the column expression becomes both the
     *       document key and the field name. After a group stage, the value is also a bare field reference to the group
     *       key.
     *   <li>All other expressions: a synthetic {@code _fN} name is assigned.
     * </ul>
     *
     * @param prev the pipeline stage to wrap.
     * @param selectClause the SELECT clause whose non-virtual selections drive the projection.
     * @param aggNames per-selection aggregate alias list, or {@code null} when no group/agg stage precedes the format
     *     stage. When non-null, each entry is the assigned {@code _aggN} alias for aggregate selections and
     *     {@code null} for non-aggregate selections.
     * @param ctx translation context.
     * @return a {@link FormatTranslation} containing the updated stage and the field names.
     */
    public static FormatTranslation translateFormat(
            Stage prev,
            SelectClause selectClause,
            @Nullable List<@Nullable String> aggNames,
            Mqlv2TranslationContext ctx) {
        var fieldNames = new ArrayList<String>();
        var fields = new ArrayList<Map.Entry<Expr, Expr>>();
        var syntheticIdx = 0;
        // Collect the set of array field paths from unnest joins. These columns require re-wrapping
        // in a single-element array in the format stage so Hibernate's getArray() call succeeds.
        // (The | unwind body maps the array field to the element document for match purposes;
        // the format stage then wraps it back so the result set delivers an ARRAY.)
        var unnestArrayFields = new LinkedHashSet<>(ctx.unnestAliasToFieldPath().values());

        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();

        for (var i = 0; i < selections.size(); i++) {
            var selExpr = selections.get(i).getExpression();
            var aggName = aggNames != null ? aggNames.get(i) : null;
            String key;
            Expr valueExpr;
            if (aggName != null) {
                // Aggregate selection: synthetic key, bare alias reference as value.
                key = "_f" + syntheticIdx++;
                valueExpr = translateAggregateReference(aggName);
            } else if (selExpr instanceof ColumnReference cr) {
                key = cr.getColumnExpression();
                // After a group stage the key field is addressed directly; otherwise translate normally.
                var rawValue = aggNames != null
                        ? (Expr) new Expr.FieldAccess(new Expr.CurrentValue(), key)
                        : translateExpression(selExpr, ctx);
                // Re-wrap unnest array fields.
                valueExpr = (!unnestArrayFields.isEmpty() && unnestArrayFields.contains(key))
                        ? new Expr.ArrayConstructor(List.of(rawValue))
                        : rawValue;
            } else if (selExpr instanceof BasicValuedPathInterpretation<?> bvpi) {
                key = bvpi.getColumnReference().getColumnExpression();
                var rawValue = aggNames != null
                        ? (Expr) new Expr.FieldAccess(new Expr.CurrentValue(), key)
                        : translateExpression(selExpr, ctx);
                valueExpr = (!unnestArrayFields.isEmpty() && unnestArrayFields.contains(key))
                        ? new Expr.ArrayConstructor(List.of(rawValue))
                        : rawValue;
            } else {
                key = "_f" + syntheticIdx++;
                valueExpr = translateExpression(selExpr, ctx);
            }
            fieldNames.add(key);
            fields.add(Map.entry(new Expr.ValueLit(new Value.VString(key)), valueExpr));
        }

        var docConstructor = new Expr.DocumentConstructor(fields);
        return new FormatTranslation(new Stage.FormatStage(prev, docConstructor), fieldNames);
    }

    // ---- Group / Having / Scalar-agg stage translation helpers (Phase D4) ----

    /**
     * Build a {@link Stage.GroupStage} for a GROUP BY query. Mirrors the hand-rolled {@code appendGroup} method in
     * {@code Mqlv2SelectTranslator}.
     *
     * <p>Group keys are named after the column expression (via {@link #simpleColumnName}). Aggregate keys come from (a)
     * non-null entries in {@code aggNames} (SELECT-position aggregates) and (b) HAVING-only aggregates in
     * {@code havingOnlyAggs}.
     *
     * @param prev the pipeline stage to wrap.
     * @param groupByExprs the GROUP BY expressions.
     * @param selectClause the SELECT clause.
     * @param aggNames per-selection aggregate alias list (same contract as in {@link #translateFormat(Stage,
     *     SelectClause, List, Mqlv2TranslationContext)}).
     * @param havingOnlyAggs aggregates referenced only in HAVING (not in SELECT), keyed by their assigned alias.
     * @param ctx translation context.
     * @return a new {@link Stage.GroupStage}.
     */
    public static Stage translateGroup(
            Stage prev,
            List<Expression> groupByExprs,
            SelectClause selectClause,
            List<@Nullable String> aggNames,
            Map<String, SelfRenderingFunctionSqlAstExpression<?>> havingOnlyAggs,
            Mqlv2TranslationContext ctx) {
        // Build groupKeys: one Assignment per group-by expression, named after the column.
        var groupKeys = new ArrayList<Assignment>(groupByExprs.size());
        for (var ge : groupByExprs) {
            String name = simpleColumnName(ge);
            groupKeys.add(new Assignment(List.of(name), translateExpression(ge, ctx)));
        }

        // Build aggKeys: one Assignment per aggregate function.
        var aggKeys = new ArrayList<Assignment>();
        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            var fn =
                    (SelfRenderingFunctionSqlAstExpression<?>) selections.get(i).getExpression();
            aggKeys.add(new Assignment(List.of(aggName), translateAggregateCall(fn, ctx)));
        }
        // HAVING-only aggregates also go into the group stage.
        for (var entry : havingOnlyAggs.entrySet()) {
            aggKeys.add(new Assignment(List.of(entry.getKey()), translateAggregateCall(entry.getValue(), ctx)));
        }

        return new Stage.GroupStage(prev, groupKeys, aggKeys);
    }

    /**
     * Build a {@link Stage.AggStage} for a scalar-aggregate query (aggregates without GROUP BY). Mirrors the
     * hand-rolled {@code appendScalarAgg} method in {@code Mqlv2SelectTranslator}.
     *
     * @param prev the pipeline stage to wrap.
     * @param selectClause the SELECT clause.
     * @param aggNames per-selection aggregate alias list.
     * @param ctx translation context.
     * @return a new {@link Stage.AggStage}.
     */
    public static Stage translateScalarAgg(
            Stage prev, SelectClause selectClause, List<@Nullable String> aggNames, Mqlv2TranslationContext ctx) {
        var fields = new ArrayList<Map.Entry<Expr, Expr>>();
        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            var fn =
                    (SelfRenderingFunctionSqlAstExpression<?>) selections.get(i).getExpression();
            fields.add(
                    Map.entry((Expr) new Expr.ValueLit(new Value.VString(aggName)), translateAggregateCall(fn, ctx)));
        }
        return new Stage.AggStage(prev, new Expr.DocumentConstructor(fields));
    }

    /**
     * Append a {@link Stage.MatchStage} for the HAVING clause if it is non-empty; otherwise return {@code prev}
     * unchanged. Mirrors the hand-rolled {@code appendHaving} method.
     *
     * <p>The HAVING predicate may reference aggregate aliases (e.g., {@code _agg0}) via the {@code aggSignatureToName}
     * map in {@code ctx}; these are resolved by {@link #translateExpression} via {@link #translateAggregateReference}.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec whose HAVING clause is consulted.
     * @param ctx translation context (must have {@code aggSignatureToName} populated).
     * @return the updated stage.
     */
    public static Stage translateHaving(Stage prev, QuerySpec qs, Mqlv2TranslationContext ctx) {
        var having = qs.getHavingClauseRestrictions();
        if (having == null || having.isEmpty()) return prev;
        return new Stage.MatchStage(prev, translatePredicate(having, ctx));
    }

    /**
     * Translate an aggregate function call (not a reference to an already-assigned alias) into its driver-mqlv2
     * {@link Expr.FunctionCall} form. Mirrors the hand-rolled {@code appendAggFunctionText}.
     *
     * <p>Argument mapping:
     *
     * <ul>
     *   <li>{@code count(*)} / {@code count(entity)} / no argument → {@code count($)} (current value).
     *   <li>Regular field argument → {@code fn($->field)} using {@link Expr.ArrowOp}.
     * </ul>
     *
     * @param fn the aggregate function call AST node.
     * @param ctx translation context (for qualifier resolution on the argument column).
     * @return the equivalent driver-mqlv2 {@link Expr.FunctionCall} node.
     */
    private static Expr translateAggregateCall(
            SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var name = fn.getFunctionName();
        var args = fn.getArguments();
        Expr argExpr;
        if (args.isEmpty() || args.get(0) instanceof Star || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            // count(*) / count(entity) → count($)
            argExpr = new Expr.CurrentValue();
        } else {
            if (!(args.get(0) instanceof Expression firstArg)) {
                throw new FeatureNotSupportedException(name + "() requires an expression argument in MQLv2");
            }
            argExpr = translateAggFieldRef(firstArg, ctx);
        }
        return new Expr.FunctionCall(name, List.of(argExpr));
    }

    /**
     * Translate an aggregate function field argument into the {@code $->field} arrow-op form. Mirrors the hand-rolled
     * {@code appendAggFieldRef} in {@code Mqlv2SelectTranslator}.
     *
     * <p>Rule: the arrow-op always starts from {@code $} (current value). In a join context the qualifier is preserved:
     * {@code qualifier->column}. For unnest aliases the mapped field path is used: {@code arrayFieldPath->column}.
     * Without joins: bare column.
     *
     * @param expr the aggregate argument expression (column reference or path interpretation).
     * @param ctx translation context.
     * @return an {@link Expr.ArrowOp} rooted at the current value, resolving the field.
     */
    private static Expr translateAggFieldRef(Expression expr, Mqlv2TranslationContext ctx) {
        ColumnReference cr;
        if (expr instanceof ColumnReference c) {
            cr = c;
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            cr = bvpi.getColumnReference();
        } else {
            throw new FeatureNotSupportedException("Expected column reference in aggregate argument; got: "
                    + expr.getClass().getSimpleName());
        }
        var qualifier = cr.getQualifier();
        String field;
        if (qualifier != null && ctx.unnestAliasToFieldPath().containsKey(qualifier)) {
            field = ctx.unnestAliasToFieldPath().get(qualifier) + "->" + cr.getColumnExpression();
        } else if (ctx.hasJoins() && qualifier != null && !qualifier.isEmpty()) {
            field = qualifier + "->" + cr.getColumnExpression();
        } else {
            field = cr.getColumnExpression();
        }
        // Build the arrow-op chain: $->a->b for "a->b" paths (unnest), or plain $->column.
        Expr target = new Expr.CurrentValue();
        int arrowIdx;
        int start = 0;
        while ((arrowIdx = field.indexOf("->", start)) != -1) {
            target = new Expr.ArrowOp(target, field.substring(start, arrowIdx));
            start = arrowIdx + 2;
        }
        target = new Expr.ArrowOp(target, field.substring(start));
        return target;
    }

    /**
     * Extracts the simple column expression from a GROUP BY key expression, used to name the group key in the
     * {@link Stage.GroupStage}. Mirrors the hand-rolled {@code simpleColumnName}.
     */
    private static String simpleColumnName(Expression expr) {
        if (expr instanceof ColumnReference cr) {
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return bvpi.getColumnReference().getColumnExpression();
        } else {
            throw new FeatureNotSupportedException("Expected simple column reference for GROUP BY key; got: "
                    + expr.getClass().getSimpleName());
        }
    }
}
