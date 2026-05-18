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

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.mqlv2.Serializer;
import com.mongodb.mqlv2.ast.BinaryOpType;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.Stage;
import com.mongodb.mqlv2.ast.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Mqlv2IrEmitters}.
 *
 * <p>These tests lock the Serializer's text for the leaf {@link Expr} shapes used in IR translation. The full
 * Hibernate-AST → IR dispatch path is exercised through the integration tests in
 * {@code Mqlv2ArrayFunctionsIntegrationTests} once the per-function migrations (Tasks 3-7) wire {@link Mqlv2IrEmitters}
 * into {@link com.mongodb.hibernate.internal.translate.Mqlv2SelectTranslator}.
 */
class Mqlv2IrEmittersTest {

    private final Serializer s = new Serializer();

    @Test
    void literalString() {
        assertThat(s.serialize(new Expr.ValueLit(new Value.VString("WIDGET-1"))))
                .isEqualTo("\"WIDGET-1\"");
    }

    @Test
    void literalInt() {
        assertThat(s.serialize(new Expr.ValueLit(new Value.VInt(42)))).isEqualTo("42");
    }

    @Test
    void literalNull() {
        assertThat(s.serialize(new Expr.ValueLit(new Value.VNull()))).isEqualTo("null");
    }

    @Test
    void literalBool() {
        assertThat(s.serialize(new Expr.ValueLit(new Value.VBool(true)))).isEqualTo("true");
    }

    @Test
    void fieldAccess() {
        assertThat(s.serialize(new Expr.FieldAccess(new Expr.CurrentValue(), "scores")))
                .isEqualTo("scores");
    }

    @Test
    void parameterRef() {
        assertThat(s.serialize(new Expr.VarRef("p0"))).isEqualTo("$p0");
    }

    @Test
    void arithmeticSubtract() {
        assertThat(s.serialize(new Expr.BinaryOp(
                        BinaryOpType.SUB, new Expr.ValueLit(new Value.VInt(1)), new Expr.ValueLit(new Value.VInt(1)))))
                .isEqualTo("(1 - 1)");
    }

    // ---- wrapAsSubPipeline / wrapAsSubPipelineWithHead (Phase D5) ----

    /** A minimal inner-pipeline stage: {@code from $orders | match (status == "NEW")} */
    private static Stage simpleInnerStage() {
        Stage from = new Stage.FromStageSimple(new Expr.VarRef("orders"));
        return new Stage.MatchStage(
                from,
                new Expr.BinaryOp(
                        BinaryOpType.EQ,
                        new Expr.FieldAccess(new Expr.CurrentValue(), "status"),
                        new Expr.ValueLit(new Value.VString("NEW"))));
    }

    /**
     * No correlated bindings → bare {@code SubPipelineExpr} (parenthesised pipeline). Mirrors hand-rolled
     * {@code wrapWithLet(innerPipeline, emptyMap)} producing {@code "(from $orders | match (...))"}.
     */
    @Test
    void wrapAsSubPipeline_noBindings_yieldsBareParens() {
        var stage = simpleInnerStage();
        var expr = Mqlv2IrEmitters.wrapAsSubPipeline(stage, Map.of(), false);
        assertThat(s.serialize(expr)).isEqualTo("(from $orders | match (status == \"NEW\"))");
    }

    /**
     * One correlated binding without joins → unqualified field in the let clause. Mirrors hand-rolled
     * {@code wrapWithLet("from $orders | match ...", {"c.id" → "$__v0"})} with {@code hasJoins=false} producing
     * {@code "let $__v0 = id in (from $orders | match (...))"}.
     */
    @Test
    void wrapAsSubPipeline_oneBinding_simpleQuery_yieldsLetWithUnqualifiedField() {
        var stage = simpleInnerStage();
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("c.id", "__v0");
        var expr = Mqlv2IrEmitters.wrapAsSubPipeline(stage, bindings, false);
        assertThat(s.serialize(expr))
                .isEqualTo("let $__v0 = id in (from $orders | match (status == \"NEW\"))");
    }

    /**
     * One correlated binding with joins → qualified {@code qualifier.column} in the let clause. Mirrors hand-rolled
     * {@code wrapWithLet(...)} with {@code hasJoins=true} producing
     * {@code "let $__v0 = c.id in (from $orders | match (...))"}.
     */
    @Test
    void wrapAsSubPipeline_oneBinding_joinQuery_yieldsLetWithQualifiedField() {
        var stage = simpleInnerStage();
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("c.id", "__v0");
        var expr = Mqlv2IrEmitters.wrapAsSubPipeline(stage, bindings, true);
        assertThat(s.serialize(expr))
                .isEqualTo("let $__v0 = c.id in (from $orders | match (status == \"NEW\"))");
    }

    /**
     * Two correlated bindings → two let entries in insertion order. Mirrors multi-binding EXISTS patterns where the
     * inner pipeline references multiple outer-query columns.
     */
    @Test
    void wrapAsSubPipeline_twoBindings_yieldsMultiLet() {
        var stage = simpleInnerStage();
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("c.id", "__v0");
        bindings.put("c.region", "__v1");
        var expr = Mqlv2IrEmitters.wrapAsSubPipeline(stage, bindings, false);
        assertThat(s.serialize(expr))
                .isEqualTo("let $__v0 = id, $__v1 = region in (from $orders | match (status == \"NEW\"))");
    }

    /**
     * Head binding (IN/ANY/ALL pattern) with no extra correlated bindings. Mirrors hand-rolled
     * {@code wrapWithLet(innerPipeline, headVarName, headText, emptyMap)} producing
     * {@code "let $__v0 = $p0 in (from $orders | match (...))"}.
     */
    @Test
    void wrapAsSubPipelineWithHead_noExtraBindings_yieldsHeadOnlyLet() {
        var stage = simpleInnerStage();
        var expr = Mqlv2IrEmitters.wrapAsSubPipelineWithHead(
                stage, "__v0", new Expr.VarRef("p0"), Map.of(), false);
        assertThat(s.serialize(expr))
                .isEqualTo("let $__v0 = $p0 in (from $orders | match (status == \"NEW\"))");
    }

    /**
     * Head binding plus one correlated binding. Mirrors the full IN/ANY/ALL+correlated pattern:
     * {@code "let $__v0 = $p0, $__v1 = id in (from $orders | match (...))"}.
     */
    @Test
    void wrapAsSubPipelineWithHead_withExtraBinding_yieldsHeadPlusCorrelatedLet() {
        var stage = simpleInnerStage();
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("c.id", "__v1");
        var expr = Mqlv2IrEmitters.wrapAsSubPipelineWithHead(
                stage, "__v0", new Expr.VarRef("p0"), bindings, false);
        assertThat(s.serialize(expr))
                .isEqualTo("let $__v0 = $p0, $__v1 = id in (from $orders | match (status == \"NEW\"))");
    }
}
