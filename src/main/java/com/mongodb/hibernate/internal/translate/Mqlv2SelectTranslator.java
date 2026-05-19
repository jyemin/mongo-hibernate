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

package com.mongodb.hibernate.internal.translate;

import static java.lang.Integer.MAX_VALUE;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static org.hibernate.sql.exec.spi.JdbcLockStrategy.NONE;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2StageEmitter;
import com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2TranslationContext;
import com.mongodb.mqlv2.Serializer;
import com.mongodb.mqlv2.ast.Stage;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.collections.Stack;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.SetOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.EntityValuedPathInterpretation;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Star;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.predicate.BooleanExpressionPredicate;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.QueryGroup;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectClause;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.exec.internal.AbstractJdbcParameter;
import org.hibernate.sql.exec.internal.JdbcOperationQuerySelect;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.exec.spi.JdbcSelect;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMappingProducerProvider;
import org.hibernate.type.BasicType;
import org.jspecify.annotations.Nullable;

/**
 * Translates a Hibernate SELECT SQL AST directly to a MQLv2 text command.
 *
 * <p>Design note — recursive descent over the visitor pattern: the actual translation work is done by
 * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2ExpressionEmitter} and
 * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2StageEmitter} using recursive descent ({@code instanceof}
 * dispatch, explicit return values) rather than the {@link org.hibernate.sql.ast.SqlAstWalker} visitor interface. Two
 * reasons:
 *
 * <ol>
 *   <li><b>Return values.</b> Visitor {@code visitX} methods are {@code void}; building a typed IR tree requires
 *       returning {@link com.mongodb.mqlv2.ast.Expr}/{@link com.mongodb.mqlv2.ast.Stage} nodes. The visitor alternative
 *       is a mutable push/pop accumulator stack — error-prone bookkeeping, especially across nested translations.
 *   <li><b>Context threading.</b> {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2TranslationContext} is
 *       copy-on-write and passed as an explicit parameter; different subtrees require different contexts (inner
 *       subquery, array-element scope, etc.). As visitor state this would require save/restore around every scope
 *       boundary.
 * </ol>
 *
 * <p>This class still implements {@link org.hibernate.sql.ast.SqlAstWalker} (via {@link ThrowingMqlv2SqlAstWalker}) to
 * satisfy the {@link org.hibernate.sql.ast.SqlAstTranslator} contract, not as a design choice.
 */
final class Mqlv2SelectTranslator implements SqlAstTranslator<JdbcSelect>, ThrowingMqlv2SqlAstWalker {

    private record QueryPartTranslation(String mqlv2, List<String> fieldNames, Stage stage) {}

    private final SessionFactoryImplementor sessionFactory;
    private final SelectStatement selectStatement;
    /**
     * Globally-ordered list of JDBC parameter binders, indexed by the {@code $pN} position in the emitted MQLv2 text.
     * Owned by the translator (its lifetime matches the translation) and shared by reference into every
     * {@link Mqlv2TranslationContext} so a single global ordering is preserved across nested subqueries.
     *
     * <p>Writes:
     *
     * <ul>
     *   <li>{@link Mqlv2TranslationContext#allocateParameter(JdbcParameterBinder)} — appends one binder per
     *       {@code JdbcParameter} AST node encountered during expression translation. This is the main writer.
     *   <li>The {@code onSetMaxResults} callback below ({@code translateLimit} invokes it when {@code setMaxResults}
     *       binds a dynamic limit) — appends the translator-owned {@link LimitJdbcParameter}'s binder.
     * </ul>
     *
     * <p>Reads: {@link #translate} consumes the list when constructing the {@link JdbcOperationQuerySelect} return.
     */
    private final List<JdbcParameterBinder> parameterBinders = new ArrayList<>();

    private @Nullable LimitJdbcParameter limitJdbcParameter;
    // Intentionally global (not reset per subquery branch): $__vN names only need to be unique across
    // the whole translation, and sharing the counter avoids collisions between nested correlated bindings.
    private int correlatedVarCounter = 0;

    Mqlv2SelectTranslator(SessionFactoryImplementor sessionFactory, SelectStatement selectStatement) {
        this.sessionFactory = sessionFactory;
        this.selectStatement = selectStatement;
    }

    @Override
    public SessionFactoryImplementor getSessionFactory() {
        return sessionFactory;
    }

    @Override
    public void render(SqlAstNode sqlAstNode, SqlAstNodeRenderingMode renderingMode) {
        throw new FeatureNotSupportedException();
    }

    @Override
    @SuppressWarnings("TypeParameterUnusedInFormals")
    public <X> X getLiteralValue(Expression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public org.hibernate.sql.ast.tree.Statement getSqlAst() {
        return selectStatement;
    }

    @Override
    public void renderNamedSetReturningFunction(
            String functionName,
            java.util.List<? extends SqlAstNode> sqlAstArguments,
            org.hibernate.query.sqm.tuple.internal.AnonymousTupleTableGroupProducer tupleType,
            String tableIdentifierVariable,
            SqlAstNodeRenderingMode argumentRenderingMode) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public QueryPart getCurrentQueryPart() {
        throw new FeatureNotSupportedException();
    }

    @Override
    public Stack<Clause> getCurrentClauseStack() {
        throw new FeatureNotSupportedException();
    }

    @Override
    public Set<String> getAffectedTableNames() {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void addAffectedTableName(String tableName) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public JdbcOperationQuerySelect translate(
            @Nullable JdbcParameterBindings jdbcParameterBindings, QueryOptions queryOptions) {

        var queryPart = selectStatement.getQueryPart();
        String mqlv2Text;
        List<String> fieldNames;

        if (queryPart instanceof QuerySpec querySpec) {
            var translation = buildQuerySpecTranslation(querySpec, queryOptions);
            mqlv2Text = translation.mqlv2();
            fieldNames = translation.fieldNames();
        } else if (queryPart instanceof QueryGroup queryGroup) {
            var translation = buildQueryGroupTranslation(queryGroup);
            mqlv2Text = translation.mqlv2();
            fieldNames = translation.fieldNames();
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported QueryPart: " + queryPart.getClass().getSimpleName());
        }

        var commandJson = new BsonDocument("mqlv2", new BsonString(mqlv2Text))
                .append(
                        "_mqlv2FieldNames",
                        new BsonArray(fieldNames.stream().map(BsonString::new).toList()))
                .append("_mqlv2ParamCount", new BsonInt32(parameterBinders.size()))
                .toJson();

        // For affected table names, walk the first QuerySpec's root
        var firstSpec = queryPart.getFirstQuerySpec();
        var affectedTableNames =
                collectAffectedTableNames(firstSpec.getFromClause().getRoots().get(0));
        var mappingProducerProvider =
                sessionFactory.getServiceRegistry().requireService(JdbcValuesMappingProducerProvider.class);
        var mappingProducer = mappingProducerProvider.buildMappingProducer(selectStatement, sessionFactory);
        return new JdbcOperationQuerySelect(
                commandJson,
                parameterBinders,
                mappingProducer,
                affectedTableNames,
                0,
                MAX_VALUE,
                emptyMap(),
                NONE,
                null,
                limitJdbcParameter);
    }

    /**
     * Builds the MQLv2 pipeline translation for a sub-spec (UNION/INTERSECT/EXCEPT operand). Each call receives its own
     * fresh per-spec context (via {@link Mqlv2TranslationContext#forSpec}), so per-spec state is naturally isolated —
     * no save/restore needed.
     *
     * <p>Inner subqueries that need outer-correlation support ({@code $__vN} bindings) are handled internally by
     * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2ExpressionEmitter} — this method is for set-operator
     * operands that have no implicit correlation with the outer scope.
     */
    private QueryPartTranslation buildSubQuerySpecTranslation(QuerySpec querySpec) {
        return buildQuerySpecTranslation(querySpec, null);
    }

    /**
     * Builds the MQLv2 pipeline text and field-name list for a single QuerySpec by constructing a full driver-mqlv2
     * {@link com.mongodb.mqlv2.ast.Stage} chain and serializing it in one shot via
     * {@link com.mongodb.mqlv2.Serializer}. Pass non-null {@code queryOptions} at the top level to honour dynamic
     * first/max rows; pass {@code null} for sub-queries (UNION members, correlated sub-queries) where only the HQL
     * literal LIMIT clause applies.
     *
     * <p>Each call allocates a fresh per-spec context via {@link Mqlv2TranslationContext#forSpec}, so per-spec state
     * ({@code hasJoins}, {@code unnestAliasToFieldPath}, {@code aggregateAliases}, {@code aggSignatureIndex}) is
     * cleanly scoped to this invocation with no manual save/restore.
     *
     * <p>Subquery-based predicates (EXISTS, IN subquery, ANY/ALL) in WHERE/HAVING and scalar SELECT-position subqueries
     * are translated via
     * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2ExpressionEmitter#translatePredicate} /
     * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2ExpressionEmitter#translateExpression} when the
     * context has subquery support enabled.
     */
    private QueryPartTranslation buildQuerySpecTranslation(QuerySpec querySpec, @Nullable QueryOptions queryOptions) {
        var root = querySpec.getFromClause().getRoots().get(0);
        // Only count non-unnest joins: unnest joins translate to | unwind and don't introduce
        // a new MQLv2 alias, so they must not trigger the qualifier-prefix path in column-ref rendering.
        boolean hasJoins = root.getTableGroupJoins().stream()
                .anyMatch(tgj -> !(tgj.getJoinedGroup().getPrimaryTableReference() instanceof FunctionTableReference ftr
                        && "unnest".equals(ftr.getFunctionExpression().getFunctionName())));

        var ntr = (NamedTableReference) root.getPrimaryTableReference();
        var outerQualifiers = collectOuterQualifiers(querySpec);
        var ctx = Mqlv2TranslationContext.forSpec(parameterBinders, hasJoins)
                .withOuterScope(outerQualifiers, () -> correlatedVarCounter++);
        var stage = Mqlv2StageEmitter.translateFromStage(ntr, hasJoins);
        stage = Mqlv2StageEmitter.translateJoins(stage, root, querySpec, ctx);
        stage = Mqlv2StageEmitter.translateMatch(stage, querySpec, ctx);

        List<@Nullable String> aggNames = null;
        if (!querySpec.getGroupByClauseExpressions().isEmpty()) {
            aggNames = buildAggNames(querySpec.getSelectClause(), ctx);
            var havingOnlyAggs = collectHavingOnlyAggs(querySpec.getHavingClauseRestrictions(), ctx);
            stage = Mqlv2StageEmitter.translateGroup(
                    stage,
                    querySpec.getGroupByClauseExpressions(),
                    querySpec.getSelectClause(),
                    aggNames,
                    havingOnlyAggs,
                    ctx);
            stage = Mqlv2StageEmitter.translateHaving(stage, querySpec, ctx);
        } else if (selectHasAggregates(querySpec.getSelectClause())) {
            aggNames = buildAggNames(querySpec.getSelectClause(), ctx);
            stage = Mqlv2StageEmitter.translateScalarAgg(stage, querySpec.getSelectClause(), aggNames, ctx);
        }

        stage = Mqlv2StageEmitter.translateSort(stage, querySpec, ctx);
        stage = Mqlv2StageEmitter.translateLimit(stage, querySpec, queryOptions, ctx, () -> {
            var basicIntegerType = sessionFactory.getTypeConfiguration().getBasicTypeForJavaType(Integer.class);
            limitJdbcParameter = new LimitJdbcParameter(requireNonNull(basicIntegerType));
            parameterBinders.add(limitJdbcParameter.getParameterBinder());
        });
        var fmt = Mqlv2StageEmitter.translateFormat(stage, querySpec.getSelectClause(), aggNames, ctx);
        stage = fmt.stage();
        if (querySpec.getSelectClause().isDistinct()) {
            stage = new Stage.DistinctStage(stage);
        }
        return new QueryPartTranslation(new Serializer().serialize(stage), fmt.fieldNames(), stage);
    }

    private QueryPartTranslation buildQueryGroupTranslation(QueryGroup queryGroup) {
        var operator = queryGroup.getSetOperator();
        if (operator == SetOperator.INTERSECT_ALL || operator == SetOperator.EXCEPT_ALL) {
            throw new FeatureNotSupportedException(operator + " is not supported in MQLv2");
        }

        var parts = queryGroup.getQueryParts();
        if ((operator == SetOperator.INTERSECT || operator == SetOperator.EXCEPT) && parts.size() > 2) {
            throw new FeatureNotSupportedException(
                    "Chained " + operator + " with more than two operands is not supported in MQLv2");
        }

        var translations = parts.stream()
                .map(p -> buildSubQuerySpecTranslation(p.getFirstQuerySpec()))
                .toList();

        var subStages = translations.stream().map(QueryPartTranslation::stage).toList();
        Stage groupStage =
                Mqlv2StageEmitter.translateQueryGroupStage(queryGroup, subStages, () -> correlatedVarCounter++);
        return new QueryPartTranslation(
                new Serializer().serialize(groupStage), translations.get(0).fieldNames(), groupStage);
    }

    private static Set<String> collectOuterQualifiers(QuerySpec outerSpec) {
        var result = new LinkedHashSet<String>();
        for (var root : outerSpec.getFromClause().getRoots()) {
            collectGroupQualifiers(root, result);
        }
        return result;
    }

    private static void collectGroupQualifiers(TableGroup group, Set<String> result) {
        var primaryRef = group.getPrimaryTableReference();
        if (primaryRef instanceof NamedTableReference ntr) {
            var alias = ntr.getIdentificationVariable();
            if (alias != null) result.add(alias);
        }
        // FunctionTableReference (unnest join) has no alias to contribute — skip it.
        for (var tgj : group.getTableGroupJoins()) {
            collectGroupQualifiers(tgj.getJoinedGroup(), result);
        }
    }

    private List<@Nullable String> buildAggNames(SelectClause selectClause, Mqlv2TranslationContext ctx) {
        var result = new ArrayList<@Nullable String>();
        var aggIdx = 0;
        for (var sel : selectClause.getSqlSelections()) {
            if (sel.isVirtual()) continue;
            if (isAggregateFunction(sel.getExpression())) {
                var fn = (SelfRenderingFunctionSqlAstExpression<?>) sel.getExpression();
                var name = "_agg" + aggIdx++;
                ctx.aggregateAliases().put(fn, name);
                // Also populate the signature index so that structurally-equal nodes from HAVING can be deduped
                // even when Hibernate constructs them as distinct instances.
                ctx.aggSignatureIndex().put(aggSignature(fn, ctx.hasJoins()), name);
                result.add(name);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    private Map<String, SelfRenderingFunctionSqlAstExpression<?>> collectHavingOnlyAggs(
            @Nullable Predicate predicate, Mqlv2TranslationContext ctx) {
        var result = new LinkedHashMap<String, SelfRenderingFunctionSqlAstExpression<?>>();
        if (predicate != null && !predicate.isEmpty()) {
            collectHavingOnlyAggsInPredicate(predicate, result, ctx);
        }
        return result;
    }

    private void collectHavingOnlyAggsInPredicate(
            Predicate predicate,
            Map<String, SelfRenderingFunctionSqlAstExpression<?>> result,
            Mqlv2TranslationContext ctx) {
        if (predicate instanceof ComparisonPredicate cp) {
            collectHavingOnlyAggsInExpr(cp.getLeftHandExpression(), result, ctx);
            collectHavingOnlyAggsInExpr(cp.getRightHandExpression(), result, ctx);
        } else if (predicate instanceof Junction junction) {
            for (var p : junction.getPredicates()) collectHavingOnlyAggsInPredicate(p, result, ctx);
        } else if (predicate instanceof GroupedPredicate gp) {
            collectHavingOnlyAggsInPredicate(gp.getSubPredicate(), result, ctx);
        } else if (predicate instanceof NegatedPredicate np) {
            collectHavingOnlyAggsInPredicate(np.getPredicate(), result, ctx);
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            collectHavingOnlyAggsInExpr(bp.getExpression(), result, ctx);
        } else if (predicate instanceof InListPredicate ilp) {
            collectHavingOnlyAggsInExpr(ilp.getTestExpression(), result, ctx);
            for (var e : ilp.getListExpressions()) collectHavingOnlyAggsInExpr(e, result, ctx);
        }
    }

    private void collectHavingOnlyAggsInExpr(
            Expression expr,
            Map<String, SelfRenderingFunctionSqlAstExpression<?>> result,
            Mqlv2TranslationContext ctx) {
        if (isAggregateFunction(expr)) {
            var fn = (SelfRenderingFunctionSqlAstExpression<?>) expr;
            if (ctx.aggregateAliases().containsKey(fn)) {
                // Same instance already registered from SELECT; nothing to do.
                return;
            }
            // Hibernate sometimes creates distinct AST node instances for the same aggregate in SELECT vs. HAVING.
            // Fall back to signature-based dedup to detect structural equality across node instances.
            var sig = aggSignature(fn, ctx.hasJoins());
            var existingAlias = ctx.aggSignatureIndex().get(sig);
            if (existingAlias != null) {
                // A structurally-equal aggregate was already registered from SELECT; alias the HAVING node too
                // so that Mqlv2ExpressionEmitter can find it via the identity-keyed aggregateAliases map.
                ctx.aggregateAliases().put(fn, existingAlias);
            } else {
                var name = "_agg" + ctx.aggregateAliases().size();
                ctx.aggregateAliases().put(fn, name);
                ctx.aggSignatureIndex().put(sig, name);
                result.put(name, fn);
            }
        } else if (expr instanceof BinaryArithmeticExpression bae) {
            collectHavingOnlyAggsInExpr(bae.getLeftHandOperand(), result, ctx);
            collectHavingOnlyAggsInExpr(bae.getRightHandOperand(), result, ctx);
        }
    }

    private static final Set<String> AGGREGATE_FUNCTION_NAMES = Set.of("count", "sum", "avg", "min", "max");

    private static boolean isAggregateFunction(Expression expr) {
        return expr instanceof SelfRenderingFunctionSqlAstExpression<?> fn
                && AGGREGATE_FUNCTION_NAMES.contains(fn.getFunctionName());
    }

    /**
     * Compute a structural signature string for an aggregate function node. Used solely by the agg-signature index as a
     * fallback dedup key when Hibernate produces two distinct node instances for the same aggregate expression in
     * SELECT and HAVING.
     */
    private static String aggSignature(SelfRenderingFunctionSqlAstExpression<?> fn, boolean hasJoins) {
        var args = fn.getArguments();
        if (args.isEmpty() || args.get(0) instanceof Star || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            return fn.getFunctionName() + ":*";
        }
        if (args.get(0) instanceof Expression argExpr) {
            return fn.getFunctionName() + ":" + aggColumnSignature(argExpr, hasJoins);
        }
        return fn.getFunctionName() + ":?";
    }

    private static String aggColumnSignature(Expression expr, boolean hasJoins) {
        if (expr instanceof ColumnReference cr) {
            if (hasJoins && cr.getQualifier() != null && !cr.getQualifier().isEmpty()) {
                return cr.getQualifier() + "." + cr.getColumnExpression();
            }
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return aggColumnSignature(requireNonNull(bvpi.getColumnReference()), hasJoins);
        } else {
            throw new FeatureNotSupportedException("Expected simple column reference in aggregate; got: "
                    + expr.getClass().getSimpleName());
        }
    }

    private static boolean selectHasAggregates(SelectClause selectClause) {
        return selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .anyMatch(s -> isAggregateFunction(s.getExpression()));
    }

    private static Set<String> collectAffectedTableNames(TableGroup root) {
        var names = new LinkedHashSet<String>();
        collectAffectedTableNamesRecursive(root, names);
        return names;
    }

    private static void collectAffectedTableNamesRecursive(TableGroup group, Set<String> names) {
        var primaryRef = group.getPrimaryTableReference();
        if (primaryRef instanceof NamedTableReference ntr) {
            names.add(ntr.getTableExpression());
        }
        // FunctionTableReference (unnest join) has no collection name to add.
        for (var tgj : group.getTableGroupJoins()) {
            collectAffectedTableNamesRecursive(tgj.getJoinedGroup(), names);
        }
    }

    private static final class LimitJdbcParameter extends AbstractJdbcParameter {

        LimitJdbcParameter(BasicType<Integer> type) {
            super(type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void bindParameterValue(
                PreparedStatement statement,
                int startPosition,
                JdbcParameterBindings jdbcParamBindings,
                ExecutionContext executionContext)
                throws SQLException {
            getJdbcMapping()
                    .getJdbcValueBinder()
                    .bind(
                            statement,
                            executionContext.getQueryOptions().getLimit().getMaxRows(),
                            startPosition,
                            executionContext.getSession());
        }
    }
}
