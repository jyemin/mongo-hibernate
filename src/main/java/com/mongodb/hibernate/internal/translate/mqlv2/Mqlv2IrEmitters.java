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
import org.hibernate.query.sqm.BinaryArithmeticOperator;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;

/**
 * Translates Hibernate SQL AST {@link Expression} nodes into driver-mqlv2 {@link Expr} nodes. Currently scoped to the
 * cases used by Group-A array function arguments (literals, column references, JDBC parameters, arithmetic). Extended
 * in subsequent migration phases.
 *
 * <p>Stateless on purpose: when the translator's qualifier rules (joins, unnest aliases) need to participate in
 * column-reference rendering, they will arrive as method parameters or via a small context object — never as instance
 * state on this class.
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
     * @param parameterIndex single-element mutable array carrying the next {@code $pN} index for {@link JdbcParameter}
     *     encounters. Caller initializes to the count of already-bound parameters; the helper advances it by one per
     *     parameter seen.
     * @return the equivalent driver-mqlv2 {@link Expr} subtree.
     */
    public static Expr translateExpression(Expression e, int[] parameterIndex) {
        if (e instanceof BasicValuedPathInterpretation<?> bvpi) {
            return translateExpression(bvpi.getColumnReference(), parameterIndex);
        }
        if (e instanceof ColumnReference cr) {
            // Phase A scope: simple column references (no joins, no unnest aliases). Qualifier
            // rules will be folded in by Phase C when predicate dispatch moves to IR.
            return new Expr.FieldAccess(new Expr.CurrentValue(), cr.getColumnExpression());
        }
        if (e instanceof QueryLiteral<?> ql) {
            return new Expr.ValueLit(translateLiteralValue(ql.getLiteralValue()));
        }
        if (e instanceof SqmParameterInterpretation spi) {
            return translateExpression(spi.getResolvedExpression(), parameterIndex);
        }
        if (e instanceof JdbcParameter) {
            return new Expr.VarRef("p" + parameterIndex[0]++);
        }
        if (e instanceof BinaryArithmeticExpression bae) {
            return new Expr.BinaryOp(
                    translateArithmeticOp(bae.getOperator()),
                    translateExpression(bae.getLeftHandOperand(), parameterIndex),
                    translateExpression(bae.getRightHandOperand(), parameterIndex));
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
}
