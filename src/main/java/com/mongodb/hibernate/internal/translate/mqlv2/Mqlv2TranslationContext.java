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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;

/**
 * Mutable translation state threaded through {@link Mqlv2IrEmitters} during IR construction. Holds the JDBC parameter
 * binder list (shared with {@code Mqlv2SelectTranslator}) and the qualifier-rendering rules currently active for column
 * references.
 *
 * <p>Phase B introduction. Phase D5 extends this with an outer-qualifier set and correlated-binding map for
 * inner-subquery IR translation (EXISTS/IN/ANY/ALL patterns).
 *
 * @hidden
 */
public final class Mqlv2TranslationContext {

    private final List<JdbcParameterBinder> parameterBinders;
    private final Map<String, String> unnestAliasToFieldPath;
    private final boolean hasJoins;
    /**
     * Maps aggregate signature (e.g., {@code "count:id"}) to the assigned alias name (e.g., {@code "_agg0"}). Used by
     * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2IrEmitters#translateExpression} to resolve aggregate
     * function references in HAVING clauses. Empty when not translating a HAVING predicate.
     */
    private final Map<String, String> aggSignatureToName;

    /**
     * Set of outer-query table-reference aliases (e.g., {@code "c"} for {@code Customer c}). Non-null only for inner
     * subquery contexts created via {@link #forInnerSubquery}. When a column reference's qualifier is in this set, it
     * is translated as a {@code $__vN} variable reference rather than a field access.
     */
    @org.jspecify.annotations.Nullable
    private final Set<String> outerQualifiers;

    /**
     * Maps {@code "qualifier.column"} → {@code "__vN"} (without {@code $} prefix — the Serializer adds it) for
     * correlated outer column references encountered during inner-subquery IR translation. Mutated lazily as new outer
     * columns are encountered. Non-null only when {@link #outerQualifiers} is non-null.
     */
    @org.jspecify.annotations.Nullable
    private final Map<String, String> correlatedBindings;

    /**
     * Supplies the next integer index for allocating {@code __vN} variable names. Shared with the outer translator so
     * that variable names are globally unique across nested subqueries. Non-null only when {@link #outerQualifiers} is
     * non-null.
     */
    @org.jspecify.annotations.Nullable
    private final IntSupplier nextCorrelatedVarIndex;

    /**
     * The set of table-reference aliases visible in this (outer) query scope. Used by
     * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2IrEmitters#translatePredicate} to dispatch
     * {@code ExistsPredicate}, {@code InSubQueryPredicate}, and {@code ComparisonPredicate} with {@code Any}/{@code
     * Every} RHS to the appropriate subquery-predicate emitters. Non-null only when subquery-predicate translation is
     * enabled for this context (set via {@link #withSubquerySupport}).
     */
    @org.jspecify.annotations.Nullable
    private final Set<String> subqueryOuterQualifiers;

    /**
     * Supplier for the next globally-unique {@code __vN} integer index, shared with the outer translator. Non-null only
     * when {@link #subqueryOuterQualifiers} is non-null.
     */
    @org.jspecify.annotations.Nullable
    private final IntSupplier subqueryNextCorrelatedVar;

    public Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders, Map<String, String> unnestAliasToFieldPath, boolean hasJoins) {
        this(parameterBinders, unnestAliasToFieldPath, hasJoins, Collections.emptyMap());
    }

    public Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders,
            Map<String, String> unnestAliasToFieldPath,
            boolean hasJoins,
            Map<String, String> aggSignatureToName) {
        this.parameterBinders = parameterBinders;
        this.unnestAliasToFieldPath = unnestAliasToFieldPath;
        this.hasJoins = hasJoins;
        this.aggSignatureToName = aggSignatureToName;
        this.outerQualifiers = null;
        this.correlatedBindings = null;
        this.nextCorrelatedVarIndex = null;
        this.subqueryOuterQualifiers = null;
        this.subqueryNextCorrelatedVar = null;
    }

    private Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders,
            Map<String, String> unnestAliasToFieldPath,
            boolean hasJoins,
            Map<String, String> aggSignatureToName,
            @org.jspecify.annotations.Nullable Set<String> outerQualifiers,
            @org.jspecify.annotations.Nullable Map<String, String> correlatedBindings,
            @org.jspecify.annotations.Nullable IntSupplier nextCorrelatedVarIndex,
            @org.jspecify.annotations.Nullable Set<String> subqueryOuterQualifiers,
            @org.jspecify.annotations.Nullable IntSupplier subqueryNextCorrelatedVar) {
        this.parameterBinders = parameterBinders;
        this.unnestAliasToFieldPath = unnestAliasToFieldPath;
        this.hasJoins = hasJoins;
        this.aggSignatureToName = aggSignatureToName;
        this.outerQualifiers = outerQualifiers;
        this.correlatedBindings = correlatedBindings;
        this.nextCorrelatedVarIndex = nextCorrelatedVarIndex;
        this.subqueryOuterQualifiers = subqueryOuterQualifiers;
        this.subqueryNextCorrelatedVar = subqueryNextCorrelatedVar;
    }

    /**
     * Create a {@link Mqlv2TranslationContext} for translating an inner subquery predicate. The inner context shares
     * the parameter-binder list with the outer context (so parameter allocation remains globally ordered), but uses
     * empty unnest-alias and agg-signature maps (inner subqueries are always simple non-join scans in the patterns we
     * support).
     *
     * <p>Column references whose qualifier is in {@code outerQualifiers} will be translated as {@code VarRef("__vN")}
     * rather than {@code FieldAccess}. The allocated binding is recorded in {@code correlatedBindings} and can be
     * retrieved via {@link #getCorrelatedBindings()} by the caller to build the {@code LetExpr} wrapper.
     *
     * @param outerQualifiers the set of table-reference aliases visible in the outer query.
     * @param correlatedBindings a mutable map (initially empty) that is populated with {@code "qualifier.column" →
     *     "__vN"} entries as outer-correlated column references are encountered during translation.
     * @param nextCorrelatedVarIndex supplier of the next integer index for allocating {@code __vN} names; shared with
     *     the outer translator.
     * @return a new inner-subquery context.
     */
    public Mqlv2TranslationContext forInnerSubquery(
            Set<String> outerQualifiers,
            Map<String, String> correlatedBindings,
            IntSupplier nextCorrelatedVarIndex) {
        return new Mqlv2TranslationContext(
                parameterBinders,
                Collections.emptyMap(),
                false,
                Collections.emptyMap(),
                outerQualifiers,
                correlatedBindings,
                nextCorrelatedVarIndex,
                null,
                null);
    }

    /**
     * Return a copy of this context augmented with subquery-predicate support. The returned context can translate
     * {@code ExistsPredicate}, {@code InSubQueryPredicate}, and {@code ComparisonPredicate} with {@code Any}/{@code
     * Every} RHS directly within {@link Mqlv2IrEmitters#translatePredicate}.
     *
     * @param outerQualifiers the set of table-reference aliases visible in the outer (this) query scope.
     * @param nextCorrelatedVar supplier of the next globally-unique integer index for {@code __vN} variable names;
     *     shared with the outer translator.
     * @return a new context with subquery-predicate translation enabled.
     */
    public Mqlv2TranslationContext withSubquerySupport(
            Set<String> outerQualifiers, IntSupplier nextCorrelatedVar) {
        return new Mqlv2TranslationContext(
                parameterBinders,
                unnestAliasToFieldPath,
                hasJoins,
                aggSignatureToName,
                this.outerQualifiers,
                this.correlatedBindings,
                this.nextCorrelatedVarIndex,
                outerQualifiers,
                nextCorrelatedVar);
    }

    /**
     * Returns {@code true} if {@code qualifier} is an outer-query alias that should be translated as a correlated
     * {@code $__vN} variable reference rather than a field access. Always {@code false} in non-subquery contexts.
     */
    public boolean isOuterCorrelated(String qualifier) {
        return outerQualifiers != null && outerQualifiers.contains(qualifier);
    }

    /**
     * Look up or allocate a {@code __vN} variable name for the outer-correlated reference {@code qualifier.column}.
     * The allocated name (without {@code $} prefix) is stored in {@link #correlatedBindings} and returned.
     *
     * <p>Must only be called when {@link #isOuterCorrelated(String)} returns {@code true} for {@code qualifier}.
     */
    public String allocateCorrelatedVar(String qualifier, String column) {
        var cb = Objects.requireNonNull(correlatedBindings, "correlatedBindings must be non-null in inner context");
        var supplier = Objects.requireNonNull(
                nextCorrelatedVarIndex, "nextCorrelatedVarIndex must be non-null in inner context");
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
     * {@link Mqlv2IrEmitters#translateLimit}) to read the current list size after a callback has pushed a binder
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

    public Map<String, String> aggSignatureToName() {
        return aggSignatureToName;
    }

    /**
     * Returns {@code true} if this context has subquery-predicate support enabled (i.e., was created via
     * {@link #withSubquerySupport}). When {@code true}, {@link Mqlv2IrEmitters#translatePredicate} can dispatch
     * {@code ExistsPredicate}, {@code InSubQueryPredicate}, and {@code ComparisonPredicate} with {@code Any}/{@code
     * Every} RHS to the appropriate emitters.
     */
    public boolean hasSubquerySupport() {
        return subqueryOuterQualifiers != null;
    }

    /**
     * Returns the set of outer-query aliases for subquery-predicate translation.
     *
     * <p>Must only be called when {@link #hasSubquerySupport()} returns {@code true}.
     */
    public Set<String> subqueryOuterQualifiers() {
        return Objects.requireNonNull(subqueryOuterQualifiers, "subqueryOuterQualifiers must be non-null");
    }

    /**
     * Returns the supplier for the next globally-unique {@code __vN} index for subquery-predicate translation.
     *
     * <p>Must only be called when {@link #hasSubquerySupport()} returns {@code true}.
     */
    public IntSupplier subqueryNextCorrelatedVar() {
        return Objects.requireNonNull(subqueryNextCorrelatedVar, "subqueryNextCorrelatedVar must be non-null");
    }
}
