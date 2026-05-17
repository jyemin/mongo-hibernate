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
import com.mongodb.mqlv2.ast.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.query.sqm.BinaryArithmeticOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.expression.UnparsedNumericLiteral;

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
            // Phase A scope: simple column references (no joins, no unnest aliases). Qualifier
            // rules will be folded in by Phase C when predicate dispatch moves to IR.
            return new Expr.FieldAccess(new Expr.CurrentValue(), cr.getColumnExpression());
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
            if ("array".equals(fnName) || "array_list".equals(fnName)) {
                return translateArrayConstructor(fn, ctx);
            }
            throw new FeatureNotSupportedException(
                    "Unsupported function in IR translation: " + fnName);
        }
        throw new FeatureNotSupportedException(
                "Unsupported expression in IR translation: " + e.getClass().getSimpleName());
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
            throw new FeatureNotSupportedException(
                    "Non-expression argument in " + fn.getFunctionName() + "()");
        }
        return new Expr.ArrayIndex(
                translateExpression(arr, ctx),
                new Expr.BinaryOp(
                        BinaryOpType.SUB,
                        translateExpression(idx, ctx),
                        new Expr.ValueLit(new Value.VInt(1))));
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
                throw new FeatureNotSupportedException(
                        "Non-expression argument in " + fn.getFunctionName() + "()");
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
            throw new FeatureNotSupportedException(
                    "Non-expression argument in " + fn.getFunctionName() + "()");
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
     * <p>D3b paren drift: the Serializer double-wraps the inner {@code any} body binop, producing
     * {@code any (($ op $__x))} vs. the old {@code any ($ op $__x)}.
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
     * predicate at the match stage, but does double-wrap the {@code any} body (producing {@code any (($ op x))} vs.
     * the old {@code (arr any ($ op x))}).
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
}
