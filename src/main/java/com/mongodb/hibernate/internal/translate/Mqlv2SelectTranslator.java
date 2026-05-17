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
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.JoinType;
import com.mongodb.mqlv2.ast.Stage;
import com.mongodb.mqlv2.ast.Value;
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
import org.bson.BsonString;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.collections.Stack;
import org.hibernate.persister.internal.SqlFragmentPredicate;
import org.hibernate.query.SortDirection;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.SetOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.EntityValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.SqlAstJoinType;
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
import org.hibernate.sql.ast.tree.from.TableReference;
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

    private record SpecTranslation(String mqlv2, List<String> fieldNames) {}

    private final SessionFactoryImplementor sessionFactory;
    private final SelectStatement selectStatement;
    private final List<JdbcParameterBinder> parameterBinders = new ArrayList<>();
    private final Serializer serializer = new Serializer();
    private @org.jspecify.annotations.Nullable LimitJdbcParameter limitJdbcParameter = null;
    private boolean hasJoins = false;
    /**
     * Maps join-alias → array field path for unnest joins (struct arrays). Populated by appendJoins when emitting
     * {@code | unwind <arrayPath>}; consulted by appendExprText / appendAggFieldRef so that column references qualified
     * by the unnest alias resolve to {@code <arrayPath>.<column>}.
     */
    private final Map<String, String> unnestAliasToFieldPath = new LinkedHashMap<>();
    // Maps aggregate signature (e.g. "sum:total") → _aggN name; populated during GROUP BY translation
    private final Map<String, String> aggSignatureToName = new LinkedHashMap<>();
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

    private SpecTranslation translateQuerySpecToMqlv2(QuerySpec querySpec) {
        var savedHasJoins = this.hasJoins;
        var savedAggMap = new LinkedHashMap<>(this.aggSignatureToName);
        var savedUnnestMap = new LinkedHashMap<>(this.unnestAliasToFieldPath);
        this.aggSignatureToName.clear();
        this.unnestAliasToFieldPath.clear();
        try {
            return buildQuerySpecTranslation(querySpec, null);
        } finally {
            this.hasJoins = savedHasJoins;
            this.aggSignatureToName.clear();
            this.aggSignatureToName.putAll(savedAggMap);
            this.unnestAliasToFieldPath.clear();
            this.unnestAliasToFieldPath.putAll(savedUnnestMap);
        }
    }

    /**
     * Builds the MQLv2 pipeline text and field-name list for a single QuerySpec. Pass non-null {@code queryOptions} at
     * the top level to honour dynamic first/max rows; pass {@code null} for sub-queries (UNION members, correlated
     * sub-queries) where only the HQL literal LIMIT clause applies.
     *
     * <p>Phase D2: simple query shapes (no joins, no GROUP BY, no scalar aggregates) are routed through
     * {@link #buildQuerySpecTranslationViaIr} which builds a full driver-mqlv2 {@link com.mongodb.mqlv2.ast.Stage}
     * chain and serializes it in one shot. All other shapes fall through to the original hand-rolled path.
     */
    private SpecTranslation buildQuerySpecTranslation(QuerySpec querySpec, @Nullable QueryOptions queryOptions) {
        var sb = new StringBuilder();
        var root = querySpec.getFromClause().getRoots().get(0);
        // Only count non-unnest joins: unnest joins translate to | unwind and don't introduce
        // a new MQLv2 alias, so they must not trigger the qualifier-prefix path in appendExprText.
        this.hasJoins = root.getTableGroupJoins().stream()
                .anyMatch(tgj -> !isUnnestFunctionTable(tgj.getJoinedGroup().getPrimaryTableReference()));

        // Phase D2/D3/D4: route query shapes through the full IR path.
        // Guard: the IR path handles only shapes that Mqlv2IrEmitters.translatePredicate and
        // translateExpression cover. Subquery-based predicates (EXISTS, IN subquery, ANY/ALL) and
        // scalar subqueries in SELECT still require the hand-rolled path.
        boolean canUseIr = !whereHasSubqueryPredicates(querySpec.getWhereClauseRestrictions())
                && !selectHasSubqueryExpressions(querySpec.getSelectClause());
        if (canUseIr) {
            return buildQuerySpecTranslationViaIr(querySpec, queryOptions, root);
        }

        appendFrom(sb, root);
        appendJoins(sb, root, querySpec);
        appendMatch(sb, querySpec);
        List<@Nullable String> aggNames = null;
        if (!querySpec.getGroupByClauseExpressions().isEmpty()) {
            aggNames = buildAggNames(querySpec.getSelectClause());
            var havingOnlyAggs = collectHavingOnlyAggs(querySpec.getHavingClauseRestrictions());
            appendGroup(
                    sb, querySpec.getGroupByClauseExpressions(), querySpec.getSelectClause(), aggNames, havingOnlyAggs);
            appendHaving(sb, querySpec);
        } else if (selectHasAggregates(querySpec.getSelectClause())) {
            aggNames = buildAggNames(querySpec.getSelectClause());
            appendScalarAgg(sb, querySpec.getSelectClause(), aggNames);
        }
        appendSort(sb, querySpec);
        if (queryOptions != null) {
            appendLimit(sb, querySpec, queryOptions);
        } else {
            appendLimitToBuilder(sb, querySpec);
        }
        var fieldNames = appendFormat(sb, querySpec.getSelectClause(), aggNames);
        if (querySpec.getSelectClause().isDistinct()) {
            sb.append(" | distinct");
        }
        return new SpecTranslation(sb.toString(), fieldNames);
    }

    /**
     * IR-path implementation of {@link #buildQuerySpecTranslation} for query shapes handled by the IR path (no subquery
     * predicates, no scalar subqueries in SELECT). Builds a full driver-mqlv2 {@link com.mongodb.mqlv2.ast.Stage} chain
     * — FROM → optional MATCH → optional GROUP/AGG → optional HAVING → optional SORT → optional LIMIT → FORMAT →
     * optional DISTINCT — and serializes it in one shot via the {@link com.mongodb.mqlv2.Serializer}.
     */
    private SpecTranslation buildQuerySpecTranslationViaIr(
            QuerySpec querySpec, @Nullable QueryOptions queryOptions, TableGroup root) {
        var ntr = (NamedTableReference) root.getPrimaryTableReference();
        var ctx = newContext();
        var stage = Mqlv2IrEmitters.translateFromStage(ntr, hasJoins);
        stage = buildJoinsStage(stage, root, querySpec, ctx);
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
        return new SpecTranslation(serializer.serialize(stage), fmt.fieldNames());
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
                .map(p -> translateQuerySpecToMqlv2(p.getFirstQuerySpec()))
                .toList();
        var mqlv2Pipelines =
                translations.stream().map(t -> "(" + t.mqlv2() + ")").toList();

        var mqlv2 =
                switch (operator) {
                    case UNION_ALL -> buildArraySourcePipeline(mqlv2Pipelines);
                    case UNION -> buildArraySourcePipeline(mqlv2Pipelines) + " | distinct";
                    case INTERSECT -> {
                        var left = translations.get(0).mqlv2();
                        var right = translations.get(1).mqlv2();
                        var varName = "$__v" + correlatedVarCounter++;
                        yield left + " | match (count(let " + varName + " = $ in (" + right + " | match ($ == "
                                + varName + "))) > 0)";
                    }
                    case EXCEPT -> {
                        var left = translations.get(0).mqlv2();
                        var right = translations.get(1).mqlv2();
                        var varName = "$__v" + correlatedVarCounter++;
                        yield left + " | match (count(let " + varName + " = $ in (" + right + " | match ($ == "
                                + varName + "))) == 0)";
                    }
                    default -> throw new FeatureNotSupportedException("Unsupported set operator: " + operator);
                };
        return new SpecTranslation(mqlv2, translations.get(0).fieldNames());
    }

    private static String buildArraySourcePipeline(List<String> pipelines) {
        var sb = new StringBuilder("from << ");
        for (var i = 0; i < pipelines.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(pipelines.get(i));
        }
        return sb.append(" >> | unwind $*").toString();
    }

    private void appendFrom(StringBuilder sb, TableGroup root) {
        var tableRef = root.getPrimaryTableReference();
        if (!(tableRef instanceof NamedTableReference ntr)) {
            throw new FeatureNotSupportedException("Subquery in FROM (derived table) is not supported in MQLv2");
        }
        var collName = ntr.getTableExpression();
        if (!hasJoins) {
            sb.append("from $").append(collName);
        } else {
            var alias = ntr.getIdentificationVariable();
            sb.append("from ").append(alias).append("=$").append(collName);
        }
    }

    private void appendJoins(StringBuilder sb, TableGroup root, QuerySpec querySpec) {
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var primaryRef = joinedGroup.getPrimaryTableReference();
            if (isUnnestFunctionTable(primaryRef)) {
                var rootAlias = ((NamedTableReference) root.getPrimaryTableReference()).getIdentificationVariable();
                appendUnwindJoin(sb, tgj, (FunctionTableReference) primaryRef, querySpec, rootAlias);
                continue;
            }
            var joinNtr = (NamedTableReference) primaryRef;
            var joinCollName = joinNtr.getTableExpression();
            var joinAlias = joinNtr.getIdentificationVariable();
            var joinType = tgj.getJoinType();
            var joinKeyword =
                    switch (joinType) {
                        case INNER -> " | join ";
                        case LEFT -> " | join leftOuter ";
                        case RIGHT -> " | join rightOuter ";
                        case FULL -> " | join fullOuter ";
                        default -> throw new FeatureNotSupportedException("Unsupported join type: " + joinType);
                    };
            sb.append(joinKeyword);
            sb.append(joinAlias).append("=$").append(joinCollName);
            var joinPredicate = tgj.getPredicate();
            if (joinPredicate != null) {
                sb.append(" ");
                appendPredicateText(sb, joinPredicate);
            }
            appendJoins(sb, joinedGroup, querySpec);
        }
    }

    /**
     * Translates a plural-attribute join ({@code FROM O o JOIN o.array a}) into a MQLv2 complex unwind stage that
     * preserves the parent document:
     *
     * <pre>{@code | unwind $__elem = arrayField in {col1: col1, ..., arrayField: $__elem}}</pre>
     *
     * <p>The body object enumerates all parent-entity columns referenced anywhere in the QuerySpec (SELECT, WHERE,
     * ORDER BY, GROUP BY, HAVING), preserving them across the unwind stage. The array field itself is mapped to
     * {@code $__elem} (the current element variable), so subsequent pipeline stages can reference it as
     * {@code arrayField.subField}.
     *
     * <p>Records the alias→field-path mapping so column references qualified by the unnest alias ({@code a.sku})
     * resolve to {@code <arrayPath>.<column>} ({@code lineItems.sku}) in subsequent WHERE / SELECT / GROUP BY / ORDER
     * BY emission.
     */
    private void appendUnwindJoin(
            StringBuilder sb,
            TableGroupJoin tgj,
            FunctionTableReference ftr,
            QuerySpec querySpec,
            @Nullable String rootAlias) {
        var arrayPath = extractUnnestArrayPath(ftr);
        var alias = extractUnnestAlias(tgj.getJoinedGroup());
        // Render the array field name directly (without qualifier prefix) regardless of hasJoins,
        // because unwind targets the field on the current (outer) document, not a named alias.
        var arrayFieldPath = columnExpressionOf(arrayPath);
        var internalVar = "$__elem";

        // Collect all parent-entity column names referenced anywhere in the QuerySpec.
        // These must be enumerated in the unwind body to be preserved across the stage.
        var parentCols = new LinkedHashSet<String>();
        collectParentColumnNames(querySpec, rootAlias, parentCols);
        // Always include the array field itself: subsequent pipeline stages reference it as
        // arrayField.subField (e.g., lineItems.sku), so the unwind body must carry it forward.
        parentCols.add(arrayFieldPath);

        // Emit: | unwind $__elem = arrayField in {col1: col1, ..., arrayField: $__elem}
        // The array field is mapped to the element variable (a single struct document), allowing
        // subsequent | match (arrayField.subField == ...) stages to work. The | format stage
        // will then re-wrap it in an array so Hibernate's getArray() call succeeds.
        sb.append(" | unwind ")
                .append(internalVar)
                .append(" = ")
                .append(arrayFieldPath)
                .append(" in {");
        var first = true;
        for (var col : parentCols) {
            if (!first) sb.append(", ");
            first = false;
            if (col.equals(arrayFieldPath)) {
                sb.append(col).append(": ").append(internalVar);
            } else {
                sb.append(col).append(": ").append(col);
            }
        }
        sb.append("}");

        unnestAliasToFieldPath.put(alias, arrayFieldPath);
    }

    // ---- IR-path join translation (Phase D3) ----

    /**
     * IR-path counterpart of {@link #appendJoins}: traverses the table-group-join list and builds a chain of
     * {@link Stage} nodes ({@link Stage.UnwindComplexStage} for unnest joins, {@link Stage.JoinStage} for regular
     * joins).
     *
     * <p>Populates {@code unnestAliasToFieldPath} (via the shared map in {@code ctx}) so that subsequent
     * column-reference rendering resolves unnest aliases correctly.
     */
    private Stage buildJoinsStage(Stage prev, TableGroup root, QuerySpec querySpec, Mqlv2TranslationContext ctx) {
        var s = prev;
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var primaryRef = joinedGroup.getPrimaryTableReference();
            if (isUnnestFunctionTable(primaryRef)) {
                var rootAlias = ((NamedTableReference) root.getPrimaryTableReference()).getIdentificationVariable();
                s = buildUnnestJoinStage(s, tgj, (FunctionTableReference) primaryRef, querySpec, rootAlias);
            } else {
                var joinNtr = (NamedTableReference) primaryRef;
                var joinCollName = joinNtr.getTableExpression();
                var joinAlias = joinNtr.getIdentificationVariable();
                var joinPredicate = tgj.getPredicate();
                if (joinPredicate == null) {
                    throw new FeatureNotSupportedException("Join without ON condition is not supported in MQLv2");
                }
                var condExpr = Mqlv2IrEmitters.translatePredicate(joinPredicate, ctx);
                s = new Stage.JoinStage(
                        s, irJoinType(tgj.getJoinType()), joinAlias, new Expr.VarRef(joinCollName), condExpr);
                s = buildJoinsStage(s, joinedGroup, querySpec, ctx);
            }
        }
        return s;
    }

    /**
     * IR-path counterpart of {@link #appendUnwindJoin}: builds a {@link Stage.UnwindComplexStage} that explodes the
     * array field and preserves all parent-entity columns in the unwind body.
     *
     * <p>Also records the alias→array-field-path mapping in {@link #unnestAliasToFieldPath} so that subsequent
     * column-reference rendering resolves {@code alias.column} to {@code arrayFieldPath.column}.
     */
    private Stage buildUnnestJoinStage(
            Stage prev,
            TableGroupJoin tgj,
            FunctionTableReference ftr,
            QuerySpec querySpec,
            @Nullable String rootAlias) {
        var arrayPath = extractUnnestArrayPath(ftr);
        var alias = extractUnnestAlias(tgj.getJoinedGroup());
        // Unwind targets the field on the current (outer) document without qualifier prefix.
        var arrayFieldPath = columnExpressionOf(arrayPath);
        var internalVarName = "__elem";

        // Collect all parent-entity column names referenced anywhere in the QuerySpec.
        // These must be enumerated in the unwind body to be preserved across the stage.
        var parentCols = new LinkedHashSet<String>();
        collectParentColumnNames(querySpec, rootAlias, parentCols);
        // Always include the array field itself: subsequent pipeline stages reference it as
        // arrayField.subField (e.g., lineItems.sku), so the unwind body must carry it forward.
        parentCols.add(arrayFieldPath);

        // Build the body document: {col1: col1, ..., arrayField: $__elem}
        var fields = new ArrayList<Map.Entry<Expr, Expr>>();
        for (var col : parentCols) {
            Expr keyExpr = new Expr.ValueLit(new Value.VString(col));
            Expr valExpr;
            if (col.equals(arrayFieldPath)) {
                // Map the array field to the element variable so subsequent match stages work.
                valExpr = new Expr.VarRef(internalVarName);
            } else {
                valExpr = new Expr.FieldAccess(new Expr.CurrentValue(), col);
            }
            fields.add(Map.entry(keyExpr, valExpr));
        }
        var bodyExpr = new Expr.DocumentConstructor(fields);

        // Source expression: the array field on the current document.
        var sourceExpr = new Expr.FieldAccess(new Expr.CurrentValue(), arrayFieldPath);

        // Register the alias so downstream column-reference rendering resolves it correctly.
        unnestAliasToFieldPath.put(alias, arrayFieldPath);

        return new Stage.UnwindComplexStage(prev, internalVarName, sourceExpr, bodyExpr);
    }

    /**
     * Maps a Hibernate {@link SqlAstJoinType} to the driver-mqlv2 {@link JoinType}. Returns {@code null} for INNER
     * joins because {@link Stage.JoinStage} treats a {@code null} join type as an inner join (and the Serializer omits
     * the keyword).
     */
    private static @Nullable JoinType irJoinType(SqlAstJoinType joinType) {
        return switch (joinType) {
            case INNER -> null;
            case LEFT -> JoinType.LEFT_OUTER;
            case RIGHT -> JoinType.RIGHT_OUTER;
            case FULL -> JoinType.FULL_OUTER;
            default -> throw new FeatureNotSupportedException("Unsupported join type: " + joinType);
        };
    }

    /**
     * Collects all parent-entity column names referenced in the given {@code QuerySpec}. A column is considered
     * "parent" if its qualifier matches {@code rootAlias} (or is null/empty). This drives the body object inside the
     * complex unwind stage so that parent fields are preserved.
     *
     * <p>Scans SELECT, WHERE, ORDER BY, GROUP BY, and HAVING clauses.
     */
    private static void collectParentColumnNames(QuerySpec querySpec, @Nullable String rootAlias, Set<String> result) {
        // SELECT
        for (var sel : querySpec.getSelectClause().getSqlSelections()) {
            collectParentColsFromExpr(sel.getExpression(), rootAlias, result);
        }
        // WHERE
        var where = querySpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            collectParentColsFromPredicate(where, rootAlias, result);
        }
        // ORDER BY
        var sortSpecs = querySpec.getSortSpecifications();
        if (sortSpecs != null) {
            for (var sort : sortSpecs) {
                collectParentColsFromExpr(sort.getSortExpression(), rootAlias, result);
            }
        }
        // GROUP BY
        for (var expr : querySpec.getGroupByClauseExpressions()) {
            collectParentColsFromExpr(expr, rootAlias, result);
        }
        // HAVING
        var having = querySpec.getHavingClauseRestrictions();
        if (having != null && !having.isEmpty()) {
            collectParentColsFromPredicate(having, rootAlias, result);
        }
    }

    private static void collectParentColsFromPredicate(
            Predicate predicate, @Nullable String rootAlias, Set<String> result) {
        if (predicate instanceof ComparisonPredicate cp) {
            collectParentColsFromExpr(cp.getLeftHandExpression(), rootAlias, result);
            collectParentColsFromExpr(cp.getRightHandExpression(), rootAlias, result);
        } else if (predicate instanceof Junction junction) {
            for (var p : junction.getPredicates()) collectParentColsFromPredicate(p, rootAlias, result);
        } else if (predicate instanceof GroupedPredicate gp) {
            collectParentColsFromPredicate(gp.getSubPredicate(), rootAlias, result);
        } else if (predicate instanceof NegatedPredicate np) {
            collectParentColsFromPredicate(np.getPredicate(), rootAlias, result);
        } else if (predicate instanceof NullnessPredicate np) {
            collectParentColsFromExpr(np.getExpression(), rootAlias, result);
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            collectParentColsFromExpr(bp.getExpression(), rootAlias, result);
        } else if (predicate instanceof InListPredicate ilp) {
            collectParentColsFromExpr(ilp.getTestExpression(), rootAlias, result);
            for (var e : ilp.getListExpressions()) collectParentColsFromExpr(e, rootAlias, result);
        }
        // Other predicate types (EXISTS, IN subquery, etc.) are not walked — their subqueries
        // are separate query scopes and don't need to contribute columns to the unwind body.
    }

    private static void collectParentColsFromExpr(Expression expr, @Nullable String rootAlias, Set<String> result) {
        if (expr instanceof ColumnReference cr) {
            var q = cr.getQualifier();
            if (q == null || q.isEmpty() || q.equals(rootAlias)) {
                result.add(cr.getColumnExpression());
            }
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            collectParentColsFromExpr(bvpi.getColumnReference(), rootAlias, result);
        } else if (expr instanceof BinaryArithmeticExpression bae) {
            collectParentColsFromExpr(bae.getLeftHandOperand(), rootAlias, result);
            collectParentColsFromExpr(bae.getRightHandOperand(), rootAlias, result);
        } else if (expr instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
            for (var arg : fn.getArguments()) {
                if (arg instanceof Expression e) collectParentColsFromExpr(e, rootAlias, result);
            }
        } else if (expr instanceof SqmParameterInterpretation spi) {
            collectParentColsFromExpr(spi.getResolvedExpression(), rootAlias, result);
        }
        // Literals, parameters, subqueries — no column references to collect.
    }

    /**
     * Extracts the simple column expression from a path expression. Used to render unwind targets without qualifier
     * prefix.
     */
    private static String columnExpressionOf(Expression expr) {
        if (expr instanceof ColumnReference cr) {
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return bvpi.getColumnReference().getColumnExpression();
        }
        throw new FeatureNotSupportedException("Expected simple column reference for unwind path; got: "
                + expr.getClass().getSimpleName());
    }

    private void appendMatch(StringBuilder sb, QuerySpec querySpec) {
        appendMatchStage(sb, querySpec.getWhereClauseRestrictions());
    }

    private void appendHaving(StringBuilder sb, QuerySpec querySpec) {
        appendMatchStage(sb, querySpec.getHavingClauseRestrictions());
    }

    private void appendMatchStage(StringBuilder sb, @Nullable Predicate predicate) {
        if (predicate == null || predicate.isEmpty()) return;
        sb.append(" | match ");
        appendPredicateText(sb, predicate);
    }

    private void appendSort(StringBuilder sb, QuerySpec querySpec) {
        if (!querySpec.hasSortSpecifications()) return;
        sb.append(" | sort ");
        var specs = querySpec.getSortSpecifications();
        for (var i = 0; i < specs.size(); i++) {
            if (i > 0) sb.append(", ");
            appendSortSpec(sb, specs.get(i));
        }
    }

    private void appendSortSpec(StringBuilder sb, SortSpecification spec) {
        appendExprText(sb, spec.getSortExpression());
        if (spec.getSortOrder() == SortDirection.DESCENDING) {
            sb.append(" desc");
        }
        // ASCENDING is default in MQLv2, omit
    }

    private void appendLimit(StringBuilder sb, QuerySpec querySpec, QueryOptions queryOptions) {
        var limit = queryOptions.getLimit();
        if (limit != null && limit.getFirstRow() != null) {
            throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
        }
        if (limit != null && limit.getMaxRows() != null) {
            var basicIntegerType = sessionFactory.getTypeConfiguration().getBasicTypeForJavaType(Integer.class);
            sb.append(" | limit ");
            limitJdbcParameter = new LimitJdbcParameter(basicIntegerType);
            appendExprText(sb, limitJdbcParameter);
        } else {
            appendLimitToBuilder(sb, querySpec);
        }
    }

    private void appendLimitToBuilder(StringBuilder sb, QuerySpec querySpec) {
        var fetchExpr = querySpec.getFetchClauseExpression();
        if (fetchExpr == null) return;
        if (querySpec.getOffsetClauseExpression() != null) {
            throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
        }
        sb.append(" | limit ");
        appendExprText(sb, fetchExpr);
    }

    /**
     * Translates innerSpec to a MQLv2 pipeline text, binding correlated outer column references as $__v0, $__v1, ... in
     * correlatedBindings.
     */
    private String appendQuerySpecPipeline(
            QuerySpec innerSpec, Set<String> outerQualifiers, Map<String, String> correlatedBindings) {
        var innerSb = new StringBuilder();
        var root = innerSpec.getFromClause().getRoots().get(0);
        if (innerSpec.getFromClause().getRoots().size() != 1
                || !root.getTableGroupJoins().isEmpty()) {
            throw new FeatureNotSupportedException(
                    "Subquery with joins or multiple FROM roots is not supported in MQLv2");
        }
        var ntr = (NamedTableReference) root.getPrimaryTableReference();
        innerSb.append("from $").append(ntr.getTableExpression());
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            innerSb.append(" | match ");
            appendPredicateTextWithResolver(
                    innerSb, where, outerCorrelatedResolver(outerQualifiers, correlatedBindings));
        }
        return innerSb.toString();
    }

    /**
     * Decides how a {@link ColumnReference} encountered inside a predicate/expression walker is rendered.
     * Implementations: outer-correlated (used by existing EXISTS/IN/ANY/ALL paths) and inside-any (used by Phase 2).
     */
    @FunctionalInterface
    private interface ColumnReferenceResolver {
        void render(StringBuilder sb, ColumnReference cr);
    }

    /** Translates a {@link SelfRenderingFunctionSqlAstExpression} to an IR {@link Expr} node. */
    @FunctionalInterface
    private interface IrFunctionTranslator {
        Expr translate(SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx);
    }

    /**
     * Returns a {@link ColumnReferenceResolver} that implements the existing outer-correlated logic: references to
     * outer-query aliases are bound to {@code $__vN} variables; all others have their qualifier stripped (inner
     * subquery alias is just a Hibernate internal alias).
     */
    private ColumnReferenceResolver outerCorrelatedResolver(
            Set<String> outerQualifiers, Map<String, String> correlatedBindings) {
        return (sb, cr) -> {
            var qualifier = cr.getQualifier();
            if (qualifier != null && outerQualifiers.contains(qualifier)) {
                // Correlated outer reference: bind to a $__vN variable
                var key = qualifier + "." + cr.getColumnExpression();
                var varName = correlatedBindings.computeIfAbsent(key, k -> "$__v" + correlatedVarCounter++);
                sb.append(varName);
            } else {
                // Inner column reference: the subquery is always a simple (non-join) scan,
                // so the qualifier is just Hibernate's internal alias — strip it.
                sb.append(cr.getColumnExpression());
            }
        };
    }

    /**
     * Builds a resolver for column references encountered inside an {@code any} body. Rule:
     *
     * <ul>
     *   <li>Qualifier matches an unnest alias on the stack → {@code $.<column>} (resolved against the current element
     *       of the array).
     *   <li>Qualifier matches an outer-query alias → {@code $__vN} via the outer-correlated path.
     *   <li>Qualifier is null → {@code $.<column>} (treat as current element).
     *   <li>Otherwise → {@code FeatureNotSupportedException}.
     * </ul>
     */
    private ColumnReferenceResolver insideAnyResolver(
            List<String> unnestAliasStack, Set<String> outerQualifiers, Map<String, String> correlatedBindings) {
        var outerResolver = outerCorrelatedResolver(outerQualifiers, correlatedBindings);
        return (sb, cr) -> {
            var qualifier = cr.getQualifier();
            if (qualifier != null && unnestAliasStack.contains(qualifier)) {
                sb.append("$.").append(cr.getColumnExpression());
            } else if (qualifier != null && outerQualifiers.contains(qualifier)) {
                outerResolver.render(sb, cr);
            } else if (qualifier == null) {
                // Unqualified ref inside any body — treat as the current element.
                sb.append("$.").append(cr.getColumnExpression());
            } else {
                throw new FeatureNotSupportedException(
                        "Reference to alias '" + qualifier + "' inside unnest body is not in scope");
            }
        };
    }

    private void appendPredicateTextWithResolver(
            StringBuilder sb, Predicate predicate, ColumnReferenceResolver resolver) {
        if (predicate instanceof ComparisonPredicate cp) {
            sb.append("(");
            appendExprTextWithResolver(sb, cp.getLeftHandExpression(), resolver);
            sb.append(" ").append(comparisonOpSurface(cp.getOperator())).append(" ");
            appendExprTextWithResolver(sb, cp.getRightHandExpression(), resolver);
            sb.append(")");
        } else if (predicate instanceof Junction junction) {
            var preds = junction.getPredicates();
            var op = junction.getNature() == Junction.Nature.CONJUNCTION ? "and" : "or";
            sb.append("(");
            for (var i = 0; i < preds.size(); i++) {
                if (i > 0) sb.append(" ").append(op).append(" ");
                appendPredicateTextWithResolver(sb, preds.get(i), resolver);
            }
            sb.append(")");
        } else if (predicate instanceof NegatedPredicate np) {
            sb.append("(not ");
            appendPredicateTextWithResolver(sb, np.getPredicate(), resolver);
            sb.append(")");
        } else if (predicate instanceof NullnessPredicate np) {
            if (np.isNegated()) {
                sb.append("(not isNullish(");
                appendExprTextWithResolver(sb, np.getExpression(), resolver);
                sb.append("))");
            } else {
                sb.append("isNullish(");
                appendExprTextWithResolver(sb, np.getExpression(), resolver);
                sb.append(")");
            }
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            sb.append("(");
            appendExprTextWithResolver(sb, bp.getExpression(), resolver);
            sb.append(bp.isNegated() ? " == false)" : " == true)");
        } else if (predicate instanceof GroupedPredicate gp) {
            appendPredicateTextWithResolver(sb, gp.getSubPredicate(), resolver);
        } else if (predicate instanceof InListPredicate ilp) {
            var exprs = ilp.getListExpressions();
            var negated = ilp.isNegated();
            var op = negated ? " != " : " == ";
            var logic = negated ? " and " : " or ";
            sb.append("(");
            for (var i = 0; i < exprs.size(); i++) {
                if (i > 0) sb.append(logic);
                sb.append("(");
                appendExprTextWithResolver(sb, ilp.getTestExpression(), resolver);
                sb.append(op);
                appendExprTextWithResolver(sb, exprs.get(i), resolver);
                sb.append(")");
            }
            sb.append(")");
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate in subquery: " + predicate.getClass().getSimpleName());
        }
    }

    private void appendExprTextWithResolver(StringBuilder sb, Expression expression, ColumnReferenceResolver resolver) {
        if (expression instanceof BasicValuedPathInterpretation<?> bvpi) {
            appendExprTextWithResolver(sb, bvpi.getColumnReference(), resolver);
        } else if (expression instanceof ColumnReference cr) {
            resolver.render(sb, cr);
        } else {
            // Non-correlated: delegate to the standard path
            appendExprText(sb, expression);
        }
    }

    /**
     * Wraps innerPipeline with let bindings for all entries in correlatedBindings (EXISTS pattern). If
     * correlatedBindings is empty, returns "(innerPipeline)" without a let clause.
     *
     * <p>correlatedBindings maps "qualifier.column" → "$__vN". When the outer query is a simple scan
     * ({@code hasJoins=false}) the binding value is the unqualified column name; when the outer query has joins
     * ({@code hasJoins=true}) the full "qualifier.column" path is used so the reference is unambiguous in MQLv2's
     * aliased-join document context.
     */
    private String wrapWithLet(String innerPipeline, Map<String, String> correlatedBindings) {
        if (correlatedBindings.isEmpty()) {
            return "(" + innerPipeline + ")";
        }
        var sb = new StringBuilder("let ");
        var first = true;
        for (var entry : correlatedBindings.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getValue()).append(" = ").append(correlatedFieldExpr(entry.getKey()));
        }
        sb.append(" in (").append(innerPipeline).append(")");
        return sb.toString();
    }

    /**
     * Wraps innerPipeline with a head binding followed by correlated bindings (IN/ANY/ALL pattern). Produces:
     * {@code let headVarName = headValueText[, $__vN = field, ...] in (innerPipeline)}.
     */
    private String wrapWithLet(
            String innerPipeline, String headVarName, String headValueText, Map<String, String> correlatedBindings) {
        var sb = new StringBuilder("let ").append(headVarName).append(" = ").append(headValueText);
        for (var entry : correlatedBindings.entrySet()) {
            sb.append(", ").append(entry.getValue()).append(" = ").append(correlatedFieldExpr(entry.getKey()));
        }
        sb.append(" in (").append(innerPipeline).append(")");
        return sb.toString();
    }

    /**
     * Returns the MQLv2 field expression for a correlated outer binding key ("qualifier.column"). Uses the qualified
     * form in a join context so the reference is unambiguous.
     */
    private String correlatedFieldExpr(String qualifiedKey) {
        if (hasJoins) {
            return qualifiedKey;
        }
        var dotIdx = qualifiedKey.indexOf('.');
        return dotIdx >= 0 ? qualifiedKey.substring(dotIdx + 1) : qualifiedKey;
    }

    private Mqlv2TranslationContext newContext() {
        return new Mqlv2TranslationContext(parameterBinders, unnestAliasToFieldPath, hasJoins, aggSignatureToName);
    }

    private static Set<String> collectOuterQualifiers(QuerySpec outerSpec) {
        var result = new LinkedHashSet<String>();
        for (var root : outerSpec.getFromClause().getRoots()) {
            collectGroupQualifiers(root, result);
        }
        return result;
    }

    private static void collectGroupQualifiers(TableGroup group, Set<String> result) {
        var alias = ((NamedTableReference) group.getPrimaryTableReference()).getIdentificationVariable();
        if (alias != null) result.add(alias);
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
                var name = "_agg" + aggIdx++;
                aggSignatureToName.put(
                        aggSignature((SelfRenderingFunctionSqlAstExpression<?>) sel.getExpression()), name);
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
            var sig = aggSignature(fn);
            if (!aggSignatureToName.containsKey(sig)) {
                var name = "_agg" + aggSignatureToName.size();
                aggSignatureToName.put(sig, name);
                result.put(name, fn);
            }
        } else if (expr instanceof BinaryArithmeticExpression bae) {
            collectHavingOnlyAggsInExpr(bae.getLeftHandOperand(), result);
            collectHavingOnlyAggsInExpr(bae.getRightHandOperand(), result);
        }
    }

    private String aggSignature(SelfRenderingFunctionSqlAstExpression<?> fn) {
        var args = fn.getArguments();
        if (args.isEmpty() || args.get(0) instanceof Star || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            return fn.getFunctionName() + ":*";
        }
        if (args.get(0) instanceof Expression argExpr) {
            return fn.getFunctionName() + ":" + aggColumnSignature(argExpr);
        }
        return fn.getFunctionName() + ":?";
    }

    // Includes the table qualifier (when present) so that count(a.id) and count(b.id)
    // in a joined query produce distinct signatures.
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

    private static final Set<String> AGGREGATE_FUNCTION_NAMES = Set.of("count", "sum", "avg", "min", "max");

    private static boolean isAggregateFunction(Expression expr) {
        return expr instanceof SelfRenderingFunctionSqlAstExpression<?> fn
                && AGGREGATE_FUNCTION_NAMES.contains(fn.getFunctionName());
    }

    private void appendGroup(
            StringBuilder sb,
            List<Expression> groupByExprs,
            SelectClause selectClause,
            List<@Nullable String> aggNames,
            Map<String, SelfRenderingFunctionSqlAstExpression<?>> havingOnlyAggs) {
        sb.append(" | group (");
        for (var i = 0; i < groupByExprs.size(); i++) {
            if (i > 0) sb.append(", ");
            var expr = groupByExprs.get(i);
            var keyName = simpleColumnName(expr);
            sb.append(keyName).append("=");
            appendExprText(sb, expr);
        }
        sb.append(") (");
        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        var first = true;
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(aggName).append("=");
            appendAggFunctionText(sb, (SelfRenderingFunctionSqlAstExpression<?>)
                    selections.get(i).getExpression());
        }
        for (var entry : havingOnlyAggs.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey()).append("=");
            appendAggFunctionText(sb, entry.getValue());
        }
        sb.append(")");
    }

    private static boolean selectHasAggregates(SelectClause selectClause) {
        return selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .anyMatch(s -> isAggregateFunction(s.getExpression()));
    }

    /**
     * Returns true if the predicate tree contains any subquery-based predicate ({@link ExistsPredicate},
     * {@link InSubQueryPredicate}, or {@link ComparisonPredicate} with an {@link Any}/{@link Every} RHS) that requires
     * the hand-rolled path. Called by the Phase D2 guard to fall back gracefully.
     */
    private static boolean whereHasSubqueryPredicates(@Nullable Predicate predicate) {
        if (predicate == null || predicate.isEmpty()) {
            return false;
        }
        if (predicate instanceof ExistsPredicate) {
            return true;
        }
        if (predicate instanceof InSubQueryPredicate) {
            return true;
        }
        if (predicate instanceof ComparisonPredicate cp
                && (cp.getRightHandExpression() instanceof Any || cp.getRightHandExpression() instanceof Every)) {
            return true;
        }
        if (predicate instanceof Junction j) {
            return j.getPredicates().stream().anyMatch(Mqlv2SelectTranslator::whereHasSubqueryPredicates);
        }
        if (predicate instanceof NegatedPredicate np) {
            return whereHasSubqueryPredicates(np.getPredicate());
        }
        if (predicate instanceof GroupedPredicate gp) {
            return whereHasSubqueryPredicates(gp.getSubPredicate());
        }
        return false;
    }

    /**
     * Returns true if any non-virtual selection in the SELECT clause contains a scalar subquery
     * ({@link SelectStatement} in expression position), which requires the hand-rolled path.
     */
    private static boolean selectHasSubqueryExpressions(SelectClause selectClause) {
        return selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .anyMatch(s -> s.getExpression() instanceof SelectStatement);
    }

    private void appendScalarAgg(StringBuilder sb, SelectClause selectClause, List<@Nullable String> aggNames) {
        sb.append(" | agg {");
        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        var first = true;
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(aggName).append(": ");
            appendAggFunctionText(sb, (SelfRenderingFunctionSqlAstExpression<?>)
                    selections.get(i).getExpression());
        }
        sb.append("}");
    }

    private void appendAggFunctionText(StringBuilder sb, SelfRenderingFunctionSqlAstExpression<?> fn) {
        var name = fn.getFunctionName();
        var args = fn.getArguments();
        sb.append(name).append("(");
        if (args.isEmpty() || args.get(0) instanceof Star || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            // count(*) / count() / count(entity) → count($): count all elements
            sb.append("$");
        } else {
            if (!(args.get(0) instanceof Expression argExpr)) {
                throw new FeatureNotSupportedException(name + "() requires a field argument in MQLv2");
            }
            sb.append("$->");
            appendAggFieldRef(sb, argExpr);
        }
        sb.append(")");
    }

    /**
     * Appends the field reference for an aggregate function argument using {@code ->} at every level so that MQLv2's
     * auto-mapping over the group sequence is preserved end-to-end. Without joins: emits {@code column}. With joins:
     * emits {@code qualifier->column}.
     */
    private void appendAggFieldRef(StringBuilder sb, Expression expr) {
        ColumnReference cr;
        if (expr instanceof ColumnReference c) {
            cr = c;
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            cr = bvpi.getColumnReference();
        } else {
            throw new FeatureNotSupportedException("Expected column reference in aggregate; got: "
                    + expr.getClass().getSimpleName());
        }
        var qualifier = cr.getQualifier();
        if (qualifier != null && unnestAliasToFieldPath.containsKey(qualifier)) {
            sb.append(unnestAliasToFieldPath.get(qualifier)).append("->").append(cr.getColumnExpression());
        } else if (hasJoins && qualifier != null && !qualifier.isEmpty()) {
            sb.append(qualifier).append("->").append(cr.getColumnExpression());
        } else {
            sb.append(cr.getColumnExpression());
        }
    }

    private static String simpleColumnName(Expression expr) {
        if (expr instanceof ColumnReference cr) {
            return cr.getColumnExpression();
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            return bvpi.getColumnReference().getColumnExpression();
        } else {
            throw new FeatureNotSupportedException(
                    "Expected simple column reference; got: " + expr.getClass().getSimpleName());
        }
    }

    private List<String> appendFormat(
            StringBuilder sb, SelectClause selectClause, @Nullable List<@Nullable String> aggNames) {
        var fieldNames = new ArrayList<String>();
        var formatParts = new ArrayList<String>();
        var syntheticIdx = 0;

        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        // Collect the set of array field paths from unnest joins. These columns require re-wrapping
        // in a single-element array in the format stage so Hibernate's getArray() call succeeds.
        // (The | unwind body maps the array field to the element document for match purposes;
        // the format stage then wraps it back so the result set delivers an ARRAY.)
        var unnestArrayFields = new LinkedHashSet<>(unnestAliasToFieldPath.values());

        for (var i = 0; i < selections.size(); i++) {
            var selExpr = selections.get(i).getExpression();
            var aggName = aggNames != null ? aggNames.get(i) : null;

            String key;
            String valueText;
            if (aggName != null) {
                key = "_f" + syntheticIdx++;
                valueText = aggName;
            } else if (selExpr instanceof ColumnReference cr) {
                key = cr.getColumnExpression();
                // After a group stage, the key field is named by simpleColumnName (= key),
                // so reference it directly rather than via the pre-group qualified path.
                var rawValue = aggNames != null ? key : appendExprTextToString(selExpr);
                // Re-wrap unnest array fields: the unwind body emits the element as a struct;
                // the format stage must wrap it in [rawValue] so getArray() sees an ARRAY.
                valueText = (!unnestArrayFields.isEmpty() && unnestArrayFields.contains(key))
                        ? "[" + rawValue + "]"
                        : rawValue;
            } else if (selExpr instanceof BasicValuedPathInterpretation<?> bvpi) {
                key = bvpi.getColumnReference().getColumnExpression();
                var rawValue = aggNames != null ? key : appendExprTextToString(selExpr);
                valueText = (!unnestArrayFields.isEmpty() && unnestArrayFields.contains(key))
                        ? "[" + rawValue + "]"
                        : rawValue;
            } else {
                key = "_f" + syntheticIdx++;
                valueText = appendExprTextToString(selExpr);
            }

            fieldNames.add(key);
            formatParts.add(key + ": " + valueText);
        }

        sb.append(" | format {");
        for (var i = 0; i < formatParts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatParts.get(i));
        }
        sb.append("}");
        return fieldNames;
    }

    /**
     * Translates {@code x op ANY(subquery)} (isAll=false) or {@code x op ALL(subquery)} (isAll=true).
     *
     * <ul>
     *   <li>ANY: {@code count(let $__vN = x in (inner | match (col op $__vN))) > 0}
     *   <li>ALL: {@code count(let $__vN = x in (inner | match (col inverse_op $__vN))) == 0}
     * </ul>
     */
    private void appendAnyAllPredicate(
            StringBuilder sb, ComparisonPredicate cp, SelectStatement subquery, boolean isAll) {
        var innerSpec = subquery.getQueryPart().getFirstQuerySpec();
        var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .findFirst()
                .orElseThrow(
                        () -> new FeatureNotSupportedException("ANY/ALL subquery must project at least one column"))
                .getExpression();
        var projectedColName = simpleColumnName(projectedExpr);

        var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var outerQualifiers = collectOuterQualifiers(outerSpec);
        var correlatedBindings = new LinkedHashMap<String, String>();

        var testVarName = "$__v" + correlatedVarCounter++;
        var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);

        String matchOp;
        String countCmp;
        if (isAll) {
            matchOp = allMatchOp(cp.getOperator());
            countCmp = " == 0)";
        } else {
            matchOp = anyMatchOp(cp.getOperator());
            countCmp = " > 0)";
        }
        innerPipeline = innerPipeline + " | match (" + projectedColName + " " + matchOp + " " + testVarName + ")";

        var headSb = new StringBuilder();
        appendExprText(headSb, cp.getLeftHandExpression());
        var letExpr = wrapWithLet(innerPipeline, testVarName, headSb.toString(), correlatedBindings);
        sb.append("(count(").append(letExpr).append(")").append(countCmp);
    }

    /**
     * Translates {@code exists (select 1 from o.array a where <body>)} into MQLv2 {@code (<arrayPath> any
     * (<body-rewritten>))}, with outer-correlated references captured into a {@code let} wrapper around the {@code any}
     * expression. Negation wraps the whole thing in {@code (not …)}.
     */
    private void appendUnnestExistsPredicate(StringBuilder sb, ExistsPredicate ep) {
        var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();
        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = extractUnnestAlias(innerRoot);

        var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var outerQualifiers = collectOuterQualifiers(outerSpec);
        var correlatedBindings = new LinkedHashMap<String, String>();

        var arrayPathSb = new StringBuilder();
        appendExprText(arrayPathSb, arrayPath);

        var bodySb = new StringBuilder();
        appendPredicateTextWithResolver(
                bodySb,
                innerSpec.getWhereClauseRestrictions(),
                insideAnyResolver(List.of(unnestAlias), outerQualifiers, correlatedBindings));

        // wrapWithLet adds the outer parens (or a `let … in (...)` wrapper) around the
        // any expression. Negation wraps the whole thing in `(not …)`.
        var wrapped = wrapWithLet(arrayPathSb + " any " + bodySb, correlatedBindings);
        if (ep.isNegated()) {
            sb.append("(not ").append(wrapped).append(")");
        } else {
            sb.append(wrapped);
        }
    }

    /**
     * Translates {@code (SELECT count(*) FROM o.array a [WHERE <body>])} (when the inner FROM root is an unnest
     * function table) into MQLv2 {@code count((from <arrayPath> | match (<body>)))}, using the subpipeline-expression
     * form. Outer-correlated body references flow through the existing {@code $__vN} let-binding machinery.
     *
     * <p>Only {@code count()} is supported. Other aggregates ({@code max}, {@code sum}, etc.) have no pipeline-argument
     * form in MQLv2 and throw {@link FeatureNotSupportedException}.
     */
    private void appendUnnestScalarSubquery(StringBuilder sb, SelectStatement ss) {
        var innerSpec = ss.getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();

        var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        if (selections.size() != 1) {
            throw new FeatureNotSupportedException("Scalar subquery over unnest() must project exactly one column");
        }
        var selExpr = selections.get(0).getExpression();
        if (!(selExpr instanceof SelfRenderingFunctionSqlAstExpression<?> fn)
                || !"count".equals(fn.getFunctionName())) {
            throw new FeatureNotSupportedException(
                    "Scalar subquery over unnest() must use count(); other aggregates have no "
                            + "pipeline-argument form in MQLv2 yet");
        }

        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = extractUnnestAlias(innerRoot);

        var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var outerQualifiers = collectOuterQualifiers(outerSpec);
        var correlatedBindings = new LinkedHashMap<String, String>();

        var arrayPathSb = new StringBuilder();
        appendExprText(arrayPathSb, arrayPath);

        var innerPipelineSb = new StringBuilder("from ").append(arrayPathSb);
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            var bodySb = new StringBuilder();
            appendPredicateTextWithResolver(
                    bodySb, where, insideAnyResolver(List.of(unnestAlias), outerQualifiers, correlatedBindings));
            innerPipelineSb.append(" | match ").append(bodySb);
        }

        var subpipelineExpr = "(" + innerPipelineSb + ")";
        var countExpr = "count(" + subpipelineExpr + ")";
        sb.append(wrapWithLet(countExpr, correlatedBindings));
    }

    private void appendPredicateText(StringBuilder sb, Predicate predicate) {
        if (predicate instanceof ComparisonPredicate cp && cp.getRightHandExpression() instanceof Any anyExpr) {
            appendAnyAllPredicate(sb, cp, anyExpr.getSubquery(), false);
        } else if (predicate instanceof ComparisonPredicate cp
                && cp.getRightHandExpression() instanceof Every everyExpr) {
            appendAnyAllPredicate(sb, cp, everyExpr.getSubquery(), true);
        } else if (predicate instanceof ComparisonPredicate
                || predicate instanceof Junction
                || predicate instanceof GroupedPredicate
                || predicate instanceof NegatedPredicate
                || predicate instanceof NullnessPredicate
                || predicate instanceof BooleanExpressionPredicate
                || predicate instanceof InListPredicate
                || predicate instanceof SelfRenderingPredicate) {
            sb.append(serializer.serialize(Mqlv2IrEmitters.translatePredicate(predicate, newContext())));
        } else if (predicate instanceof InSubQueryPredicate isp) {
            // Note: IN-subquery over an unnest function table is Hibernate-SQM-blocked
            // (resolveSqmPath AssertionError on the unnest FunctionJoin path during type
            // inference in visitInSubQueryPredicate). The AST never reaches us, so no
            // dispatch hook is needed here. See Mqlv2UnnestSubqueryIntegrationTests for the
            // negative tests that lock the contract.
            var innerSpec = isp.getSubQuery().getQueryPart().getFirstQuerySpec();
            var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual())
                    .findFirst()
                    .orElseThrow(() -> new FeatureNotSupportedException("IN subquery must project at least one column"))
                    .getExpression();
            var projectedColName = simpleColumnName(projectedExpr);

            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();

            // Bind the test expression as the first $__vN variable
            var testVarName = "$__v" + correlatedVarCounter++;

            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
            // Add match stage: projected column == test variable
            innerPipeline = innerPipeline + " | match (" + projectedColName + " == " + testVarName + ")";

            var headSb = new StringBuilder();
            appendExprText(headSb, isp.getTestExpression());
            var letExpr = wrapWithLet(innerPipeline, testVarName, headSb.toString(), correlatedBindings);
            var countExpr = "count(" + letExpr + ")";
            if (isp.isNegated()) {
                sb.append("(").append(countExpr).append(" == 0)");
            } else {
                sb.append("(").append(countExpr).append(" > 0)");
            }
        } else if (predicate instanceof ExistsPredicate ep) {
            var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
            var innerRoot = innerSpec.getFromClause().getRoots().get(0);
            if (isUnnestFunctionTable(innerRoot.getPrimaryTableReference())) {
                appendUnnestExistsPredicate(sb, ep);
            } else {
                var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
                var outerQualifiers = collectOuterQualifiers(outerSpec);
                var correlatedBindings = new LinkedHashMap<String, String>();
                var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
                var wrapped = wrapWithLet(innerPipeline, correlatedBindings);
                var countOp = ep.isNegated() ? " == 0)" : " > 0)";
                sb.append("(count(").append(wrapped).append(")").append(countOp);
            }
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate: " + predicate.getClass().getSimpleName());
        }
    }

    /**
     * Appends the serialized IR text for a function expression. Constructs an IR node via {@code translator} (which
     * allocates any JDBC parameter binders as it walks the AST) and appends the serialized text to {@code sb}.
     */
    private void appendIrExprFunction(
            StringBuilder sb, SelfRenderingFunctionSqlAstExpression<?> fn, IrFunctionTranslator translator) {
        Expr ir = translator.translate(fn, newContext());
        sb.append(serializer.serialize(ir));
    }

    private String appendExprTextToString(Expression expression) {
        var sb = new StringBuilder();
        appendExprText(sb, expression);
        return sb.toString();
    }

    private static boolean isFoundationExpression(Expression expression) {
        return expression instanceof BasicValuedPathInterpretation<?>
                || expression instanceof ColumnReference
                || expression instanceof QueryLiteral<?>
                || expression instanceof UnparsedNumericLiteral<?>
                || expression instanceof SqmParameterInterpretation
                || expression instanceof JdbcParameter
                || expression instanceof BinaryArithmeticExpression;
    }

    private void appendExprText(StringBuilder sb, Expression expression) {
        if (isFoundationExpression(expression)) {
            Expr ir = Mqlv2IrEmitters.translateExpression(expression, newContext());
            sb.append(serializer.serialize(ir));
        } else if (expression instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
            if (isAggregateFunction(expression)) {
                var aggName = aggSignatureToName.get(aggSignature(fn));
                if (aggName == null) {
                    throw new FeatureNotSupportedException(
                            "Aggregate function in expression not found in SELECT: " + fn.getFunctionName() + "()");
                }
                sb.append(serializer.serialize(Mqlv2IrEmitters.translateAggregateReference(aggName)));
            } else if ("extract".equals(fn.getFunctionName())) {
                appendIrExprFunction(sb, fn, Mqlv2IrEmitters::translateExtract);
            } else if ("array_length".equals(fn.getFunctionName())) {
                appendIrExprFunction(sb, fn, Mqlv2IrEmitters::translateArrayLength);
            } else if ("array_get".equals(fn.getFunctionName())) {
                appendIrExprFunction(sb, fn, Mqlv2IrEmitters::translateArrayGet);
            } else if ("array".equals(fn.getFunctionName()) || "array_list".equals(fn.getFunctionName())) {
                appendIrExprFunction(sb, fn, Mqlv2IrEmitters::translateArrayConstructor);
            } else {
                throw new FeatureNotSupportedException("Unsupported function: " + fn.getFunctionName() + "()");
            }
        } else if (expression instanceof SelectStatement ss) {
            var innerSpec = ss.getQueryPart().getFirstQuerySpec();
            var innerRoot = innerSpec.getFromClause().getRoots().get(0);
            if (isUnnestFunctionTable(innerRoot.getPrimaryTableReference())) {
                appendUnnestScalarSubquery(sb, ss);
            } else {
                var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                        .filter(s -> !s.isVirtual())
                        .toList();
                if (selections.size() != 1) {
                    throw new FeatureNotSupportedException("Scalar subquery in SELECT must project exactly one column");
                }
                var selExpr = selections.get(0).getExpression();
                // Only count() is supported: MQLv2 count(pipeline) returns the pipeline cardinality.
                // Other aggregates (sum, avg, min, max) have no equivalent pipeline-argument form in MQLv2.
                if (!(selExpr instanceof SelfRenderingFunctionSqlAstExpression<?> fn)
                        || !"count".equals(fn.getFunctionName())) {
                    throw new FeatureNotSupportedException(
                            "Scalar subquery in SELECT must use count(); other aggregates are not supported");
                }
                var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
                var outerQualifiers = collectOuterQualifiers(outerSpec);
                var correlatedBindings = new LinkedHashMap<String, String>();
                var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
                var wrapped = wrapWithLet(innerPipeline, correlatedBindings);
                sb.append("count(").append(wrapped).append(")");
            }
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported expression: " + expression.getClass().getSimpleName());
        }
    }

    private static String comparisonOpSurface(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> "==";
            case NOT_EQUAL -> "!=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            default -> throw new FeatureNotSupportedException("Unsupported comparison operator: " + op);
        };
    }

    /**
     * Returns the operator string for {@code col matchOp $__v0} in an ANY match stage.
     *
     * <p>For {@code x op ANY(subquery)} we want to count rows {@code e} in the subquery where {@code x op e}, i.e.,
     * {@code $__v0 op col}. Written with the column on the left: {@code col swapOp $__v0}, where swapOp reverses the
     * operand order.
     */
    private static String anyMatchOp(ComparisonOperator op) {
        // swap operand order: a > b becomes b < a
        return switch (op) {
            case EQUAL -> "==";
            case NOT_EQUAL -> "!=";
            case LESS_THAN -> ">";
            case LESS_THAN_OR_EQUAL -> ">=";
            case GREATER_THAN -> "<";
            case GREATER_THAN_OR_EQUAL -> "<=";
            default -> throw new FeatureNotSupportedException("Unsupported comparison operator: " + op);
        };
    }

    /**
     * Returns the operator string for {@code col matchOp $__v0} in an ALL match stage.
     *
     * <p>For {@code x op ALL(subquery)} we count rows {@code e} in the subquery where {@code NOT (x op e)}, i.e.,
     * {@code x inverseOp e}, i.e., {@code $__v0 inverseOp col}. Written with the column on the left: {@code col
     * swapInverseOp $__v0}.
     */
    private static String allMatchOp(ComparisonOperator op) {
        // negate then swap operand order
        return switch (op) {
            case EQUAL -> "!=";
            case NOT_EQUAL -> "==";
            case LESS_THAN -> "<=";
            case LESS_THAN_OR_EQUAL -> "<";
            case GREATER_THAN -> ">=";
            case GREATER_THAN_OR_EQUAL -> ">";
            default -> throw new FeatureNotSupportedException("Unsupported comparison operator: " + op);
        };
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

    // ---- Phase 2/3/4 unnest helpers ----

    /**
     * @return true iff the table reference is a {@link FunctionTableReference} whose function descriptor identifies as
     *     "unnest".
     */
    private static boolean isUnnestFunctionTable(TableReference ref) {
        return ref instanceof FunctionTableReference ftr
                && "unnest".equals(ftr.getFunctionExpression().getFunctionName());
    }

    /**
     * Returns the single argument expression to {@code unnest(<arg>)}. Throws {@link FeatureNotSupportedException} if
     * the argument is not a simple {@link ColumnReference} or {@link BasicValuedPathInterpretation} (which would mean
     * the user passed a literal array, a function call, or some other non-path expression).
     */
    private static Expression extractUnnestArrayPath(FunctionTableReference ftr) {
        var args = ftr.getFunctionExpression().getArguments();
        if (args.size() != 1) {
            throw new FeatureNotSupportedException("unnest() requires exactly one argument; got " + args.size());
        }
        var arg = args.get(0);
        if (arg instanceof ColumnReference || arg instanceof BasicValuedPathInterpretation<?>) {
            return (Expression) arg;
        }
        throw new FeatureNotSupportedException("unnest() argument must be a path expression on an outer entity; got: "
                + arg.getClass().getSimpleName());
    }

    /**
     * Returns the identification variable that aliases the rows of the unnest output (the "a" in {@code FROM o.array
     * a}).
     */
    private static String extractUnnestAlias(TableGroup group) {
        return ((FunctionTableReference) group.getPrimaryTableReference()).getIdentificationVariable();
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
