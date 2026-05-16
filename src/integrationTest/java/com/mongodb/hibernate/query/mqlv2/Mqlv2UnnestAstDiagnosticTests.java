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

package com.mongodb.hibernate.query.mqlv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hibernate.cfg.AvailableSettings.DIALECT;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Struct;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.jspecify.annotations.Nullable;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Diagnostic-only test that locks in the load-bearing AST-shape assumption Phases 2-4 of the elemMatch design depend
 * on: HQL {@code join lateral unnest(o.array) a on 1=1} produces a {@link FunctionTableReference} whose function
 * descriptor identifies as {@code "unnest"}.
 *
 * <p>Prerequisites the upgrade put in place:
 *
 * <ul>
 *   <li>{@code unnest} is registered in {@link com.mongodb.hibernate.dialect.MongoDialect#initializeFunctionRegistry}
 *       so Hibernate's HQL semantic analyzer recognizes it.
 *   <li>{@link CapturingMqlv2Dialect} installs {@link CapturingMqlv2TranslatorFactory} so the
 *       {@link org.hibernate.sql.ast.tree.select.SelectStatement} can be inspected before the v2 translator throws on
 *       the unsupported {@code FunctionTableReference}.
 * </ul>
 *
 * <p>Confirmed for both array shapes:
 *
 * <ul>
 *   <li>Scalar array ({@code int[]}) — {@code join lateral unnest(i.tags)} produces a
 *       {@code FunctionTableReference("unnest")} as the joined group's primary table reference.
 *   <li>Struct array ({@code Tag[]} with {@code @Embeddable @Struct(name="Tag")} on Tag) — same AST shape.
 * </ul>
 *
 * <p>Findings worth recording for Phase 3 design:
 *
 * <ul>
 *   <li>The plural-attribute-join sugar ({@code from O o join o.array a}, without explicit {@code lateral unnest}) does
 *       <em>not</em> fire for {@code int[]} (the join is optimized away) or {@code @ElementCollection
 *       Collection<Integer>} (which produces a {@link org.hibernate.sql.ast.tree.from.NamedTableReference} for a
 *       separate element-collection table). Phase 3 must determine which storage-side mapping actually triggers the
 *       {@code FunctionTableReference} path under the sugar — likely a Hibernate-default array property — or accept
 *       that the elemMatch HQL surface requires the explicit {@code lateral unnest(...)} form.
 *   <li>{@code FunctionTableReference.toString()} does not include the function name; use
 *       {@link FunctionTableReference#getFunctionExpression()} and
 *       {@link org.hibernate.sql.ast.tree.expression.FunctionExpression#getFunctionName()} instead.
 * </ul>
 *
 * <p>This test will be removed or rewritten as a positive feature assertion once Phase 3 lands.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestAstDiagnosticTests.Item.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(settings = @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.CapturingMqlv2Dialect"))
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestAstDiagnosticTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void resetCapture() {
        CapturingMqlv2TranslatorFactory.reset();
    }

    // -----------------------------------------------------------------------
    // Phase 3 — plural-attribute-join sugar form (`from O o join lateral unnest(o.array) a`).
    // Confirmed shape: outer FROM root has a TableGroupJoin to FunctionTableReference("unnest").
    // -----------------------------------------------------------------------

    @Test
    void scalarArrayJoinDesugarsToUnnestFunctionTableReference() {
        var captured = captureFromHql("select i from Item i join lateral unnest(i.tags) t on 1=1");
        assertOuterUnnestJoin(captured);
    }

    @Test
    void structArrayJoinDesugarsToUnnestFunctionTableReference() {
        var captured = captureFromHql("select i from Item i join lateral unnest(i.structTags) t on 1=1");
        assertOuterUnnestJoin(captured);
    }

    @Test
    void structArrayJoinWithSimplePredicateProducesUnnest() {
        var captured = captureFromHql(
                "select i from Item i join lateral unnest(i.structTags) t on 1=1 where t.name = 'x'");
        assertOuterUnnestJoin(captured);
    }

    @Test
    void structArrayJoinWithMultiPredicateProducesUnnest() {
        var captured = captureFromHql(
                "select i from Item i join lateral unnest(i.structTags) t on 1=1 where t.name = 'x' and t.weight > 5");
        assertOuterUnnestJoin(captured);
    }

    @Test
    void structArrayJoinProjectsAliasFields() {
        var captured =
                captureFromHqlForTuple("select t.name from Item i join lateral unnest(i.structTags) t on 1=1");
        assertOuterUnnestJoin(captured);
    }

    // -----------------------------------------------------------------------
    // Phase 2 — EXISTS-over-unnest (the canonical $elemMatch shape).
    // Expected: outer WHERE has an ExistsPredicate whose inner subquery's FROM root is a
    // FunctionTableReference("unnest").
    // -----------------------------------------------------------------------

    @Test
    void structArrayExistsOverImplicitCollectionPath_simplePredicate() {
        // The natural Hibernate idiom: `from i.structTags t` directly inside the EXISTS
        // subquery. Inner FROM root is FunctionTableReference("unnest").
        var captured = captureFromHql(
                "select i from Item i where exists (select 1 from i.structTags t where t.name = 'x')");
        assertExistsOverUnnest(captured);
    }

    @Test
    void structArrayExistsOverImplicitCollectionPath_multiPredicate() {
        var captured = captureFromHql("select i from Item i where exists ("
                + "select 1 from i.structTags t where t.name = 'x' and t.weight > 5)");
        assertExistsOverUnnest(captured);
    }

    @Test
    void structArrayExistsOverCorrelatedJoinInSubquery() {
        // Alternative idiom: explicit JOIN in the EXISTS subquery, correlated to outer.
        // Inner FROM is Item with TableGroupJoin → FunctionTableReference("unnest").
        var captured = captureFromHql("select i from Item i where exists ("
                + "select 1 from Item i2 join i2.structTags t where i2 = i and t.name = 'x')");
        assertExistsOverCorrelatedJoinUnnest(captured);
    }

    @Test
    void structArrayExistsOverLateralUnnestForm_doesNotParse() {
        // The explicit `lateral unnest` form is NOT valid HQL grammar inside EXISTS subqueries.
        // Documenting the limitation so Phase 2's plan picks the implicit form above.
        var thrown = catchThrowable(() -> captureFromHql("select i from Item i where exists ("
                + "select 1 from lateral unnest(i.structTags) t where t.name = 'x')"));
        // The try in captureFromHql swallows; we just confirm no AST was captured.
        var captured = CapturingMqlv2TranslatorFactory.takeLastCaptured();
        assertThat(captured)
                .as("`lateral unnest` inside EXISTS should not parse; suppressed: %s", thrown)
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Phase 4 — scalar-subquery count over unnest.
    // Expected: outer SELECT contains a scalar SelectStatement whose FROM has a
    // FunctionTableReference("unnest").
    // -----------------------------------------------------------------------

    @Test
    void scalarSubqueryCount_implicitCollectionPath() {
        // Implicit collection-valued path form for the scalar subquery (Phase 4 shape).
        var captured = captureFromHqlForTuple("select i.id, "
                + "(select count(*) from i.structTags t where t.weight > 5) "
                + "from Item i");
        assertScalarSubqueryWithUnnestFrom(captured);
    }

    @Test
    void scalarSubqueryCount_lateralUnnestForm_doesNotParse() {
        // Documenting that the `lateral unnest` form does not parse in scalar SELECT subqueries.
        var thrown = catchThrowable(() -> captureFromHqlForTuple("select i.id, "
                + "(select count(*) from lateral unnest(i.structTags) t where t.weight > 5) "
                + "from Item i"));
        var captured = CapturingMqlv2TranslatorFactory.takeLastCaptured();
        assertThat(captured)
                .as("`lateral unnest` inside scalar subquery should not parse; suppressed: %s", thrown)
                .isNull();
    }

    // -----------------------------------------------------------------------
    // The plural-attribute join SUGAR form (`from O o join o.array a`, no explicit
    // `lateral unnest`). Spec hoped this would work; results split by storage shape.
    // -----------------------------------------------------------------------

    @Test
    void structArrayJoinSugarForm_producesUnnest() {
        // ✅ Struct arrays: the sugar form produces the SAME AST shape as the explicit
        // `lateral unnest` form (TableGroupJoin → FunctionTableReference("unnest")).
        // This means Phase 3 can use the idiomatic Hibernate JOIN syntax.
        var captured = captureFromHql("select i from Item i join i.structTags t where t.name = 'x'");
        assertOuterUnnestJoin(captured);
    }

    @Test
    void scalarArrayJoinSugarForm_triggersHibernateAssertion() {
        // ❌ Scalar arrays (int[]): the sugar form triggers a Hibernate-internal AssertionError
        // (SqmMappingModelHelper.resolveSqmPath). Hibernate cannot resolve the unnest result
        // type for a basic array. Users must use the explicit `lateral unnest(...)` form for
        // scalar arrays, or accept this limitation.
        var captured = captureFromHql("select i from Item i join i.tags t where t > 0");
        assertThat(captured)
                .as("sugar form for int[] should not capture an AST (Hibernate AssertionError)")
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Triggers translation of {@code hql} against {@code Item.class}, swallows the expected
     * translation failure, and returns the captured {@link SelectStatement} (or {@code null}
     * if HQL parsing / SQM-to-SQL failed before the translator was invoked).
     */
    private @Nullable SelectStatement captureFromHql(String hql) {
        return captureFromHql(hql, Item.class);
    }

    /** Like {@link #captureFromHql(String)} but for tuple-returning queries. */
    private @Nullable SelectStatement captureFromHqlForTuple(String hql) {
        return captureFromHql(hql, Object[].class);
    }

    private @Nullable SelectStatement captureFromHql(String hql, Class<?> resultType) {
        var thrown = catchThrowable(() ->
                sessionFactoryScope.inSession(session ->
                        session.createQuery(hql, resultType).getResultList()));
        var captured = CapturingMqlv2TranslatorFactory.takeLastCaptured();
        if (captured == null && thrown != null) {
            System.out.println(
                    ">>> [" + hql + "] no SelectStatement captured. Top-level exception: "
                            + thrown.getClass().getName() + ": " + thrown.getMessage());
            var cause = thrown.getCause();
            while (cause != null && cause != cause.getCause()) {
                System.out.println(
                        ">>>   caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }
        return captured;
    }

    /** Asserts: outer FROM root has exactly one TableGroupJoin → FunctionTableReference("unnest"). */
    private static void assertOuterUnnestJoin(@Nullable SelectStatement captured) {
        assertThat(captured).as("no SelectStatement captured").isNotNull();
        var roots = captured.getQueryPart().getFirstQuerySpec().getFromClause().getRoots();
        assertThat(roots).hasSize(1);
        var joins = roots.get(0).getTableGroupJoins();
        assertThat(joins).as("expected one TableGroupJoin on the outer FROM root").hasSize(1);
        var primaryRef = joins.get(0).getJoinedGroup().getPrimaryTableReference();
        assertThat(primaryRef)
                .as("outer-FROM join target should be a FunctionTableReference")
                .isInstanceOf(FunctionTableReference.class);
        assertThat(((FunctionTableReference) primaryRef).getFunctionExpression().getFunctionName())
                .isEqualTo("unnest");
    }

    /**
     * Asserts: outer WHERE contains an ExistsPredicate whose inner subquery's first FROM root
     * has a FunctionTableReference("unnest") as its primary table reference.
     */
    private static void assertExistsOverUnnest(@Nullable SelectStatement captured) {
        assertThat(captured).as("no SelectStatement captured").isNotNull();
        var outerSpec = captured.getQueryPart().getFirstQuerySpec();
        var existsPredicates = new java.util.ArrayList<ExistsPredicate>();
        collectExistsPredicates(outerSpec.getWhereClauseRestrictions(), existsPredicates);
        assertThat(existsPredicates).as("expected an ExistsPredicate in outer WHERE").hasSize(1);
        var innerSpec = existsPredicates.get(0).getExpression().getQueryPart().getFirstQuerySpec();
        var innerRoots = innerSpec.getFromClause().getRoots();
        assertThat(innerRoots).as("expected one FROM root in EXISTS subquery").hasSize(1);
        var primaryRef = innerRoots.get(0).getPrimaryTableReference();
        assertThat(primaryRef)
                .as("EXISTS subquery FROM root should be a FunctionTableReference")
                .isInstanceOf(FunctionTableReference.class);
        assertThat(((FunctionTableReference) primaryRef).getFunctionExpression().getFunctionName())
                .isEqualTo("unnest");
    }

    /**
     * Asserts: outer WHERE contains an ExistsPredicate whose inner subquery's first FROM root
     * is a NamedTableReference (Item) with exactly one TableGroupJoin to a
     * FunctionTableReference("unnest").
     */
    private static void assertExistsOverCorrelatedJoinUnnest(@Nullable SelectStatement captured) {
        assertThat(captured).as("no SelectStatement captured").isNotNull();
        var outerSpec = captured.getQueryPart().getFirstQuerySpec();
        var existsPredicates = new java.util.ArrayList<ExistsPredicate>();
        collectExistsPredicates(outerSpec.getWhereClauseRestrictions(), existsPredicates);
        assertThat(existsPredicates).as("expected an ExistsPredicate in outer WHERE").hasSize(1);
        var innerSpec = existsPredicates.get(0).getExpression().getQueryPart().getFirstQuerySpec();
        var innerRoots = innerSpec.getFromClause().getRoots();
        assertThat(innerRoots).hasSize(1);
        var innerJoins = innerRoots.get(0).getTableGroupJoins();
        assertThat(innerJoins).as("expected one TableGroupJoin in EXISTS subquery").hasSize(1);
        var primaryRef = innerJoins.get(0).getJoinedGroup().getPrimaryTableReference();
        assertThat(primaryRef)
                .as("EXISTS subquery join target should be a FunctionTableReference")
                .isInstanceOf(FunctionTableReference.class);
        assertThat(((FunctionTableReference) primaryRef).getFunctionExpression().getFunctionName())
                .isEqualTo("unnest");
    }

    /**
     * Asserts: outer SELECT contains a scalar SelectStatement expression whose FROM has a
     * FunctionTableReference("unnest").
     */
    private static void assertScalarSubqueryWithUnnestFrom(@Nullable SelectStatement captured) {
        assertThat(captured).as("no SelectStatement captured").isNotNull();
        var outerSelections = captured.getQueryPart().getFirstQuerySpec().getSelectClause().getSqlSelections();
        var subqueryUnnestFound = outerSelections.stream()
                .map(s -> s.getExpression())
                .filter(e -> e instanceof SelectStatement)
                .map(e -> (SelectStatement) e)
                .map(ss -> ss.getQueryPart().getFirstQuerySpec().getFromClause().getRoots())
                .filter(roots -> !roots.isEmpty())
                .map(roots -> roots.get(0).getPrimaryTableReference())
                .anyMatch(ref -> ref instanceof FunctionTableReference ftr
                        && "unnest".equals(ftr.getFunctionExpression().getFunctionName()));
        assertThat(subqueryUnnestFound)
                .as("expected a scalar subquery in SELECT whose FROM is FunctionTableReference(unnest)")
                .isTrue();
    }

    private static void collectExistsPredicates(@Nullable Predicate predicate, java.util.List<ExistsPredicate> out) {
        if (predicate == null) return;
        if (predicate instanceof ExistsPredicate ep) out.add(ep);
        if (predicate instanceof Junction j) {
            j.getPredicates().forEach(p -> collectExistsPredicates(p, out));
        }
        if (predicate instanceof GroupedPredicate gp) {
            collectExistsPredicates(gp.getSubPredicate(), out);
        }
        if (predicate instanceof NegatedPredicate np) {
            collectExistsPredicates(np.getPredicate(), out);
        }
    }

    private static void printAstSummary(String label, @Nullable SelectStatement captured) {
        if (captured == null) {
            System.out.println(">>> " + label + ": no SelectStatement captured");
            return;
        }
        printQuerySpec(label, "outer", captured.getQueryPart().getFirstQuerySpec());
    }

    private static void printQuerySpec(
            String label, String layer, org.hibernate.sql.ast.tree.select.QuerySpec spec) {
        var roots = spec.getFromClause().getRoots();
        System.out.println(">>> " + label + " [" + layer + "]: from roots=" + roots.size());
        for (var root : roots) {
            var primaryRef = root.getPrimaryTableReference();
            String refDesc = primaryRef.getClass().getSimpleName();
            if (primaryRef instanceof FunctionTableReference ftr) {
                refDesc += "(" + ftr.getFunctionExpression().getFunctionName() + ")";
            }
            System.out.println(">>>   root primaryRef=" + refDesc
                    + " tgjs=" + root.getTableGroupJoins().size());
            for (var tgj : root.getTableGroupJoins()) {
                var jref = tgj.getJoinedGroup().getPrimaryTableReference();
                String jrefDesc = jref.getClass().getSimpleName();
                if (jref instanceof FunctionTableReference ftr) {
                    jrefDesc += "(" + ftr.getFunctionExpression().getFunctionName() + ")";
                }
                System.out.println(">>>     tgj joinedGroup.primaryRef=" + jrefDesc);
            }
        }
        // Walk EXISTS predicates in WHERE clause and recurse into their subqueries.
        var existsPredicates = new java.util.ArrayList<ExistsPredicate>();
        collectExistsPredicates(spec.getWhereClauseRestrictions(), existsPredicates);
        for (var ep : existsPredicates) {
            printQuerySpec(label, layer + ".exists", ep.getExpression().getQueryPart().getFirstQuerySpec());
        }
    }

    @Entity(name = "Item")
    @Table(name = "items")
    static class Item {
        @Id
        int id;

        int[] tags;

        Tag[] structTags;

        Item() {}
    }

    @Embeddable
    @Struct(name = "Tag")
    static class Tag {
        String name;
        int weight;

        Tag() {}
    }
}
