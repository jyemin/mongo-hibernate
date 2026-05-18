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
import com.mongodb.mqlv2.ast.BinaryOpType;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.Stage;
import com.mongodb.mqlv2.ast.UnaryOpType;
import com.mongodb.mqlv2.ast.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.query.common.TemporalUnit;
import org.hibernate.query.sqm.BinaryArithmeticOperator;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.sql.ast.tree.expression.Any;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Every;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.ExtractUnit;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.expression.UnparsedNumericLiteral;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.predicate.BooleanExpressionPredicate;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.InSubQueryPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.NullnessPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;

/**
 * Translates Hibernate SQL AST {@link Expression} and {@link Predicate} nodes into driver-mqlv2 {@link Expr} nodes.
 * Covers literals, column references, JDBC parameters, arithmetic, all function-call arms, all predicate shapes
 * (including subquery-referencing ones: EXISTS, IN subquery, ANY/ALL, scalar subqueries, and unnest variants), and the
 * inner-pipeline helpers they depend on ({@link #translateInnerQuerySpec}, {@link #wrapAsSubPipeline},
 * {@link #wrapAsSubPipelineWithHead}).
 *
 * <p>Stateless on purpose: qualifier rules (joins, unnest aliases) are carried by the {@link Mqlv2TranslationContext}
 * passed to each method. {@link Mqlv2StageEmitter} calls {@link #translateExpression} and {@link #translatePredicate}
 * for expression/predicate arguments embedded in stage construction; the dependency is strictly one-way.
 *
 * @hidden
 */
public final class Mqlv2ExpressionEmitter {

    private Mqlv2ExpressionEmitter() {}

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
            if (isAggregateFunction(fn)) {
                var alias = ctx.aggregateAliases().get(fn);
                if (alias == null) {
                    throw new FeatureNotSupportedException(
                            "Aggregate function in expression not found in SELECT: " + fnName + "()");
                }
                return new Expr.FieldAccess(new Expr.CurrentValue(), alias);
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
        if (e instanceof SelectStatement ss) {
            if (!ctx.hasOuterScope()) {
                throw new FeatureNotSupportedException("SelectStatement in expression requires subquery-aware context");
            }
            var innerSpec = ss.getQueryPart().getFirstQuerySpec();
            var innerRoot = innerSpec.getFromClause().getRoots().get(0);
            if (innerRoot.getPrimaryTableReference() instanceof FunctionTableReference) {
                return translateUnnestScalarSubquery(ss, ctx);
            }
            return translateScalarSubquery(ss, ctx);
        }
        throw new FeatureNotSupportedException(
                "Unsupported expression in IR translation: " + e.getClass().getSimpleName());
    }

    /**
     * Convert a Hibernate {@link Predicate} to a driver-mqlv2 {@link Expr}. Handles leaf-shape predicates (Comparison,
     * Nullness, BooleanExpression, Grouped, SelfRendering array functions), conjunctions/disjunctions (Junction),
     * negation (NegatedPredicate), InList, InSubQuery, and Exists predicates.
     *
     * <p>Junction with N predicates builds a left-associative {@link Expr.BinaryOp} chain: {@code (p1 op p2 op p3)}
     * becomes {@code ((p1 op p2) op p3)}. The Serializer adds inner parentheses around each binary sub-expression,
     * so 3+ predicate conjunctions/disjunctions produce extra nesting compared to an equivalent hand-flattened form.
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
        // Check for ANY/ALL subquery before the generic ComparisonPredicate arm, since Any/Every
        // are not translatable as plain expressions.
        if (p instanceof ComparisonPredicate cp && cp.getRightHandExpression() instanceof Any anyExpr
                && ctx.hasOuterScope()) {
            return translateAnyAllSubquery(cp, anyExpr.getSubquery(), false, ctx);
        }
        if (p instanceof ComparisonPredicate cp && cp.getRightHandExpression() instanceof Every everyExpr
                && ctx.hasOuterScope()) {
            return translateAnyAllSubquery(cp, everyExpr.getSubquery(), true, ctx);
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
        // Remaining subquery-based predicates (InSubQueryPredicate, non-unnest ExistsPredicate):
        // require subquery support to be enabled in the context.
        if (ctx.hasOuterScope()) {
            if (p instanceof InSubQueryPredicate isp) {
                return translateInSubQuery(isp, ctx);
            }
            if (p instanceof ExistsPredicate ep) {
                var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
                var innerRoot = innerSpec.getFromClause().getRoots().get(0);
                if (innerRoot.getPrimaryTableReference() instanceof FunctionTableReference) {
                    return translateUnnestExists(ep, ctx);
                }
                return translateExists(ep, ctx);
            }
        }
        throw new FeatureNotSupportedException(
                "Unsupported predicate in IR translation: " + p.getClass().getSimpleName());
    }

    /**
     * Wrap a translated inner-subquery {@link Stage} as a {@link Expr.SubPipelineExpr}, optionally enclosed in a
     * {@link Expr.LetExpr} when there are correlated outer-query bindings.
     *
     * <ul>
     *   <li>No bindings → {@code (innerPipeline)} (a bare {@link Expr.SubPipelineExpr}).
     *   <li>With bindings → {@code let $__v0 = field0[, $__v1 = field1, ...] in (innerPipeline)}.
     * </ul>
     *
     * <p>The binding <em>values</em> (the field expressions for outer-correlated columns) are built using
     * {@link #outerCorrelatedFieldExpr(String, boolean)}: unqualified column name in simple scans, full
     * {@code qualifier.column} in join contexts.
     *
     * @param stage the inner pipeline stage (produced by {@link #translateInnerQuerySpec}).
     * @param correlatedBindings map of {@code "qualifier.column" → "__vN"} collected during inner-spec translation
     *     (from {@link Mqlv2TranslationContext#getCorrelatedBindings()}). May be empty.
     * @param outerHasJoins whether the outer query has joins; determines whether field references in let bindings are
     *     qualified ({@code qualifier.column}) or unqualified ({@code column}).
     * @return the wrapped {@link Expr}: either a bare {@link Expr.SubPipelineExpr} or a {@link Expr.LetExpr}.
     */
    public static Expr wrapAsSubPipeline(
            Stage stage, Map<String, String> correlatedBindings, boolean outerHasJoins) {
        Expr pipelineExpr = new Expr.SubPipelineExpr(stage);
        if (correlatedBindings.isEmpty()) {
            return pipelineExpr;
        }
        var bindings = new ArrayList<Map.Entry<String, Expr>>(correlatedBindings.size());
        for (var entry : correlatedBindings.entrySet()) {
            // entry.getKey() is "qualifier.column"; entry.getValue() is "__vN" (without $).
            // The binding name in LetExpr is "__vN" (without $); the Serializer adds the "$" prefix.
            var fieldExpr = outerCorrelatedFieldExpr(entry.getKey(), outerHasJoins);
            bindings.add(Map.entry(entry.getValue(), fieldExpr));
        }
        return new Expr.LetExpr(bindings, pipelineExpr);
    }

    /**
     * Overload for the IN/ANY/ALL pattern that prepends a head binding (the test expression variable) before the
     * correlated outer-column bindings. Produces:
     * {@code let $headVarName = headValueExpr[, $__vN = field, ...] in (innerPipeline)}.
     *
     * @param stage the inner pipeline stage.
     * @param headVarName the {@code __vN} name (without {@code $}) for the head binding.
     * @param headValueExpr the expression that the head variable is bound to (the test expression from the outer
     *     query).
     * @param correlatedBindings additional outer-correlated bindings, as in
     *     {@link #wrapAsSubPipeline(Stage, Map, boolean)}.
     * @param outerHasJoins whether the outer query has joins.
     * @return the wrapped {@link Expr.LetExpr}.
     */
    public static Expr wrapAsSubPipelineWithHead(
            Stage stage,
            String headVarName,
            Expr headValueExpr,
            Map<String, String> correlatedBindings,
            boolean outerHasJoins) {
        Expr pipelineExpr = new Expr.SubPipelineExpr(stage);
        var bindings = new ArrayList<Map.Entry<String, Expr>>(1 + correlatedBindings.size());
        bindings.add(Map.entry(headVarName, headValueExpr));
        for (var entry : correlatedBindings.entrySet()) {
            var fieldExpr = outerCorrelatedFieldExpr(entry.getKey(), outerHasJoins);
            bindings.add(Map.entry(entry.getValue(), fieldExpr));
        }
        return new Expr.LetExpr(bindings, pipelineExpr);
    }

    private static final Set<String> AGGREGATE_FUNCTION_NAMES = Set.of("count", "sum", "avg", "min", "max");

    private static boolean isAggregateFunction(SelfRenderingFunctionSqlAstExpression<?> fn) {
        return AGGREGATE_FUNCTION_NAMES.contains(fn.getFunctionName());
    }

    static Value translateLiteralValue(Object v) {
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
        // Inside an any/every body or unnest-source pipeline: qualified refs to the array-element alias
        // resolve to $.column (the current value is the array element itself).
        if (qualifier != null && ctx.isCurrentValueAlias(qualifier)) {
            return new Expr.FieldAccess(new Expr.CurrentValue(), column);
        }
        // Outer-correlated reference inside an inner subquery: bind to a $__vN variable.
        if (qualifier != null && ctx.isOuterCorrelated(qualifier)) {
            return new Expr.VarRef(ctx.allocateCorrelatedVar(qualifier, column));
        }
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
    static Expr buildFieldChain(String dotPath) {
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
    private static Expr translateArrayGet(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
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
    private static Expr translateArrayConstructor(
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
    private static Expr translateArrayLength(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
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
    private static Expr translateArrayIntersects(
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
    private static Expr translateArrayContains(
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
    static Expr translateAggregateReference(String aggName) {
        return new Expr.FieldAccess(new Expr.CurrentValue(), aggName);
    }

    /**
     * Translate {@code extract(UNIT FROM dateExpr)} to a MQLv2 date-part function call, e.g. {@code extract(YEAR FROM
     * orderDate)} → {@code year(orderDate)}.
     *
     * @param fn the function call AST node (function name must be {@code "extract"}).
     * @param ctx translation context — see {@link #translateExpression(Expression, Mqlv2TranslationContext)}.
     */
    private static Expr translateExtract(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
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

    // ---- Shared helpers (package-private so Mqlv2StageEmitter can call them) ----

    /**
     * Extracts the simple column expression from a GROUP BY key expression or projected column expression.
     * Used to name group keys in the {@link Stage.GroupStage} and projected columns in IN/ANY/ALL subqueries.
     */
    static String simpleColumnName(Expression expr) {
        if (expr instanceof ColumnReference cr) {
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return bvpi.getColumnReference().getColumnExpression();
        } else {
            throw new FeatureNotSupportedException("Expected simple column reference for GROUP BY key; got: "
                    + expr.getClass().getSimpleName());
        }
    }

    /**
     * Extracts the unnest function table's array-path expression (the single argument to {@code unnest()}).
     * Package-private so {@link Mqlv2StageEmitter} can use it when building unwind join stages.
     */
    static Expression extractUnnestArrayPath(FunctionTableReference ftr) {
        var args = ftr.getFunctionExpression().getArguments();
        if (args.size() != 1) {
            throw new FeatureNotSupportedException("unnest() requires exactly one argument; got " + args.size());
        }
        var arg = args.get(0);
        if (arg instanceof ColumnReference || arg instanceof BasicValuedPathInterpretation<?>) {
            return (Expression) arg;
        }
        throw new FeatureNotSupportedException("unnest() argument must be a path expression on an outer entity; got: "
                + arg.getClass().getSimpleName());
    }

    // ---- Inner-subquery IR translation helpers ----

    /**
     * Translate a simple inner-subquery {@link QuerySpec} into a {@link Stage} pipeline. The inner spec must have
     * exactly one FROM root with no joins.
     *
     * <p>Outer-correlated column references (i.e., column references whose qualifier is in the outer-query scope) are
     * translated as {@code VarRef("__vN")} rather than field accesses. The allocated bindings are recorded in the
     * {@link Mqlv2TranslationContext} (via {@link Mqlv2TranslationContext#allocateCorrelatedVar}) and can be retrieved
     * by the caller via {@link Mqlv2TranslationContext#getCorrelatedBindings()} to build the {@code LetExpr} wrapper
     * using {@link #wrapAsSubPipeline}.
     *
     * <p>The {@code innerCtx} must have been created with {@link Mqlv2TranslationContext#forInnerSubquery}.
     *
     * @param innerSpec the inner query spec to translate (FROM + optional WHERE).
     * @param innerCtx translation context for the inner scope, carrying the outer-qualifier set and the mutable
     *     correlated-bindings map.
     * @return the translated {@link Stage} for the inner pipeline.
     */
    private static Stage translateInnerQuerySpec(QuerySpec innerSpec, Mqlv2TranslationContext innerCtx) {
        var roots = innerSpec.getFromClause().getRoots();
        if (roots.size() != 1 || !roots.get(0).getTableGroupJoins().isEmpty()) {
            throw new FeatureNotSupportedException(
                    "Subquery with joins or multiple FROM roots is not supported in MQLv2");
        }
        var ntr = (NamedTableReference) roots.get(0).getPrimaryTableReference();
        Stage s = new Stage.FromStageSimple(new Expr.VarRef(ntr.getTableExpression()));
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            s = new Stage.MatchStage(s, translatePredicate(where, innerCtx));
        }
        return s;
    }

    /**
     * Build the field-access {@link Expr} for an outer correlated column binding value.
     *
     * <ul>
     *   <li>No joins: unqualified column name — {@code FieldAccess($, "column")}.
     *   <li>Has joins: qualified path — {@code FieldAccess(FieldAccess($, "qualifier"), "column")}.
     * </ul>
     *
     * @param qualifiedKey {@code "qualifier.column"} key from the correlated-bindings map.
     * @param outerHasJoins whether the outer query has joins.
     * @return the field-access expression for the binding value.
     */
    private static Expr outerCorrelatedFieldExpr(String qualifiedKey, boolean outerHasJoins) {
        if (outerHasJoins) {
            return buildFieldChain(qualifiedKey);
        }
        var dotIdx = qualifiedKey.indexOf('.');
        var column = dotIdx >= 0 ? qualifiedKey.substring(dotIdx + 1) : qualifiedKey;
        return new Expr.FieldAccess(new Expr.CurrentValue(), column);
    }

    // ---- Subquery-predicate IR translation helpers ----

    /**
     * Translate an {@link ExistsPredicate} (non-unnest form) into a driver-mqlv2 {@link Expr}.
     *
     * <ul>
     *   <li>Normal: {@code (count(<subpipeline>) > 0)}
     *   <li>Negated (via {@code ExistsPredicate.isNegated()}): {@code (count(<subpipeline>) == 0)}
     * </ul>
     *
     * <p>Correlated outer-column references inside the inner subquery are captured as {@code $__vN} let bindings and
     * wrapped via {@link #wrapAsSubPipeline}.
     *
     * @param ep the EXISTS predicate.
     * @param outerCtx the outer translation context (provides parameter binders, hasJoins state, and outer qualifiers).
     * @return the translated {@link Expr}.
     */
    private static Expr translateExists(ExistsPredicate ep, Mqlv2TranslationContext outerCtx) {
        var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
        var correlatedBindings = new LinkedHashMap<String, String>();
        var innerCtx = outerCtx.forInnerSubquery(correlatedBindings);
        Stage innerStage = translateInnerQuerySpec(innerSpec, innerCtx);
        Expr wrapped = wrapAsSubPipeline(innerStage, correlatedBindings, outerCtx.hasJoins());
        Expr countExpr = new Expr.FunctionCall("count", List.of(wrapped));
        return new Expr.BinaryOp(
                ep.isNegated() ? BinaryOpType.EQ : BinaryOpType.GT,
                countExpr,
                new Expr.ValueLit(new Value.VInt(0)));
    }

    /**
     * Translate an {@link InSubQueryPredicate} into a driver-mqlv2 {@link Expr}.
     *
     * <ul>
     *   <li>Normal: {@code (count(let $__vN = testExpr in (<inner> | match (projectedCol == $__vN))) > 0)}
     *   <li>Negated: same with {@code == 0}.
     * </ul>
     *
     * <p>The head binding binds the outer test expression to {@code $__vN}; an additional {@code | match} stage tests
     * whether the projected inner column equals that variable. Correlated outer-column references (beyond the head
     * binding) are captured as further {@code $__vN} let bindings.
     *
     * @param isp the IN-subquery predicate.
     * @param outerCtx the outer translation context (provides parameter binders, hasJoins state, and outer qualifiers).
     * @return the translated {@link Expr}.
     */
    private static Expr translateInSubQuery(InSubQueryPredicate isp, Mqlv2TranslationContext outerCtx) {
        var innerSpec = isp.getSubQuery().getQueryPart().getFirstQuerySpec();
        var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .findFirst()
                .orElseThrow(() -> new FeatureNotSupportedException("IN subquery must project at least one column"))
                .getExpression();
        var projectedColName = simpleColumnName(projectedExpr);

        // Allocate the head variable BEFORE creating the inner context so that variable ordering
        // matches the hand-rolled path ($__v0 for the head, then higher indices for outer refs).
        String headVarName = "__v" + outerCtx.nextCorrelatedVar().getAsInt();

        var correlatedBindings = new LinkedHashMap<String, String>();
        var innerCtx = outerCtx.forInnerSubquery(correlatedBindings);
        Stage innerStage = translateInnerQuerySpec(innerSpec, innerCtx);

        // Append match stage: projectedCol == $headVar
        innerStage = new Stage.MatchStage(
                innerStage,
                new Expr.BinaryOp(
                        BinaryOpType.EQ,
                        new Expr.FieldAccess(new Expr.CurrentValue(), projectedColName),
                        new Expr.VarRef(headVarName)));

        Expr testExpr = translateExpression(isp.getTestExpression(), outerCtx);
        Expr wrapped = wrapAsSubPipelineWithHead(innerStage, headVarName, testExpr, correlatedBindings,
                outerCtx.hasJoins());
        Expr countExpr = new Expr.FunctionCall("count", List.of(wrapped));
        return new Expr.BinaryOp(
                isp.isNegated() ? BinaryOpType.EQ : BinaryOpType.GT,
                countExpr,
                new Expr.ValueLit(new Value.VInt(0)));
    }

    /**
     * Translate {@code x op ANY(subquery)} or {@code x op ALL(subquery)} into a driver-mqlv2 {@link Expr}.
     *
     * <ul>
     *   <li>ANY ({@code isAll=false}): {@code (count(let $__vN = x in (<inner> | match (col swapOp $__vN))) > 0)}
     *   <li>ALL ({@code isAll=true}): same shape but with the inverse swap operator and {@code == 0}.
     * </ul>
     *
     * <p>The inner {@code | match} stage places the projected column on the left and the test variable on the right,
     * requiring operand-order swapping (and for ALL also negation) of the original operator (see
     * {@link #anyMatchOpIr}/{@link #allMatchOpIr}).
     *
     * @param cp the comparison predicate whose RHS is the {@link Any}/{@link Every} expression.
     * @param subquery the subquery carried by the ANY/ALL expression.
     * @param isAll {@code true} for ALL, {@code false} for ANY.
     * @param outerCtx the outer translation context (provides parameter binders, hasJoins state, and outer qualifiers).
     * @return the translated {@link Expr}.
     */
    private static Expr translateAnyAllSubquery(
            ComparisonPredicate cp,
            SelectStatement subquery,
            boolean isAll,
            Mqlv2TranslationContext outerCtx) {
        var innerSpec = subquery.getQueryPart().getFirstQuerySpec();
        var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .findFirst()
                .orElseThrow(
                        () -> new FeatureNotSupportedException("ANY/ALL subquery must project at least one column"))
                .getExpression();
        var projectedColName = simpleColumnName(projectedExpr);

        // Allocate head variable BEFORE creating the inner context to preserve ordering.
        String headVarName = "__v" + outerCtx.nextCorrelatedVar().getAsInt();

        var correlatedBindings = new LinkedHashMap<String, String>();
        var innerCtx = outerCtx.forInnerSubquery(correlatedBindings);
        Stage innerStage = translateInnerQuerySpec(innerSpec, innerCtx);

        // Append match stage: projectedCol matchOp $headVar (with swapped/inverted operator).
        BinaryOpType matchOp = isAll ? allMatchOpIr(cp.getOperator()) : anyMatchOpIr(cp.getOperator());
        innerStage = new Stage.MatchStage(
                innerStage,
                new Expr.BinaryOp(
                        matchOp,
                        new Expr.FieldAccess(new Expr.CurrentValue(), projectedColName),
                        new Expr.VarRef(headVarName)));

        Expr leftExpr = translateExpression(cp.getLeftHandExpression(), outerCtx);
        Expr wrapped = wrapAsSubPipelineWithHead(innerStage, headVarName, leftExpr, correlatedBindings,
                outerCtx.hasJoins());
        Expr countExpr = new Expr.FunctionCall("count", List.of(wrapped));
        // ANY: count > 0; ALL: count == 0 (no row where NOT condition holds)
        return new Expr.BinaryOp(
                isAll ? BinaryOpType.EQ : BinaryOpType.GT,
                countExpr,
                new Expr.ValueLit(new Value.VInt(0)));
    }

    /**
     * Returns the {@link BinaryOpType} for the {@code | match} stage inside an ANY subquery. Swaps operand order so
     * that {@code col op $__vN} is semantically equivalent to {@code $__vN op col} (i.e., {@code x op ANY(col)} counts
     * rows where {@code col} satisfies the predicate with {@code x}).
     */
    private static BinaryOpType anyMatchOpIr(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> BinaryOpType.EQ;
            case NOT_EQUAL -> BinaryOpType.NE;
            case LESS_THAN -> BinaryOpType.GT;
            case LESS_THAN_OR_EQUAL -> BinaryOpType.GE;
            case GREATER_THAN -> BinaryOpType.LT;
            case GREATER_THAN_OR_EQUAL -> BinaryOpType.LE;
            default ->
                throw new FeatureNotSupportedException("Unsupported comparison operator in ANY subquery: " + op);
        };
    }

    /**
     * Returns the {@link BinaryOpType} for the {@code | match} stage inside an ALL subquery. Negates and swaps operand
     * order so that ALL counts rows where the condition does NOT hold.
     */
    private static BinaryOpType allMatchOpIr(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> BinaryOpType.NE;
            case NOT_EQUAL -> BinaryOpType.EQ;
            case LESS_THAN -> BinaryOpType.LE;
            case LESS_THAN_OR_EQUAL -> BinaryOpType.LT;
            case GREATER_THAN -> BinaryOpType.GE;
            case GREATER_THAN_OR_EQUAL -> BinaryOpType.GT;
            default ->
                throw new FeatureNotSupportedException("Unsupported comparison operator in ALL subquery: " + op);
        };
    }

    // ---- Scalar-subquery expression IR translation helper ----

    /**
     * Translate a scalar {@link SelectStatement} subquery in expression position (e.g., in a SELECT clause) into a
     * driver-mqlv2 {@link Expr}. Only the non-unnest form is handled here; unnest-form subqueries (whose inner FROM is a
     * {@link FunctionTableReference}) are handled by {@link #translateUnnestScalarSubquery}.
     *
     * <p>Only {@code count()} is supported: MQLv2's {@code count(pipeline)} returns the pipeline cardinality. Other
     * aggregates have no pipeline-argument form and throw {@link FeatureNotSupportedException}.
     *
     * <p>Produces: {@code count(<subpipeline>)} or {@code count(let $__vN = field in (<subpipeline>))} when there are
     * outer-correlated column references.
     *
     * @param ss the scalar subquery statement.
     * @param outerCtx the outer translation context (provides parameter binders, hasJoins state, and outer qualifiers).
     * @return the translated {@link Expr}.
     */
    private static Expr translateScalarSubquery(SelectStatement ss, Mqlv2TranslationContext outerCtx) {
        var innerSpec = ss.getQueryPart().getFirstQuerySpec();
        var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        if (selections.size() != 1) {
            throw new FeatureNotSupportedException("Scalar subquery in SELECT must project exactly one column");
        }
        var selExpr = selections.get(0).getExpression();
        if (!(selExpr instanceof SelfRenderingFunctionSqlAstExpression<?> fn)
                || !"count".equals(fn.getFunctionName())) {
            throw new FeatureNotSupportedException(
                    "Scalar subquery in SELECT must use count(); other aggregates are not supported");
        }
        var correlatedBindings = new LinkedHashMap<String, String>();
        var innerCtx = outerCtx.forInnerSubquery(correlatedBindings);
        Stage innerStage = translateInnerQuerySpec(innerSpec, innerCtx);
        Expr wrapped = wrapAsSubPipeline(innerStage, correlatedBindings, outerCtx.hasJoins());
        return new Expr.FunctionCall("count", List.of(wrapped));
    }

    // ---- Unnest predicate / scalar-subquery IR translation helpers ----

    /**
     * Translate {@code exists (select 1 from o.array a where <body>)} (an unnest-form {@link ExistsPredicate}) into a
     * driver-mqlv2 {@link Expr.Any}: {@code arrayPath any (<body-rewritten>)}, with outer-correlated references captured
     * into a {@code let} wrapper around the {@code any} expression. Negation wraps the whole thing in {@code (not …)}.
     *
     * <p>The inner predicate body is translated in a context where the unnest alias resolves to
     * {@code FieldAccess(CurrentValue, column)} ({@code $.col}), reflecting that inside an {@code any} body the current
     * value is the array element.
     *
     * @param ep the unnest-EXISTS predicate (caller must verify the inner FROM root is a {@link FunctionTableReference}).
     * @param outerCtx the outer translation context (provides parameter binders, hasJoins state, and outer qualifiers).
     * @return the translated {@link Expr}.
     */
    private static Expr translateUnnestExists(ExistsPredicate ep, Mqlv2TranslationContext outerCtx) {
        var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();
        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = ftr.getIdentificationVariable();

        // Array path is rendered in the outer context (resolves to e.g. lineItems or o.lineItems).
        Expr arrayPathExpr = translateExpression(arrayPath, outerCtx);

        // Body is rendered in a context where:
        //   - the unnest alias resolves to $.column (current value is the array element),
        //   - outer qualifiers resolve to $__vN variables (correlated bindings).
        var correlatedBindings = new LinkedHashMap<String, String>();
        var bodyCtx = outerCtx
                .forInnerSubquery(correlatedBindings)
                .forArrayElement(Set.of(unnestAlias));
        Expr bodyExpr = translatePredicate(innerSpec.getWhereClauseRestrictions(), bodyCtx);

        Expr anyExpr = new Expr.Any(arrayPathExpr, bodyExpr);
        Expr wrapped = wrapAsSubPipelineLikeAny(anyExpr, correlatedBindings, outerCtx.hasJoins());
        if (ep.isNegated()) {
            wrapped = new Expr.UnaryOp(UnaryOpType.NOT, wrapped);
        }
        return wrapped;
    }

    /**
     * Translate {@code (select count(*) from o.array a [where <body>])} (an unnest-form scalar subquery) into a
     * driver-mqlv2 {@code count((from arrayPath [| match (<body>)]))} expression, optionally wrapped in a {@code let}
     * for outer-correlated bindings.
     *
     * <p>Only {@code count()} is supported. Other aggregates throw {@link FeatureNotSupportedException}.
     *
     * @param ss the scalar SELECT (caller must verify the inner FROM root is a {@link FunctionTableReference}).
     * @param outerCtx the outer translation context (provides parameter binders, hasJoins state, and outer qualifiers).
     * @return the translated {@link Expr}.
     */
    private static Expr translateUnnestScalarSubquery(SelectStatement ss, Mqlv2TranslationContext outerCtx) {
        var innerSpec = ss.getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();

        var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        if (selections.size() != 1) {
            throw new FeatureNotSupportedException("Scalar subquery over unnest() must project exactly one column");
        }
        var selExpr = selections.get(0).getExpression();
        if (!(selExpr instanceof SelfRenderingFunctionSqlAstExpression<?> fn)
                || !"count".equals(fn.getFunctionName())) {
            throw new FeatureNotSupportedException(
                    "Scalar subquery over unnest() must use count(); other aggregates have no "
                            + "pipeline-argument form in MQLv2 yet");
        }

        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = ftr.getIdentificationVariable();

        // Build "from arrayPath" stage.
        Expr arrayPathExpr = translateExpression(arrayPath, outerCtx);
        Stage stage = new Stage.FromStageSimple(arrayPathExpr);

        // Optional "| match (body)" stage.
        var where = innerSpec.getWhereClauseRestrictions();
        var correlatedBindings = new LinkedHashMap<String, String>();
        if (where != null && !where.isEmpty()) {
            var bodyCtx = outerCtx
                    .forInnerSubquery(correlatedBindings)
                    .forArrayElement(Set.of(unnestAlias));
            stage = new Stage.MatchStage(stage, translatePredicate(where, bodyCtx));
        }

        Expr wrapped = wrapAsSubPipeline(stage, correlatedBindings, outerCtx.hasJoins());
        return new Expr.FunctionCall("count", List.of(wrapped));
    }

    /**
     * Like {@link #wrapAsSubPipeline} but for a non-pipeline body (an {@code Expr.Any} expression): wraps in a
     * {@link Expr.LetExpr} when there are correlated bindings, otherwise returns the body unchanged.
     */
    private static Expr wrapAsSubPipelineLikeAny(
            Expr body, Map<String, String> correlatedBindings, boolean outerHasJoins) {
        if (correlatedBindings.isEmpty()) {
            return body;
        }
        var bindings = new ArrayList<Map.Entry<String, Expr>>(correlatedBindings.size());
        for (var entry : correlatedBindings.entrySet()) {
            var fieldExpr = outerCorrelatedFieldExpr(entry.getKey(), outerHasJoins);
            bindings.add(Map.entry(entry.getValue(), fieldExpr));
        }
        return new Expr.LetExpr(bindings, body);
    }
}
