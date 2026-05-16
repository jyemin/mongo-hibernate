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
import static org.hibernate.cfg.AvailableSettings.DIALECT;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Struct;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
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

    @Test
    void scalarArrayJoinDesugarsToUnnestFunctionTableReference() {
        runAndAssertUnnestShape("select i from Item i join lateral unnest(i.tags) t on 1=1");
    }

    @Test
    void structArrayJoinDesugarsToUnnestFunctionTableReference() {
        runAndAssertUnnestShape("select i from Item i join lateral unnest(i.structTags) t on 1=1");
    }

    /**
     * Triggers translation of {@code hql}, inspects the captured SelectStatement, and asserts the AST shape Phases 2-4
     * of the elemMatch design rely on: outer FROM root has exactly one TableGroupJoin whose joined group is a
     * FunctionTableReference with function name {@code "unnest"}.
     *
     * <p>The query fails at translation time (the v2 translator does not yet handle {@link FunctionTableReference});
     * the capture in buildSelectTranslator happens before the failure so the AST is still inspectable.
     */
    private void runAndAssertUnnestShape(String hql) {
        try {
            sessionFactoryScope.inSession(
                    session -> session.createQuery(hql, Item.class).getResultList());
        } catch (Throwable ignored) {
            // expected: the v2 translator does not yet handle FunctionTableReference
        }

        var captured = CapturingMqlv2TranslatorFactory.takeLastCaptured();
        assertThat(captured)
                .as("CapturingMqlv2TranslatorFactory did not capture any SelectStatement for HQL: %s", hql)
                .isNotNull();

        var roots = captured.getQueryPart().getFirstQuerySpec().getFromClause().getRoots();
        assertThat(roots).as("HQL: %s", hql).hasSize(1);
        var joins = roots.get(0).getTableGroupJoins();
        assertThat(joins)
                .as("lateral unnest join should appear as a TableGroupJoin; HQL: %s", hql)
                .hasSize(1);

        var primaryRef = joins.get(0).getJoinedGroup().getPrimaryTableReference();
        assertThat(primaryRef)
                .as("Hibernate 7 should map `join lateral unnest(...)` to a FunctionTableReference; HQL: %s", hql)
                .isInstanceOf(FunctionTableReference.class);

        var ftr = (FunctionTableReference) primaryRef;
        assertThat(ftr.getFunctionExpression().getFunctionName())
                .as("FunctionTableReference should identify the function as unnest; HQL: %s", hql)
                .isEqualTo("unnest");
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
