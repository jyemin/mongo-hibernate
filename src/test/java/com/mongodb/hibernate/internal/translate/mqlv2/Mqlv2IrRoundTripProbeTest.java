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
import com.mongodb.mqlv2.ast.SortDirection;
import com.mongodb.mqlv2.ast.SortSpec;
import com.mongodb.mqlv2.ast.Stage;
import com.mongodb.mqlv2.ast.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * IR migration probe (see {@code docs/superpowers/specs/2026-05-17-mqlv2-translator-ir-migration-design.md}).
 *
 * <p>For each of five distinct AST shapes, construct the driver-mqlv2 AST that would replace the current
 * hand-rolled MQLv2 string emission, then run the Serializer and lock its output as the "after migration"
 * expectation. The comment above each assertion records the current translator's emission for the same
 * shape, so the diff is visible at a glance.
 *
 * <p>Migration go/no-go: all surfaced diffs are stylistic / semantically equivalent — no Serializer bugs.
 * The IR migration is viable; showcase verification tests will need their expected strings updated for
 * three categories of drift, summarized at the end of this file.
 */
class Mqlv2IrRoundTripProbeTest {

    private final Serializer serializer = new Serializer();

    private static Expr i(long n) {
        return new Expr.ValueLit(new Value.VInt(n));
    }

    private static Expr s(String v) {
        return new Expr.ValueLit(new Value.VString(v));
    }

    private static Expr field(String name) {
        return new Expr.FieldAccess(new Expr.CurrentValue(), name);
    }

    private static Expr collection(String name) {
        return new Expr.VarRef(name);
    }

    private static Expr binop(BinaryOpType op, Expr l, Expr r) {
        return new Expr.BinaryOp(op, l, r);
    }

    private static Expr fn(String name, Expr... args) {
        return new Expr.FunctionCall(name, List.of(args));
    }

    /** Trailing format stage: alphabetical projection by field name (matches today's translator). */
    private static Stage withInventoryFormat(Stage prev) {
        return new Stage.FormatStage(
                prev,
                new Expr.DocumentConstructor(List.of(
                        Map.entry(s("_id"), field("_id")),
                        Map.entry(s("boxedScores"), field("boxedScores")),
                        Map.entry(s("scores"), field("scores")))));
    }

    @Test
    void arrayLength() {
        // Today's translator emits:
        //   from $inventory | match (count(scores) > 2)
        //       | format {_id: _id, boxedScores: boxedScores, scores: scores}
        Stage ast = withInventoryFormat(new Stage.MatchStage(
                new Stage.FromStageSimple(collection("inventory")),
                binop(BinaryOpType.GT, fn("count", field("scores")), i(2))));

        assertThat(serializer.serialize(ast))
                .isEqualTo("from $inventory | match (count(scores) > 2)"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        // Byte-equivalent for this shape — was previously a quoted-keys diff; resolved by the
        // driver-mqlv2 bareword-keys tweak (commit 892852d6fa).
    }

    @Test
    void arrayGet() {
        // Today's translator emits:
        //   from $inventory | match (scores[(1) - 1] == 10) | format ...
        Stage ast = withInventoryFormat(new Stage.MatchStage(
                new Stage.FromStageSimple(collection("inventory")),
                binop(
                        BinaryOpType.EQ,
                        new Expr.ArrayIndex(field("scores"), binop(BinaryOpType.SUB, i(1), i(1))),
                        i(10))));

        assertThat(serializer.serialize(ast))
                .isEqualTo("from $inventory | match (scores[(1 - 1)] == 10)"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        // Diff: array-index argument parens move outside the binop: `(1) - 1` → `(1 - 1)`.
        // The old shape is a hand-rolled artifact; the new shape is the Serializer's
        // canonical paren-the-whole-binop rule. Same precedence, same eval.
    }

    @Test
    void arrayContains() {
        // Today's translator emits:
        //   from $inventory | match (scores any ($ == 30)) | format ...
        Stage ast = withInventoryFormat(new Stage.MatchStage(
                new Stage.FromStageSimple(collection("inventory")),
                new Expr.Any(field("scores"), binop(BinaryOpType.EQ, new Expr.CurrentValue(), i(30)))));

        assertThat(serializer.serialize(ast))
                .isEqualTo("from $inventory | match scores any (($ == 30))"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        // Two paren diffs:
        // 1. `match` stage drops the hand-rolled outer parens around the predicate.
        // 2. `any` body has DOUBLE parens — one from `any (…)` syntax, one from BinaryOp's
        //    canonical paren-the-whole-binop rule. Today's translator special-cases the
        //    inside-any case to skip the outer set. Semantically identical.
    }

    @Test
    void arrayIntersectsWithConstructor() {
        // Today's translator emits:
        //   from $inventory | match (scores any (let $__x = $ in [30, 99] any ($ == $__x))) | format ...
        Stage ast = withInventoryFormat(new Stage.MatchStage(
                new Stage.FromStageSimple(collection("inventory")),
                new Expr.Any(
                        field("scores"),
                        new Expr.LetExpr(
                                List.of(Map.entry("__x", new Expr.CurrentValue())),
                                new Expr.Any(
                                        new Expr.ArrayConstructor(List.of(i(30), i(99))),
                                        binop(BinaryOpType.EQ, new Expr.CurrentValue(), new Expr.VarRef("__x")))))));

        assertThat(serializer.serialize(ast))
                .isEqualTo("from $inventory | match scores any (let $__x = $ in [30, 99] any (($ == $__x)))"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        // Same paren diffs as arrayContains, applied to the inner any. Otherwise identical.
    }

    @Test
    void sortLimitExercisesNewLimitStageExpr() {
        // Locks the new LimitStage(Expr) change (commit 6e1f35e792 on driver-mqlv2) against today's
        // emission. Byte-equivalent — no diff.
        Stage ast = new Stage.LimitStage(
                new Stage.SortStage(
                        new Stage.FromStageSimple(collection("carts")),
                        List.of(new SortSpec(field("_id"), SortDirection.ASC))),
                i(3));

        assertThat(serializer.serialize(ast)).isEqualTo("from $carts | sort _id | limit 3");
    }

    /*
     * Drift summary (for the migration plan):
     *
     * D1. ~~format-stage document keys are quoted.~~ Resolved upstream in driver-mqlv2 commit
     *     892852d6fa: DocumentConstructor now emits bareword keys when the key matches the MQLv2
     *     identifier shape ([A-Za-z_][A-Za-z0-9_]*), falling back to the quoted form for keys
     *     with special characters.
     *
     * D2. binop expressions inside ArrayIndex are paren-wrapped as `[(a OP b)]` not `[(a) OP b]`.
     *     Trivially the cleaner rule. Update showcase strings.
     *
     * D3. `match` stage no longer wraps the predicate in outer parens. Inner BinaryOp inside an `Any`
     *     gets its own parens, producing `any (($ == x))` rather than `any ($ == x)`. Both are pure
     *     parenthesization; MQLv2 grammar parses them identically.
     *
     * No semantic bugs surfaced. Migration is viable; updates to Mqlv2ShowcaseVerificationTests'
     * expected strings will be mechanical.
     */
}
