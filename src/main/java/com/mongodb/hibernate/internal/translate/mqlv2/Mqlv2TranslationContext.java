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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;
import org.jspecify.annotations.Nullable;

/**
 * Immutable (copy-on-write) translation state threaded through {@link Mqlv2ExpressionEmitter} and {@link Mqlv2StageEmitter} during IR construction.
 *
 * <p>Design note — single outer-scope: there is one {@code outerQualifiers} field and one {@code nextCorrelatedVar}
 * supplier, serving two roles that were previously split across {@code subqueryOuterQualifiers}/
 * {@code subqueryNextCorrelatedVar} (outer-query scope, set by the old {@code withSubquerySupport}) and
 * {@code outerQualifiers}/{@code nextCorrelatedVarIndex} (inner-subquery scope, set by the old
 * {@code forInnerSubquery}). Both roles encode the same lexical concept — "the qualifier set visible one scope above
 * the current translation point" — so they are unified here. {@link #withOuterScope} populates these fields for the
 * outer query so that subquery-predicate dispatch can read them; {@link #forInnerSubquery} derives a fresh inner
 * context from the same fields without re-passing them.
 *
 * @hidden
 */
public final class Mqlv2TranslationContext {

    private final List<JdbcParameterBinder> parameterBinders;
    private final Map<String, String> unnestAliasToFieldPath;
    private final boolean hasJoins;
    /**
     * Maps aggregate AST node (by identity) to the assigned alias name (e.g., {@code "_agg0"}). Used by
     * {@link Mqlv2ExpressionEmitter#translateExpression} to resolve aggregate
     * function references in HAVING clauses. Empty when not translating a HAVING predicate.
     */
    private final Map<SelfRenderingFunctionSqlAstExpression<?>, String> aggregateAliases;
    /**
     * Secondary signature-keyed index of already-assigned aggregate aliases. Used as a fallback dedup key when
     * Hibernate produces two distinct node instances for the same aggregate expression in SELECT and HAVING.
     */
    private final Map<String, String> aggSignatureIndex;

    /**
     * Set of table-reference aliases that are "outer" relative to the current translation scope. Non-null once
     * {@link #withOuterScope} has been called on the outer query, and preserved into inner-subquery contexts created via
     * {@link #forInnerSubquery}.
     *
     * <ul>
     *   <li>In an outer-query context: this is the set of aliases belonging to the outer query; its presence enables
     *       subquery-predicate dispatch in {@link Mqlv2ExpressionEmitter#translatePredicate}.
     *   <li>In an inner-subquery context: column references whose qualifier is in this set are translated as
     *       {@code $__vN} variable references rather than field accesses.
     * </ul>
     */
    @Nullable
    private final Set<String> outerQualifiers;

    /**
     * Maps {@code "qualifier.column"} → {@code "__vN"} (without {@code $} prefix — the Serializer adds it) for
     * correlated outer column references encountered during inner-subquery IR translation. Mutated lazily as new outer
     * columns are encountered. Non-null only for inner-subquery contexts created via {@link #forInnerSubquery}.
     */
    @Nullable
    private final Map<String, String> correlatedBindings;

    /**
     * Supplies the next integer index for allocating {@code __vN} variable names. Shared with the outer translator so
     * that variable names are globally unique across nested subqueries. Non-null once {@link #withOuterScope} has been
     * called, and preserved into inner-subquery contexts.
     */
    @Nullable
    private final IntSupplier nextCorrelatedVar;

    /**
     * Set of table-reference aliases whose qualified column references should be translated as
     * {@code FieldAccess(CurrentValue, column)} ({@code $.column}) — used inside MQLv2 {@code any}/{@code every} bodies
     * and inside the unnest-source pipeline of an unnest scalar subquery, where the current value is the array element.
     * Empty when not translating such a body.
     */
    private final Set<String> currentValueAliases;

    private Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders,
            Map<String, String> unnestAliasToFieldPath,
            boolean hasJoins,
            Map<SelfRenderingFunctionSqlAstExpression<?>, String> aggregateAliases,
            Map<String, String> aggSignatureIndex,
            @Nullable Set<String> outerQualifiers,
            @Nullable Map<String, String> correlatedBindings,
            @Nullable IntSupplier nextCorrelatedVar,
            Set<String> currentValueAliases) {
        this.parameterBinders = parameterBinders;
        this.unnestAliasToFieldPath = unnestAliasToFieldPath;
        this.hasJoins = hasJoins;
        this.aggregateAliases = aggregateAliases;
        this.aggSignatureIndex = aggSignatureIndex;
        this.outerQualifiers = outerQualifiers;
        this.correlatedBindings = correlatedBindings;
        this.nextCorrelatedVar = nextCorrelatedVar;
        this.currentValueAliases = currentValueAliases;
    }

    /**
     * Creates a root context that holds the shared (translator-global) parameter-binder list. Call {@link
     * #forSpec(boolean)} on this instance to obtain a fresh per-spec context for each {@link
     * org.hibernate.sql.ast.tree.select.QuerySpec} build.
     */
    public static Mqlv2TranslationContext root(List<JdbcParameterBinder> parameterBinders) {
        return new Mqlv2TranslationContext(
                parameterBinders,
                Collections.emptyMap(),
                false,
                Collections.emptyMap(),
                Collections.emptyMap(),
                null,
                null,
                null,
                Collections.emptySet());
    }

    /**
     * Constructs a fresh per-spec context, sharing the translator-global parameter-binder list but giving the new spec
     * its own per-spec state ({@code hasJoins}, unnest aliases, aggregate aliases, agg-signature index). Used at the
     * start of each {@link org.hibernate.sql.ast.tree.select.QuerySpec} build to give the spec a clean state slate.
     */
    public Mqlv2TranslationContext forSpec(boolean hasJoins) {
        return new Mqlv2TranslationContext(
                this.parameterBinders,
                new LinkedHashMap<>(),
                hasJoins,
                new IdentityHashMap<>(),
                new LinkedHashMap<>(),
                null,
                null,
                null,
                Collections.emptySet());
    }

    /**
     * Create a {@link Mqlv2TranslationContext} for translating an inner subquery predicate. The inner context shares
     * the parameter-binder list with the outer context (so parameter allocation remains globally ordered), but uses
     * empty unnest-alias and agg-signature maps (inner subqueries are always simple non-join scans in the patterns we
     * support).
     *
     * <p>Column references whose qualifier is in this context's {@link #outerQualifiers} will be translated as
     * {@code VarRef("__vN")} rather than {@code FieldAccess}. The allocated binding is recorded in
     * {@code correlatedBindings} and can be retrieved via {@link #getCorrelatedBindings()} by the caller to build the
     * {@code LetExpr} wrapper.
     *
     * <p>Must only be called on a context that has {@link #hasOuterScope()} returning {@code true}.
     *
     * @param correlatedBindings a mutable map (initially empty) that is populated with {@code "qualifier.column" →
     *     "__vN"} entries as outer-correlated column references are encountered during translation.
     * @return a new inner-subquery context.
     */
    public Mqlv2TranslationContext forInnerSubquery(Map<String, String> correlatedBindings) {
        return new Mqlv2TranslationContext(
                parameterBinders,
                Collections.emptyMap(),
                false,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Objects.requireNonNull(outerQualifiers, "outerQualifiers must be set before calling forInnerSubquery"),
                correlatedBindings,
                Objects.requireNonNull(nextCorrelatedVar, "nextCorrelatedVar must be set before calling forInnerSubquery"),
                Collections.emptySet());
    }

    /**
     * Return a copy of this context augmented with a set of "current-value aliases" that resolve to
     * {@code FieldAccess(CurrentValue, column)} ({@code $.column}). Used to translate column references inside MQLv2
     * {@code any}/{@code every} bodies and inside the unnest-source pipeline of an unnest scalar subquery, where the
     * current value is the unwrapped array element.
     *
     * <p>The new aliases are added on top of any existing current-value aliases (nested unwrap bodies are supported).
     *
     * @param newAliases additional table-reference aliases that should resolve to the current value.
     * @return a new context whose {@link #isCurrentValueAlias(String)} returns true for {@code newAliases} (and any
     *     previously-registered current-value aliases).
     */
    public Mqlv2TranslationContext forArrayElement(Set<String> newAliases) {
        var merged = new LinkedHashSet<>(this.currentValueAliases);
        merged.addAll(newAliases);
        return new Mqlv2TranslationContext(
                parameterBinders,
                unnestAliasToFieldPath,
                hasJoins,
                aggregateAliases,
                aggSignatureIndex,
                outerQualifiers,
                correlatedBindings,
                nextCorrelatedVar,
                Collections.unmodifiableSet(merged));
    }

    /** Returns true iff {@code qualifier} is a registered current-value alias (see {@link #forArrayElement}). */
    public boolean isCurrentValueAlias(String qualifier) {
        return currentValueAliases.contains(qualifier);
    }

    /**
     * Return a copy of this context augmented with outer-scope information. The returned context:
     *
     * <ul>
     *   <li>enables subquery-predicate dispatch in {@link Mqlv2ExpressionEmitter#translatePredicate} ({@link #hasOuterScope()}
     *       returns {@code true}),
     *   <li>makes {@link #outerQualifiers()} and {@link #nextCorrelatedVar()} available for building inner-subquery
     *       contexts via {@link #forInnerSubquery}.
     * </ul>
     *
     * @param outerQualifiers the set of table-reference aliases visible in the outer (this) query scope.
     * @param nextCorrelatedVar supplier of the next globally-unique integer index for {@code __vN} variable names;
     *     shared with the outer translator.
     * @return a new context with outer-scope information set.
     */
    public Mqlv2TranslationContext withOuterScope(Set<String> outerQualifiers, IntSupplier nextCorrelatedVar) {
        return new Mqlv2TranslationContext(
                parameterBinders,
                unnestAliasToFieldPath,
                hasJoins,
                aggregateAliases,
                aggSignatureIndex,
                outerQualifiers,
                this.correlatedBindings,
                nextCorrelatedVar,
                currentValueAliases);
    }

    /**
     * Returns {@code true} if {@code qualifier} is an outer-query alias that should be translated as a correlated
     * {@code $__vN} variable reference rather than a field access. Always {@code false} in non-subquery contexts.
     */
    public boolean isOuterCorrelated(String qualifier) {
        return outerQualifiers != null && correlatedBindings != null && outerQualifiers.contains(qualifier);
    }

    /**
     * Look up or allocate a {@code __vN} variable name for the outer-correlated reference {@code qualifier.column}.
     * The allocated name (without {@code $} prefix) is stored in {@link #correlatedBindings} and returned.
     *
     * <p>Must only be called when {@link #isOuterCorrelated(String)} returns {@code true} for {@code qualifier}.
     */
    public String allocateCorrelatedVar(String qualifier, String column) {
        var cb = Objects.requireNonNull(correlatedBindings, "correlatedBindings must be non-null in inner context");
        var supplier = Objects.requireNonNull(nextCorrelatedVar, "nextCorrelatedVar must be non-null in inner context");
        var key = qualifier + "." + column;
        return cb.computeIfAbsent(key, k -> "__v" + supplier.getAsInt());
    }

    /**
     * Returns the correlated-bindings map populated during inner-subquery translation. The map holds
     * {@code "qualifier.column" → "__vN"} (without {@code $} prefix) for all outer-correlated column references
     * encountered. Empty until any outer-correlated column reference is translated.
     *
     * <p>Must only be called on a context created via {@link #forInnerSubquery}.
     */
    public Map<String, String> getCorrelatedBindings() {
        return Objects.requireNonNull(correlatedBindings, "correlatedBindings must be non-null in inner context");
    }

    /** Append {@code binder} to the binder list and return the resulting {@code $pN} index. */
    public int allocateParameter(JdbcParameterBinder binder) {
        int idx = parameterBinders.size();
        parameterBinders.add(binder);
        return idx;
    }

    /**
     * Returns the live parameter-binder list. Used by stage-level helpers (e.g.,
     * {@link Mqlv2StageEmitter#translateLimit}) to read the current list size after a callback has pushed a binder
     * externally.
     */
    public List<JdbcParameterBinder> parameterBinders() {
        return parameterBinders;
    }

    public Map<String, String> unnestAliasToFieldPath() {
        return unnestAliasToFieldPath;
    }

    public boolean hasJoins() {
        return hasJoins;
    }

    public Map<SelfRenderingFunctionSqlAstExpression<?>, String> aggregateAliases() {
        return aggregateAliases;
    }

    public Map<String, String> aggSignatureIndex() {
        return aggSignatureIndex;
    }

    /**
     * Returns {@code true} if this context has outer-scope information set (i.e., was created via
     * {@link #withOuterScope}). When {@code true}, {@link Mqlv2ExpressionEmitter#translatePredicate} can dispatch
     * {@code ExistsPredicate}, {@code InSubQueryPredicate}, and {@code ComparisonPredicate} with {@code Any}/{@code
     * Every} RHS to the appropriate emitters, and {@link #forInnerSubquery} may be called to derive an inner context.
     */
    public boolean hasOuterScope() {
        return outerQualifiers != null && correlatedBindings == null;
    }

    /**
     * Returns the set of outer-query table-reference aliases. Used both for subquery-predicate dispatch (outer-query
     * context) and for outer-correlated column-reference detection (inner-subquery context).
     *
     * <p>Must only be called when {@link #hasOuterScope()} returns {@code true}.
     */
    public Set<String> outerQualifiers() {
        return Objects.requireNonNull(outerQualifiers, "outerQualifiers must be non-null");
    }

    /**
     * Returns the supplier for the next globally-unique {@code __vN} integer index. Used both for subquery-predicate
     * dispatch (outer-query context) and for correlated-variable allocation (inner-subquery context).
     *
     * <p>Must only be called when {@link #hasOuterScope()} returns {@code true}.
     */
    public IntSupplier nextCorrelatedVar() {
        return Objects.requireNonNull(nextCorrelatedVar, "nextCorrelatedVar must be non-null");
    }
}
