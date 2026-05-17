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
import com.mongodb.mqlv2.ast.Value;
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
}
