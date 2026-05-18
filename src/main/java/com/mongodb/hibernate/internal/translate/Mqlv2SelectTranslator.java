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
import static org.hibernate.sql.exec.spi.JdbcLockStrategy.NONE;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2IrEmitters;
import com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2TranslationContext;
import com.mongodb.mqlv2.Serializer;
import com.mongodb.mqlv2.ast.Stage;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.EntityValuedPathInterpretation;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.collections.Stack;
import org.hibernate.persister.internal.SqlFragmentPredicate;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.SetOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.delete.DeleteStatement;
import org.hibernate.sql.ast.tree.expression.AggregateColumnWriteExpression;
import org.hibernate.sql.ast.tree.expression.Any;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.CaseSearchedExpression;
import org.hibernate.sql.ast.tree.expression.CaseSimpleExpression;
import org.hibernate.sql.ast.tree.expression.CastTarget;
import org.hibernate.sql.ast.tree.expression.Collation;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Distinct;
import org.hibernate.sql.ast.tree.expression.Duration;
import org.hibernate.sql.ast.tree.expression.DurationUnit;
import org.hibernate.sql.ast.tree.expression.EmbeddableTypeLiteral;
import org.hibernate.sql.ast.tree.expression.EntityTypeLiteral;
import org.hibernate.sql.ast.tree.expression.Every;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.ExtractUnit;
import org.hibernate.sql.ast.tree.expression.Format;
import org.hibernate.sql.ast.tree.expression.JdbcLiteral;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.ModifiedSubQueryExpression;
import org.hibernate.sql.ast.tree.expression.NestedColumnReference;
import org.hibernate.sql.ast.tree.expression.Over;
import org.hibernate.sql.ast.tree.expression.Overflow;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression;
import org.hibernate.sql.ast.tree.expression.SqlSelectionExpression;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.expression.Star;
import org.hibernate.sql.ast.tree.expression.Summarization;
import org.hibernate.sql.ast.tree.expression.TrimSpecification;
import org.hibernate.sql.ast.tree.expression.UnaryOperation;
import org.hibernate.sql.ast.tree.expression.UnparsedNumericLiteral;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.QueryPartTableReference;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.from.TableReferenceJoin;
import org.hibernate.sql.ast.tree.from.ValuesTableReference;
import org.hibernate.sql.ast.tree.insert.InsertSelectStatement;
import org.hibernate.sql.ast.tree.predicate.BetweenPredicate;
import org.hibernate.sql.ast.tree.predicate.BooleanExpressionPredicate;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.predicate.FilterPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InArrayPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.InSubQueryPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.LikePredicate;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.NullnessPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate;
import org.hibernate.sql.ast.tree.predicate.ThruthnessPredicate;
import org.hibernate.sql.ast.tree.select.QueryGroup;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectClause;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.ast.tree.select.SortSpecification;
import org.hibernate.sql.ast.tree.update.Assignment;
import org.hibernate.sql.ast.tree.update.UpdateStatement;
import org.hibernate.sql.exec.internal.AbstractJdbcParameter;
import org.hibernate.sql.exec.internal.JdbcOperationQuerySelect;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.exec.spi.JdbcSelect;
import org.hibernate.sql.model.ast.ColumnWriteFragment;
import org.hibernate.sql.model.internal.OptionalTableUpdate;
import org.hibernate.sql.model.internal.TableDeleteCustomSql;
import org.hibernate.sql.model.internal.TableDeleteStandard;
import org.hibernate.sql.model.internal.TableInsertCustomSql;
import org.hibernate.sql.model.internal.TableInsertStandard;
import org.hibernate.sql.model.internal.TableUpdateCustomSql;
import org.hibernate.sql.model.internal.TableUpdateStandard;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMappingProducerProvider;
import org.hibernate.type.BasicType;
import org.jspecify.annotations.Nullable;

/** Translates a Hibernate SELECT SQL AST directly to a MQLv2 text command. */
final class Mqlv2SelectTranslator implements SqlAstTranslator<JdbcSelect> {

    private record SpecTranslation(String mqlv2, List<String> fieldNames, Stage stage) {}

    private final SessionFactoryImplementor sessionFactory;
    private final SelectStatement selectStatement;
    private final List<JdbcParameterBinder> parameterBinders = new ArrayList<>();
    private final Serializer serializer = new Serializer();
    private @Nullable LimitJdbcParameter limitJdbcParameter;
    private boolean hasJoins;
    /**
     * Maps join-alias → array field path for unnest joins (struct arrays). Populated by
     * {@link Mqlv2IrEmitters#translateJoins} when emitting an unwind stage; consulted by
     * {@link Mqlv2IrEmitters#translateExpression} (via the shared map in {@link Mqlv2TranslationContext})
     * so that column references qualified by the unnest alias resolve to {@code <arrayPath>.<column>}.
     */
    private final Map<String, String> unnestAliasToFieldPath = new LinkedHashMap<>();
    /**
     * Maps aggregate AST node (by identity) → assigned alias name (e.g. {@code _agg0}). Populated during SELECT-clause
     * analysis by {@link #buildAggNames} and during HAVING-only aggregate collection by
     * {@link #collectHavingOnlyAggsInExpr}. Uses an {@link IdentityHashMap} because Hibernate may create structurally
     * equal AST nodes with distinct identities for the same aggregate in SELECT vs. HAVING; identity-keying is safe
     * because all lookups happen within a single translation pass.
     *
     * <p>When Hibernate produces two distinct node instances for the same aggregate in SELECT and HAVING,
     * {@link #aggSignatureIndex} serves as a fallback dedup index; see {@link #collectHavingOnlyAggsInExpr}.
     */
    private final IdentityHashMap<SelfRenderingFunctionSqlAstExpression<?>, String> aggregateAliases =
            new IdentityHashMap<>();
    /**
     * Secondary signature-keyed index of already-assigned aggregate aliases. Populated in parallel with
     * {@link #aggregateAliases} by {@link #buildAggNames}. Used as a fallback in
     * {@link #collectHavingOnlyAggsInExpr} to detect structurally-equal aggregates that appear in HAVING as distinct
     * node instances from their SELECT counterparts.
     */
    private final Map<String, String> aggSignatureIndex = new LinkedHashMap<>();
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
            var specTranslation = buildQuerySpecTranslation(querySpec, queryOptions);
            mqlv2Text = specTranslation.mqlv2();
            fieldNames = specTranslation.fieldNames();
        } else if (queryPart instanceof QueryGroup queryGroup) {
            var specTranslation = translateQueryGroupToMqlv2(queryGroup);
            mqlv2Text = specTranslation.mqlv2();
            fieldNames = specTranslation.fieldNames();
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported QueryPart: " + queryPart.getClass().getSimpleName());
        }

        var fieldNamesArray =
                new BsonArray(fieldNames.stream().map(BsonString::new).toList());
        var commandDoc = new BsonDocument("mqlv2", new BsonString(mqlv2Text))
                .append("_mqlv2FieldNames", fieldNamesArray)
                .append("_mqlv2ParamCount", new org.bson.BsonInt32(parameterBinders.size()));
        var commandJson = commandDoc.toJson();

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
     * Builds the MQLv2 pipeline translation for a sub-spec (UNION/INTERSECT/EXCEPT operand or correlated sub-query),
     * isolating translator state across the call so the parent spec's {@code hasJoins}, {@code aggregateAliases}, and
     * {@code unnestAliasToFieldPath} are preserved.
     *
     * <p>Inner subqueries that need outer-correlation support ({@code $__vN} bindings) should use
     * {@link Mqlv2IrEmitters#translateInnerQuerySpec} instead — this method is for set-operator operands that have
     * no implicit correlation with the outer scope.
     */
    private SpecTranslation buildSubQuerySpecTranslation(QuerySpec querySpec) {
        var savedHasJoins = this.hasJoins;
        var savedAggMap = new IdentityHashMap<SelfRenderingFunctionSqlAstExpression<?>, String>(this.aggregateAliases);
        var savedSigIndex = new LinkedHashMap<>(this.aggSignatureIndex);
        var savedUnnestMap = new LinkedHashMap<>(this.unnestAliasToFieldPath);
        this.aggregateAliases.clear();
        this.aggSignatureIndex.clear();
        this.unnestAliasToFieldPath.clear();
        try {
            return buildQuerySpecTranslation(querySpec, null);
        } finally {
            this.hasJoins = savedHasJoins;
            this.aggregateAliases.clear();
            this.aggregateAliases.putAll(savedAggMap);
            this.aggSignatureIndex.clear();
            this.aggSignatureIndex.putAll(savedSigIndex);
            this.unnestAliasToFieldPath.clear();
            this.unnestAliasToFieldPath.putAll(savedUnnestMap);
        }
    }

    /**
     * Builds the MQLv2 pipeline text and field-name list for a single QuerySpec by constructing a full driver-mqlv2
     * {@link com.mongodb.mqlv2.ast.Stage} chain and serializing it in one shot via {@link com.mongodb.mqlv2.Serializer}.
     * Pass non-null {@code queryOptions} at the top level to honour dynamic first/max rows; pass {@code null} for
     * sub-queries (UNION members, correlated sub-queries) where only the HQL literal LIMIT clause applies.
     *
     * <p>Does <em>not</em> save or restore translator state ({@code hasJoins}, {@code aggregateAliases},
     * {@code unnestAliasToFieldPath}); intended for top-level use or as the stateful callee of
     * {@link #buildSubQuerySpecTranslation}.
     *
     * <p>Subquery-based predicates (EXISTS, IN subquery, ANY/ALL) in WHERE/HAVING and scalar SELECT-position subqueries
     * are translated via {@link Mqlv2IrEmitters#translatePredicate} / {@link Mqlv2IrEmitters#translateExpression} when
     * the context has subquery support enabled.
     */
    private SpecTranslation buildQuerySpecTranslation(QuerySpec querySpec, @Nullable QueryOptions queryOptions) {
        var root = querySpec.getFromClause().getRoots().get(0);
        // Only count non-unnest joins: unnest joins translate to | unwind and don't introduce
        // a new MQLv2 alias, so they must not trigger the qualifier-prefix path in column-ref rendering.
        this.hasJoins = root.getTableGroupJoins().stream()
                .anyMatch(tgj -> !(tgj.getJoinedGroup().getPrimaryTableReference() instanceof FunctionTableReference ftr
                        && "unnest".equals(ftr.getFunctionExpression().getFunctionName())));

        var ntr = (NamedTableReference) root.getPrimaryTableReference();
        var outerQualifiers = collectOuterQualifiers(querySpec);
        var ctx = newContext().withOuterScope(outerQualifiers, () -> correlatedVarCounter++);
        var stage = Mqlv2IrEmitters.translateFromStage(ntr, hasJoins);
        stage = Mqlv2IrEmitters.translateJoins(stage, root, querySpec, ctx);
        stage = Mqlv2IrEmitters.translateMatch(stage, querySpec, ctx);

        List<@Nullable String> aggNames = null;
        if (!querySpec.getGroupByClauseExpressions().isEmpty()) {
            aggNames = buildAggNames(querySpec.getSelectClause());
            var havingOnlyAggs = collectHavingOnlyAggs(querySpec.getHavingClauseRestrictions());
            stage = Mqlv2IrEmitters.translateGroup(
                    stage,
                    querySpec.getGroupByClauseExpressions(),
                    querySpec.getSelectClause(),
                    aggNames,
                    havingOnlyAggs,
                    ctx);
            stage = Mqlv2IrEmitters.translateHaving(stage, querySpec, ctx);
        } else if (selectHasAggregates(querySpec.getSelectClause())) {
            aggNames = buildAggNames(querySpec.getSelectClause());
            stage = Mqlv2IrEmitters.translateScalarAgg(stage, querySpec.getSelectClause(), aggNames, ctx);
        }

        stage = Mqlv2IrEmitters.translateSort(stage, querySpec, ctx);
        stage = Mqlv2IrEmitters.translateLimit(stage, querySpec, queryOptions, ctx, () -> {
            var basicIntegerType = sessionFactory.getTypeConfiguration().getBasicTypeForJavaType(Integer.class);
            limitJdbcParameter = new LimitJdbcParameter(basicIntegerType);
            parameterBinders.add(limitJdbcParameter.getParameterBinder());
        });
        var fmt = Mqlv2IrEmitters.translateFormat(stage, querySpec.getSelectClause(), aggNames, ctx);
        stage = fmt.stage();
        if (querySpec.getSelectClause().isDistinct()) {
            stage = new Stage.DistinctStage(stage);
        }
        return new SpecTranslation(serializer.serialize(stage), fmt.fieldNames(), stage);
    }

    private SpecTranslation translateQueryGroupToMqlv2(QueryGroup queryGroup) {
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

        var subStages = translations.stream().map(SpecTranslation::stage).toList();
        Stage groupStage = Mqlv2IrEmitters.translateQueryGroupStage(queryGroup, subStages, () -> correlatedVarCounter++);
        return new SpecTranslation(serializer.serialize(groupStage), translations.get(0).fieldNames(), groupStage);
    }

    private Mqlv2TranslationContext newContext() {
        return new Mqlv2TranslationContext(parameterBinders, unnestAliasToFieldPath, hasJoins, aggregateAliases);
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

    private List<@Nullable String> buildAggNames(SelectClause selectClause) {
        var result = new ArrayList<@Nullable String>();
        var aggIdx = 0;
        for (var sel : selectClause.getSqlSelections()) {
            if (sel.isVirtual()) continue;
            if (isAggregateFunction(sel.getExpression())) {
                var fn = (SelfRenderingFunctionSqlAstExpression<?>) sel.getExpression();
                var name = "_agg" + aggIdx++;
                aggregateAliases.put(fn, name);
                // Also populate the signature index so that structurally-equal nodes from HAVING can be deduped
                // even when Hibernate constructs them as distinct instances.
                aggSignatureIndex.put(aggSignature(fn), name);
                result.add(name);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    private Map<String, SelfRenderingFunctionSqlAstExpression<?>> collectHavingOnlyAggs(@Nullable Predicate predicate) {
        var result = new LinkedHashMap<String, SelfRenderingFunctionSqlAstExpression<?>>();
        if (predicate != null && !predicate.isEmpty()) {
            collectHavingOnlyAggsInPredicate(predicate, result);
        }
        return result;
    }

    private void collectHavingOnlyAggsInPredicate(
            Predicate predicate, Map<String, SelfRenderingFunctionSqlAstExpression<?>> result) {
        if (predicate instanceof ComparisonPredicate cp) {
            collectHavingOnlyAggsInExpr(cp.getLeftHandExpression(), result);
            collectHavingOnlyAggsInExpr(cp.getRightHandExpression(), result);
        } else if (predicate instanceof Junction junction) {
            for (var p : junction.getPredicates()) collectHavingOnlyAggsInPredicate(p, result);
        } else if (predicate instanceof GroupedPredicate gp) {
            collectHavingOnlyAggsInPredicate(gp.getSubPredicate(), result);
        } else if (predicate instanceof NegatedPredicate np) {
            collectHavingOnlyAggsInPredicate(np.getPredicate(), result);
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            collectHavingOnlyAggsInExpr(bp.getExpression(), result);
        } else if (predicate instanceof InListPredicate ilp) {
            collectHavingOnlyAggsInExpr(ilp.getTestExpression(), result);
            for (var e : ilp.getListExpressions()) collectHavingOnlyAggsInExpr(e, result);
        }
    }

    private void collectHavingOnlyAggsInExpr(
            Expression expr, Map<String, SelfRenderingFunctionSqlAstExpression<?>> result) {
        if (isAggregateFunction(expr)) {
            var fn = (SelfRenderingFunctionSqlAstExpression<?>) expr;
            if (aggregateAliases.containsKey(fn)) {
                // Same instance already registered from SELECT; nothing to do.
                return;
            }
            // Hibernate sometimes creates distinct AST node instances for the same aggregate in SELECT vs. HAVING.
            // Fall back to signature-based dedup to detect structural equality across node instances.
            var sig = aggSignature(fn);
            var existingAlias = aggSignatureIndex.get(sig);
            if (existingAlias != null) {
                // A structurally-equal aggregate was already registered from SELECT; alias the HAVING node too
                // so that Mqlv2IrEmitters can find it via the identity-keyed aggregateAliases map.
                aggregateAliases.put(fn, existingAlias);
            } else {
                var name = "_agg" + aggregateAliases.size();
                aggregateAliases.put(fn, name);
                aggSignatureIndex.put(sig, name);
                result.put(name, fn);
            }
        } else if (expr instanceof BinaryArithmeticExpression bae) {
            collectHavingOnlyAggsInExpr(bae.getLeftHandOperand(), result);
            collectHavingOnlyAggsInExpr(bae.getRightHandOperand(), result);
        }
    }

    private static final Set<String> AGGREGATE_FUNCTION_NAMES = Set.of("count", "sum", "avg", "min", "max");

    private static boolean isAggregateFunction(Expression expr) {
        return expr instanceof SelfRenderingFunctionSqlAstExpression<?> fn
                && AGGREGATE_FUNCTION_NAMES.contains(fn.getFunctionName());
    }

    /**
     * Compute a structural signature string for an aggregate function node. Used solely by
     * {@link #aggSignatureIndex} as a fallback dedup key when Hibernate produces two distinct node instances for the
     * same aggregate expression in SELECT and HAVING.
     */
    private String aggSignature(SelfRenderingFunctionSqlAstExpression<?> fn) {
        var args = fn.getArguments();
        if (args.isEmpty()
                || args.get(0) instanceof Star
                || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            return fn.getFunctionName() + ":*";
        }
        if (args.get(0) instanceof Expression argExpr) {
            return fn.getFunctionName() + ":" + aggColumnSignature(argExpr);
        }
        return fn.getFunctionName() + ":?";
    }

    private String aggColumnSignature(Expression expr) {
        if (expr instanceof ColumnReference cr) {
            if (hasJoins && cr.getQualifier() != null && !cr.getQualifier().isEmpty()) {
                return cr.getQualifier() + "." + cr.getColumnExpression();
            }
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return aggColumnSignature(bvpi.getColumnReference());
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

    // SqlAstWalker visitor methods — only the ones used above are implemented;
    // all others throw FeatureNotSupportedException.

    @Override
    public void visitSelectStatement(SelectStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitDeleteStatement(DeleteStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitUpdateStatement(UpdateStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitInsertStatement(InsertSelectStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitAssignment(Assignment assignment) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitQueryGroup(QueryGroup queryGroup) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitQuerySpec(QuerySpec querySpec) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSortSpecification(SortSpecification sortSpecification) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitOffsetFetchClause(QueryPart querySpec) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSelectClause(SelectClause selectClause) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSqlSelection(org.hibernate.sql.ast.spi.SqlSelection sqlSelection) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitFromClause(org.hibernate.sql.ast.tree.from.FromClause fromClause) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitTableGroup(TableGroup tableGroup) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitTableGroupJoin(TableGroupJoin tableGroupJoin) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitNamedTableReference(NamedTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitValuesTableReference(ValuesTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitQueryPartTableReference(QueryPartTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitFunctionTableReference(FunctionTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitTableReferenceJoin(TableReferenceJoin tableReferenceJoin) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitColumnReference(ColumnReference columnReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitNestedColumnReference(NestedColumnReference nestedColumnReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitAggregateColumnWriteExpression(AggregateColumnWriteExpression aggregateColumnWriteExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitExtractUnit(ExtractUnit extractUnit) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitFormat(Format format) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitDistinct(Distinct distinct) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitOverflow(Overflow overflow) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitStar(Star star) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitTrimSpecification(TrimSpecification trimSpecification) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCastTarget(CastTarget castTarget) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitBinaryArithmeticExpression(BinaryArithmeticExpression arithmeticExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCaseSearchedExpression(CaseSearchedExpression caseSearchedExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCaseSimpleExpression(CaseSimpleExpression caseSimpleExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitAny(Any any) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitEvery(Every every) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSummarization(Summarization summarization) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitOver(Over<?> over) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSelfRenderingExpression(SelfRenderingExpression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSqlSelectionExpression(SqlSelectionExpression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitEntityTypeLiteral(EntityTypeLiteral expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitEmbeddableTypeLiteral(EmbeddableTypeLiteral expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitTuple(SqlTuple tuple) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCollation(Collation collation) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitParameter(JdbcParameter jdbcParameter) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitJdbcLiteral(JdbcLiteral<?> jdbcLiteral) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitQueryLiteral(QueryLiteral<?> queryLiteral) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public <N extends Number> void visitUnparsedNumericLiteral(UnparsedNumericLiteral<N> literal) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitUnaryOperationExpression(UnaryOperation unaryOperationExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitModifiedSubQueryExpression(ModifiedSubQueryExpression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitBooleanExpressionPredicate(BooleanExpressionPredicate booleanExpressionPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitBetweenPredicate(BetweenPredicate betweenPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitFilterPredicate(FilterPredicate filterPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitFilterFragmentPredicate(FilterPredicate.FilterFragmentPredicate fragmentPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSqlFragmentPredicate(SqlFragmentPredicate predicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitGroupedPredicate(GroupedPredicate groupedPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitInListPredicate(InListPredicate inListPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitInSubQueryPredicate(InSubQueryPredicate inSubQueryPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitInArrayPredicate(InArrayPredicate inArrayPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitExistsPredicate(ExistsPredicate existsPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitJunction(Junction junction) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitLikePredicate(LikePredicate likePredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitNegatedPredicate(NegatedPredicate negatedPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitNullnessPredicate(NullnessPredicate nullnessPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitThruthnessPredicate(ThruthnessPredicate predicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitRelationalPredicate(ComparisonPredicate comparisonPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitSelfRenderingPredicate(SelfRenderingPredicate selfRenderingPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitDurationUnit(DurationUnit durationUnit) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitDuration(Duration duration) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitConversion(org.hibernate.query.sqm.tree.expression.Conversion conversion) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitStandardTableInsert(TableInsertStandard tableInsert) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCustomTableInsert(TableInsertCustomSql tableInsert) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitStandardTableDelete(TableDeleteStandard tableDelete) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCustomTableDelete(TableDeleteCustomSql tableDelete) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitStandardTableUpdate(TableUpdateStandard tableUpdate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitOptionalTableUpdate(OptionalTableUpdate tableUpdate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitCustomTableUpdate(TableUpdateCustomSql tableUpdate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    public void visitColumnWriteFragment(ColumnWriteFragment columnWriteFragment) {
        throw new FeatureNotSupportedException();
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
