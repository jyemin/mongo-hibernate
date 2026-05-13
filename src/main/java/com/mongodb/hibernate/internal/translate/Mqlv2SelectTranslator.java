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
import org.hibernate.query.sqm.BinaryArithmeticOperator;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.SetOperator;
import org.hibernate.query.sqm.TemporalUnit;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
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
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;
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
import org.hibernate.sql.ast.tree.expression.UnparsedNumericLiteral;
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression;
import org.hibernate.sql.ast.tree.expression.SqlSelectionExpression;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.expression.Star;
import org.hibernate.sql.ast.tree.expression.Summarization;
import org.hibernate.sql.ast.tree.expression.TrimSpecification;
import org.hibernate.sql.ast.tree.expression.UnaryOperation;
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
import org.hibernate.sql.exec.spi.JdbcOperationQuerySelect;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.model.ast.ColumnWriteFragment;
import org.hibernate.sql.model.internal.TableDeleteCustomSql;
import org.hibernate.sql.model.internal.TableDeleteStandard;
import org.hibernate.sql.model.internal.TableInsertCustomSql;
import org.hibernate.sql.model.internal.TableInsertStandard;
import org.hibernate.sql.model.internal.TableUpdateCustomSql;
import org.hibernate.sql.model.internal.TableUpdateStandard;
import org.hibernate.sql.model.internal.OptionalTableUpdate;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMappingProducerProvider;
import org.jspecify.annotations.Nullable;

/** Translates a Hibernate SELECT SQL AST directly to a MQLv2 text command. */
final class Mqlv2SelectTranslator implements SqlAstTranslator<JdbcOperationQuerySelect> {

    private record SpecTranslation(String mqlv2, List<String> fieldNames) {}

    private final SessionFactoryImplementor sessionFactory;
    private final SelectStatement selectStatement;
    private final List<JdbcParameterBinder> parameterBinders = new ArrayList<>();
    private boolean hasJoins = false;
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
    public boolean supportsFilterClause() {
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
    public JdbcOperationQuerySelect translate(
            @Nullable JdbcParameterBindings jdbcParameterBindings, QueryOptions queryOptions) {

        var queryPart = selectStatement.getQueryPart();
        String mqlv2Text;
        List<String> fieldNames;

        if (queryPart instanceof QuerySpec querySpec) {
            var root = querySpec.getFromClause().getRoots().get(0);
            hasJoins = !root.getTableGroupJoins().isEmpty();
            var groupByExprs = querySpec.getGroupByClauseExpressions();
            var sb = new StringBuilder();
            appendFrom(sb, root);
            appendJoins(sb, root);
            appendMatch(sb, querySpec);
            List<@Nullable String> aggNames = null;
            if (!groupByExprs.isEmpty()) {
                aggNames = buildAggNames(querySpec.getSelectClause());
                appendGroup(sb, groupByExprs, querySpec.getSelectClause(), aggNames);
                appendHaving(sb, querySpec);
            }
            appendSort(sb, querySpec);
            appendLimit(sb, querySpec, queryOptions);
            fieldNames = appendFormat(sb, querySpec.getSelectClause(), aggNames);
            if (querySpec.getSelectClause().isDistinct()) {
                sb.append(" | distinct");
            }
            mqlv2Text = sb.toString();
        } else if (queryPart instanceof QueryGroup queryGroup) {
            var specTranslation = translateQueryGroupToMqlv2(queryGroup);
            mqlv2Text = specTranslation.mqlv2();
            fieldNames = specTranslation.fieldNames();
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported QueryPart: " + queryPart.getClass().getSimpleName());
        }

        var fieldNamesArray = new BsonArray(fieldNames.stream().map(BsonString::new).toList());
        var commandDoc = new BsonDocument("mqlv2", new BsonString(mqlv2Text))
                .append("_mqlv2FieldNames", fieldNamesArray)
                .append("_mqlv2ParamCount", new org.bson.BsonInt32(parameterBinders.size()));
        var commandJson = commandDoc.toJson();

        // For affected table names, walk the first QuerySpec's root
        var firstSpec = queryPart.getFirstQuerySpec();
        var affectedTableNames = collectAffectedTableNames(firstSpec.getFromClause().getRoots().get(0));
        var mappingProducerProvider =
                sessionFactory.getServiceRegistry().requireService(JdbcValuesMappingProducerProvider.class);
        var mappingProducer = mappingProducerProvider.buildMappingProducer(selectStatement, sessionFactory);
        return new JdbcOperationQuerySelect(
                commandJson, parameterBinders, mappingProducer, affectedTableNames, 0, MAX_VALUE, emptyMap(), NONE,
                null, null);
    }

    private SpecTranslation translateQuerySpecToMqlv2(QuerySpec querySpec) {
        var savedHasJoins = this.hasJoins;
        var savedAggMap = new LinkedHashMap<>(this.aggSignatureToName);
        this.hasJoins = !querySpec.getFromClause().getRoots().get(0).getTableGroupJoins().isEmpty();
        this.aggSignatureToName.clear();

        var sb = new StringBuilder();
        var root = querySpec.getFromClause().getRoots().get(0);
        appendFrom(sb, root);
        appendJoins(sb, root);
        appendMatch(sb, querySpec);
        List<@Nullable String> aggNames = null;
        if (!querySpec.getGroupByClauseExpressions().isEmpty()) {
            aggNames = buildAggNames(querySpec.getSelectClause());
            appendGroup(sb, querySpec.getGroupByClauseExpressions(), querySpec.getSelectClause(), aggNames);
            appendHaving(sb, querySpec);
        }
        appendSort(sb, querySpec);
        appendLimitToBuilder(sb, querySpec);
        var fieldNames = appendFormat(sb, querySpec.getSelectClause(), aggNames);
        if (querySpec.getSelectClause().isDistinct()) {
            sb.append(" | distinct");
        }

        this.hasJoins = savedHasJoins;
        this.aggSignatureToName.clear();
        this.aggSignatureToName.putAll(savedAggMap);
        return new SpecTranslation(sb.toString(), fieldNames);
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
        var mqlv2Pipelines = translations.stream().map(t -> "(" + t.mqlv2() + ")").toList();

        var mqlv2 = switch (operator) {
            case UNION_ALL -> buildArraySourcePipeline(mqlv2Pipelines);
            case UNION -> buildArraySourcePipeline(mqlv2Pipelines) + " | distinct";
            case INTERSECT -> {
                var left = translations.get(0).mqlv2();
                var right = translations.get(1).mqlv2();
                var varName = "$__v" + correlatedVarCounter++;
                yield left + " | match (count(let " + varName + " = $ in (" + right
                        + " | match ($ == " + varName + "))) > 0)";
            }
            case EXCEPT -> {
                var left = translations.get(0).mqlv2();
                var right = translations.get(1).mqlv2();
                var varName = "$__v" + correlatedVarCounter++;
                yield left + " | match (count(let " + varName + " = $ in (" + right
                        + " | match ($ == " + varName + "))) == 0)";
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

    private void appendJoins(StringBuilder sb, TableGroup root) {
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var joinNtr = (NamedTableReference) joinedGroup.getPrimaryTableReference();
            var joinCollName = joinNtr.getTableExpression();
            var joinAlias = joinNtr.getIdentificationVariable();
            var joinType = tgj.getJoinType();
            var joinKeyword = switch (joinType) {
                case INNER -> " | join ";
                case LEFT -> " | join leftOuter ";
                case RIGHT -> " | join rightOuter ";
                case FULL -> " | join fullOuter ";
                default -> throw new FeatureNotSupportedException("Unsupported join type: " + joinType);
            };
            sb.append(joinKeyword);
            sb.append(joinAlias).append("=$").append(joinCollName).append(" (");
            var joinPredicate = tgj.getPredicate();
            if (joinPredicate != null) {
                appendPredicateText(sb, joinPredicate);
            }
            sb.append(")");
        }
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
        if (queryOptions.getLimit() != null && queryOptions.getLimit().getMaxRows() != null) {
            throw new FeatureNotSupportedException(
                    "Use HQL LIMIT clause; setMaxResults() is not yet supported in MQLv2 mode");
        }
        appendLimitToBuilder(sb, querySpec);
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
     * Translates innerSpec to a MQLv2 pipeline text, binding correlated outer column
     * references as $__v0, $__v1, ... in correlatedBindings.
     */
    private String appendQuerySpecPipeline(
            QuerySpec innerSpec,
            Set<String> outerQualifiers,
            Map<String, String> correlatedBindings) {
        var innerSb = new StringBuilder();
        var root = innerSpec.getFromClause().getRoots().get(0);
        if (innerSpec.getFromClause().getRoots().size() != 1 || !root.getTableGroupJoins().isEmpty()) {
            throw new FeatureNotSupportedException(
                    "Subquery with joins or multiple FROM roots is not supported in MQLv2");
        }
        var ntr = (NamedTableReference) root.getPrimaryTableReference();
        innerSb.append("from $").append(ntr.getTableExpression());
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            innerSb.append(" | match ");
            appendPredicateTextCorrelated(innerSb, where, outerQualifiers, correlatedBindings);
        }
        return innerSb.toString();
    }

    private void appendPredicateTextCorrelated(
            StringBuilder sb,
            Predicate predicate,
            Set<String> outerQualifiers,
            Map<String, String> correlatedBindings) {
        if (predicate instanceof ComparisonPredicate cp) {
            sb.append("(");
            appendExprTextCorrelated(sb, cp.getLeftHandExpression(), outerQualifiers, correlatedBindings);
            sb.append(" ").append(comparisonOpSurface(cp.getOperator())).append(" ");
            appendExprTextCorrelated(sb, cp.getRightHandExpression(), outerQualifiers, correlatedBindings);
            sb.append(")");
        } else if (predicate instanceof Junction junction) {
            var preds = junction.getPredicates();
            var op = junction.getNature() == Junction.Nature.CONJUNCTION ? "and" : "or";
            sb.append("(");
            for (var i = 0; i < preds.size(); i++) {
                if (i > 0) sb.append(" ").append(op).append(" ");
                appendPredicateTextCorrelated(sb, preds.get(i), outerQualifiers, correlatedBindings);
            }
            sb.append(")");
        } else if (predicate instanceof NegatedPredicate np) {
            sb.append("(not ");
            appendPredicateTextCorrelated(sb, np.getPredicate(), outerQualifiers, correlatedBindings);
            sb.append(")");
        } else if (predicate instanceof NullnessPredicate np) {
            if (np.isNegated()) {
                sb.append("(not isNullish(");
                appendExprTextCorrelated(sb, np.getExpression(), outerQualifiers, correlatedBindings);
                sb.append("))");
            } else {
                sb.append("isNullish(");
                appendExprTextCorrelated(sb, np.getExpression(), outerQualifiers, correlatedBindings);
                sb.append(")");
            }
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            sb.append("(");
            appendExprTextCorrelated(sb, bp.getExpression(), outerQualifiers, correlatedBindings);
            sb.append(bp.isNegated() ? " == false)" : " == true)");
        } else if (predicate instanceof InListPredicate ilp) {
            var exprs = ilp.getListExpressions();
            var negated = ilp.isNegated();
            var op = negated ? " != " : " == ";
            var logic = negated ? " and " : " or ";
            sb.append("(");
            for (var i = 0; i < exprs.size(); i++) {
                if (i > 0) sb.append(logic);
                sb.append("(");
                appendExprTextCorrelated(sb, ilp.getTestExpression(), outerQualifiers, correlatedBindings);
                sb.append(op);
                appendExprTextCorrelated(sb, exprs.get(i), outerQualifiers, correlatedBindings);
                sb.append(")");
            }
            sb.append(")");
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate in subquery: " + predicate.getClass().getSimpleName());
        }
    }

    private void appendExprTextCorrelated(
            StringBuilder sb,
            Expression expression,
            Set<String> outerQualifiers,
            Map<String, String> correlatedBindings) {
        if (expression instanceof BasicValuedPathInterpretation<?> bvpi) {
            appendExprTextCorrelated(sb, bvpi.getColumnReference(), outerQualifiers, correlatedBindings);
        } else if (expression instanceof ColumnReference cr) {
            var qualifier = cr.getQualifier();
            if (qualifier != null && outerQualifiers.contains(qualifier)) {
                // Correlated outer reference: bind to a $__vN variable
                var key = qualifier + "." + cr.getColumnExpression();
                var varName = correlatedBindings.computeIfAbsent(
                        key, k -> "$__v" + correlatedVarCounter++);
                sb.append(varName);
            } else {
                // Inner column reference: the subquery is always a simple (non-join) scan,
                // so the qualifier is just Hibernate's internal alias — strip it.
                sb.append(cr.getColumnExpression());
            }
        } else {
            // Non-correlated: delegate to the standard path
            appendExprText(sb, expression);
        }
    }

    /**
     * Wraps innerPipeline with let bindings for all entries in correlatedBindings.
     * If correlatedBindings is empty, returns "(innerPipeline)" without a let clause.
     *
     * <p>correlatedBindings maps "qualifier.column" → "$__vN". The outer query is always a
     * simple (non-join) scan, so the binding value uses just the unqualified column name.
     */
    private static String wrapWithLet(
            String innerPipeline, Map<String, String> correlatedBindings) {
        if (correlatedBindings.isEmpty()) {
            return "(" + innerPipeline + ")";
        }
        var sb = new StringBuilder("let ");
        var first = true;
        for (var entry : correlatedBindings.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            // entry.getKey() = "qualifier.column" → use unqualified column name as the outer field ref
            var dotIdx = entry.getKey().indexOf('.');
            var fieldName = dotIdx >= 0 ? entry.getKey().substring(dotIdx + 1) : entry.getKey();
            sb.append(entry.getValue()).append(" = ").append(fieldName);
        }
        sb.append(" in (").append(innerPipeline).append(")");
        return sb.toString();
    }

    private static Set<String> collectOuterQualifiers(QuerySpec outerSpec) {
        var result = new LinkedHashSet<String>();
        for (var root : outerSpec.getFromClause().getRoots()) {
            var ntr = (NamedTableReference) root.getPrimaryTableReference();
            var alias = ntr.getIdentificationVariable();
            if (alias != null) result.add(alias);
            for (var tgj : root.getTableGroupJoins()) {
                var joinNtr = (NamedTableReference) tgj.getJoinedGroup().getPrimaryTableReference();
                var joinAlias = joinNtr.getIdentificationVariable();
                if (joinAlias != null) result.add(joinAlias);
            }
        }
        return result;
    }

    private List<@Nullable String> buildAggNames(SelectClause selectClause) {
        var result = new ArrayList<@Nullable String>();
        var aggIdx = 0;
        for (var sel : selectClause.getSqlSelections()) {
            if (sel.isVirtual()) continue;
            if (isAggregateFunction(sel.getExpression())) {
                var name = "_agg" + aggIdx++;
                aggSignatureToName.put(aggSignature((SelfRenderingFunctionSqlAstExpression) sel.getExpression()), name);
                result.add(name);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    private String aggSignature(SelfRenderingFunctionSqlAstExpression fn) {
        var args = fn.getArguments();
        if (args.isEmpty() || args.get(0) instanceof Star) {
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
            throw new FeatureNotSupportedException(
                    "Expected simple column reference in aggregate; got: " + expr.getClass().getSimpleName());
        }
    }

    private static final Set<String> AGGREGATE_FUNCTION_NAMES = Set.of("count", "sum", "avg", "min", "max");

    private static boolean isAggregateFunction(Expression expr) {
        return expr instanceof SelfRenderingFunctionSqlAstExpression fn
                && AGGREGATE_FUNCTION_NAMES.contains(fn.getFunctionName());
    }

    private void appendGroup(
            StringBuilder sb,
            List<Expression> groupByExprs,
            SelectClause selectClause,
            List<@Nullable String> aggNames) {
        sb.append(" | group (");
        for (var i = 0; i < groupByExprs.size(); i++) {
            if (i > 0) sb.append(", ");
            var expr = groupByExprs.get(i);
            var keyName = simpleColumnName(expr);
            sb.append(keyName).append("=");
            appendExprText(sb, expr);
        }
        sb.append(") (");
        var selections =
                selectClause.getSqlSelections().stream().filter(s -> !s.isVirtual()).toList();
        var first = true;
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(aggName).append("=");
            appendAggFunctionText(
                    sb, (SelfRenderingFunctionSqlAstExpression) selections.get(i).getExpression());
        }
        sb.append(")");
    }

    private void appendAggFunctionText(StringBuilder sb, SelfRenderingFunctionSqlAstExpression fn) {
        var name = fn.getFunctionName();
        var args = fn.getArguments();
        sb.append(name).append("(");
        if (args.isEmpty() || args.get(0) instanceof Star) {
            // count(*) / count() → count($): count all elements in the group
            sb.append("$");
        } else {
            if (!(args.get(0) instanceof Expression argExpr)) {
                throw new FeatureNotSupportedException(name + "() requires a field argument in MQLv2");
            }
            sb.append("$->").append(simpleColumnName(argExpr));
        }
        sb.append(")");
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

        var selections =
                selectClause.getSqlSelections().stream().filter(s -> !s.isVirtual()).toList();
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
                var valSb = new StringBuilder();
                appendExprText(valSb, selExpr);
                valueText = valSb.toString();
            } else if (selExpr instanceof BasicValuedPathInterpretation<?> bvpi) {
                key = bvpi.getColumnReference().getColumnExpression();
                var valSb = new StringBuilder();
                appendExprText(valSb, selExpr);
                valueText = valSb.toString();
            } else {
                key = "_f" + syntheticIdx++;
                var valSb = new StringBuilder();
                appendExprText(valSb, selExpr);
                valueText = valSb.toString();
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
     *   <li>ANY: {@code count(let $__vN = x in (inner | match (col op $__vN))) > 0}</li>
     *   <li>ALL: {@code count(let $__vN = x in (inner | match (col inverse_op $__vN))) == 0}</li>
     * </ul>
     */
    private void appendAnyAllPredicate(
            StringBuilder sb, ComparisonPredicate cp, SelectStatement subquery, boolean isAll) {
        var innerSpec = subquery.getQueryPart().getFirstQuerySpec();
        var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .findFirst()
                .orElseThrow(() -> new FeatureNotSupportedException(
                        "ANY/ALL subquery must project at least one column"))
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

        var letSb = new StringBuilder("let ").append(testVarName).append(" = ");
        appendExprText(letSb, cp.getLeftHandExpression());
        for (var entry : correlatedBindings.entrySet()) {
            letSb.append(", ").append(entry.getValue()).append(" = ");
            var key = entry.getKey();
            var dotIdx = key.indexOf('.');
            letSb.append(dotIdx >= 0 ? key.substring(dotIdx + 1) : key);
        }
        letSb.append(" in (").append(innerPipeline).append(")");

        sb.append("(count(").append(letSb).append(")").append(countCmp);
    }

    private void appendPredicateText(StringBuilder sb, Predicate predicate) {
        if (predicate instanceof ComparisonPredicate cp && cp.getRightHandExpression() instanceof Any anyExpr) {
            appendAnyAllPredicate(sb, cp, anyExpr.getSubquery(), false);
        } else if (predicate instanceof ComparisonPredicate cp && cp.getRightHandExpression() instanceof Every everyExpr) {
            appendAnyAllPredicate(sb, cp, everyExpr.getSubquery(), true);
        } else if (predicate instanceof ComparisonPredicate cp) {
            sb.append("(");
            appendExprText(sb, cp.getLeftHandExpression());
            sb.append(" ").append(comparisonOpSurface(cp.getOperator())).append(" ");
            appendExprText(sb, cp.getRightHandExpression());
            sb.append(")");
        } else if (predicate instanceof Junction junction) {
            var preds = junction.getPredicates();
            var op = junction.getNature() == Junction.Nature.CONJUNCTION ? "and" : "or";
            sb.append("(");
            for (var i = 0; i < preds.size(); i++) {
                if (i > 0) sb.append(" ").append(op).append(" ");
                appendPredicateText(sb, preds.get(i));
            }
            sb.append(")");
        } else if (predicate instanceof NegatedPredicate np) {
            sb.append("(not ");
            appendPredicateText(sb, np.getPredicate());
            sb.append(")");
        } else if (predicate instanceof NullnessPredicate np) {
            if (np.isNegated()) {
                sb.append("(not isNullish(");
                appendExprText(sb, np.getExpression());
                sb.append("))");
            } else {
                sb.append("isNullish(");
                appendExprText(sb, np.getExpression());
                sb.append(")");
            }
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            sb.append("(");
            appendExprText(sb, bp.getExpression());
            sb.append(bp.isNegated() ? " == false)" : " == true)");
        } else if (predicate instanceof InListPredicate ilp) {
            var exprs = ilp.getListExpressions();
            var negated = ilp.isNegated();
            var op = negated ? " != " : " == ";
            var logic = negated ? " and " : " or ";
            sb.append("(");
            for (var i = 0; i < exprs.size(); i++) {
                if (i > 0) sb.append(logic);
                sb.append("(");
                appendExprText(sb, ilp.getTestExpression());
                sb.append(op);
                appendExprText(sb, exprs.get(i));
                sb.append(")");
            }
            sb.append(")");
        } else if (predicate instanceof InSubQueryPredicate isp) {
            var innerSpec = isp.getSubQuery().getQueryPart().getFirstQuerySpec();
            var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual())
                    .findFirst()
                    .orElseThrow(() -> new FeatureNotSupportedException(
                            "IN subquery must project at least one column"))
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

            // Build the let expression manually:
            // first binding = test expression, then any correlated outer bindings from appendQuerySpecPipeline
            var letSb = new StringBuilder("let ").append(testVarName).append(" = ");
            appendExprText(letSb, isp.getTestExpression());
            for (var entry : correlatedBindings.entrySet()) {
                letSb.append(", ").append(entry.getValue()).append(" = ");
                // strip qualifier prefix (same as wrapWithLet)
                var key = entry.getKey();
                var dotIdx = key.indexOf('.');
                letSb.append(dotIdx >= 0 ? key.substring(dotIdx + 1) : key);
            }
            letSb.append(" in (").append(innerPipeline).append(")");

            var countExpr = "count(" + letSb + ")";
            if (isp.isNegated()) {
                sb.append("(").append(countExpr).append(" == 0)");
            } else {
                sb.append("(").append(countExpr).append(" > 0)");
            }
        } else if (predicate instanceof ExistsPredicate ep) {
            var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();
            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
            var wrapped = wrapWithLet(innerPipeline, correlatedBindings);
            var countOp = ep.isNegated() ? " == 0)" : " > 0)";
            sb.append("(count(").append(wrapped).append(")").append(countOp);
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate: " + predicate.getClass().getSimpleName());
        }
    }

    private void appendExprText(StringBuilder sb, Expression expression) {
        if (expression instanceof BasicValuedPathInterpretation<?> bvpi) {
            appendExprText(sb, bvpi.getColumnReference());
        } else if (expression instanceof ColumnReference cr) {
            if (hasJoins && cr.getQualifier() != null && !cr.getQualifier().isEmpty()) {
                sb.append(cr.getQualifier()).append(".").append(cr.getColumnExpression());
            } else {
                sb.append(cr.getColumnExpression());
            }
        } else if (expression instanceof QueryLiteral<?> ql) {
            appendLiteralText(sb, ql.getLiteralValue());
        } else if (expression instanceof UnparsedNumericLiteral<?> unl) {
            sb.append(unl.getLiteralValue());
        } else if (expression instanceof SqmParameterInterpretation spi) {
            appendExprText(sb, spi.getResolvedExpression());
        } else if (expression instanceof JdbcParameter jp) {
            sb.append("$p").append(parameterBinders.size());
            parameterBinders.add(jp.getParameterBinder());
        } else if (expression instanceof BinaryArithmeticExpression bae) {
            sb.append("(");
            appendExprText(sb, bae.getLeftHandOperand());
            sb.append(" ").append(arithmeticOpText(bae.getOperator())).append(" ");
            appendExprText(sb, bae.getRightHandOperand());
            sb.append(")");
        } else if (expression instanceof SelfRenderingFunctionSqlAstExpression fn) {
            if (isAggregateFunction(expression)) {
                var aggName = aggSignatureToName.get(aggSignature(fn));
                if (aggName == null) {
                    throw new FeatureNotSupportedException(
                            "Aggregate function in expression not found in SELECT: " + fn.getFunctionName() + "()");
                }
                sb.append(aggName);
            } else if ("extract".equals(fn.getFunctionName())) {
                var args = fn.getArguments();
                if (args.size() != 2 || !(args.get(0) instanceof ExtractUnit eu)) {
                    throw new FeatureNotSupportedException("Unsupported extract() form");
                }
                if (!(args.get(1) instanceof Expression dateExpr)) {
                    throw new FeatureNotSupportedException("Non-expression date argument in extract()");
                }
                sb.append(mqlv2ExtractName(eu.getUnit())).append("(");
                appendExprText(sb, dateExpr);
                sb.append(")");
            } else {
                throw new FeatureNotSupportedException("Unsupported function: " + fn.getFunctionName() + "()");
            }
        } else if (expression instanceof SelectStatement ss) {
            var innerSpec = ss.getQueryPart().getFirstQuerySpec();
            var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual())
                    .toList();
            if (selections.size() != 1) {
                throw new FeatureNotSupportedException(
                        "Scalar subquery in SELECT must project exactly one column");
            }
            var selExpr = selections.get(0).getExpression();
            // Only count() is supported: MQLv2 count(pipeline) returns the pipeline cardinality.
            // Other aggregates (sum, avg, min, max) have no equivalent pipeline-argument form in MQLv2.
            if (!(selExpr instanceof SelfRenderingFunctionSqlAstExpression fn)
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
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported expression: " + expression.getClass().getSimpleName());
        }
    }

    private static void appendLiteralText(StringBuilder sb, Object value) {
        if (value instanceof String s) {
            sb.append('"');
            for (var i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    sb.append("\\\"");
                } else if (c == '\\') {
                    sb.append("\\\\");
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value == null) {
            sb.append("null");
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported literal type: " + value.getClass().getSimpleName());
        }
    }

    private static String arithmeticOpText(BinaryArithmeticOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE, DIVIDE_PORTABLE, QUOT -> "/";
            default -> throw new FeatureNotSupportedException("Unsupported arithmetic operator: " + op);
        };
    }

    private static String mqlv2ExtractName(TemporalUnit unit) {
        return switch (unit) {
            case YEAR -> "year";
            case MONTH -> "month";
            case DAY, DAY_OF_MONTH -> "dayOfMonth";
            case DAY_OF_YEAR -> "dayOfYear";
            case DAY_OF_WEEK -> "dayOfWeek";
            case HOUR -> "hour";
            case MINUTE -> "minute";
            case SECOND -> "second";
            default -> throw new FeatureNotSupportedException("Unsupported extract() unit: " + unit);
        };
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
     * <p>For {@code x op ANY(subquery)} we want to count rows {@code e} in the subquery where
     * {@code x op e}, i.e., {@code $__v0 op col}. Written with the column on the left:
     * {@code col swapOp $__v0}, where swapOp reverses the operand order.
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
     * <p>For {@code x op ALL(subquery)} we count rows {@code e} in the subquery where
     * {@code NOT (x op e)}, i.e., {@code x inverseOp e}, i.e., {@code $__v0 inverseOp col}.
     * Written with the column on the left: {@code col swapInverseOp $__v0}.
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
        names.add(((NamedTableReference) root.getPrimaryTableReference()).getTableExpression());
        for (var tgj : root.getTableGroupJoins()) {
            names.add(((NamedTableReference) tgj.getJoinedGroup().getPrimaryTableReference())
                    .getTableExpression());
        }
        return names;
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
    public void visitAggregateColumnWriteExpression(
            AggregateColumnWriteExpression aggregateColumnWriteExpression) {
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
}
