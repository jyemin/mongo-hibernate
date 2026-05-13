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
import java.util.LinkedHashSet;
import java.util.List;
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

    private final SessionFactoryImplementor sessionFactory;
    private final SelectStatement selectStatement;
    private final List<JdbcParameterBinder> parameterBinders = new ArrayList<>();
    private boolean hasJoins = false;

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

        var querySpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var root = querySpec.getFromClause().getRoots().get(0);
        hasJoins = !root.getTableGroupJoins().isEmpty();

        var sb = new StringBuilder();
        appendFrom(sb, root);
        appendJoins(sb, root);
        appendMatch(sb, querySpec);
        appendSort(sb, querySpec);
        appendLimit(sb, querySpec, queryOptions);
        var fieldNames = appendFormat(sb, querySpec.getSelectClause());
        if (querySpec.getSelectClause().isDistinct()) {
            sb.append(" | distinct");
        }

        var mqlv2Text = sb.toString();
        var fieldNamesArray = new BsonArray(fieldNames.stream().map(BsonString::new).toList());
        var commandDoc = new BsonDocument("mqlv2", new BsonString(mqlv2Text))
                .append("_mqlv2FieldNames", fieldNamesArray)
                .append("_mqlv2ParamCount", new org.bson.BsonInt32(parameterBinders.size()));
        var commandJson = commandDoc.toJson();

        var affectedTableNames = collectAffectedTableNames(root);
        var mappingProducerProvider =
                sessionFactory.getServiceRegistry().requireService(JdbcValuesMappingProducerProvider.class);
        var mappingProducer = mappingProducerProvider.buildMappingProducer(selectStatement, sessionFactory);
        return new JdbcOperationQuerySelect(
                commandJson, parameterBinders, mappingProducer, affectedTableNames, 0, MAX_VALUE, emptyMap(), NONE,
                null, null);
    }

    private void appendFrom(StringBuilder sb, TableGroup root) {
        var ntr = (NamedTableReference) root.getPrimaryTableReference();
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
            if (joinType == SqlAstJoinType.INNER) {
                sb.append(" | join ");
            } else if (joinType == SqlAstJoinType.LEFT) {
                sb.append(" | join leftOuter ");
            } else {
                throw new FeatureNotSupportedException("Unsupported join type: " + joinType);
            }
            sb.append(joinAlias).append("=$").append(joinCollName).append(" (");
            var joinPredicate = tgj.getPredicate();
            if (joinPredicate != null) {
                appendPredicateText(sb, joinPredicate);
            }
            sb.append(")");
        }
    }

    private void appendMatch(StringBuilder sb, QuerySpec querySpec) {
        var where = querySpec.getWhereClauseRestrictions();
        if (where == null || where.isEmpty()) return;
        sb.append(" | match ");
        appendPredicateText(sb, where);
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
        var fetchExpr = querySpec.getFetchClauseExpression();
        if (fetchExpr == null) return;
        if (querySpec.getOffsetClauseExpression() != null) {
            throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
        }
        sb.append(" | limit ");
        appendExprText(sb, fetchExpr);
    }

    private List<String> appendFormat(StringBuilder sb, SelectClause selectClause) {
        var fieldNames = new ArrayList<String>();
        var formatParts = new ArrayList<String>();

        for (var sqlSelection : selectClause.getSqlSelections()) {
            if (sqlSelection.isVirtual()) continue;
            var selExpr = sqlSelection.getExpression();
            ColumnReference cr;
            if (selExpr instanceof ColumnReference directCr) {
                cr = directCr;
            } else if (selExpr instanceof BasicValuedPathInterpretation<?> bvpi) {
                cr = bvpi.getColumnReference();
            } else {
                throw new FeatureNotSupportedException("Only column references are supported in SELECT");
            }
            var col = cr.getColumnExpression();
            fieldNames.add(col);
            var valSb = new StringBuilder();
            appendExprText(valSb, cr);
            formatParts.add(col + ": " + valSb);
        }

        sb.append(" | format {");
        for (var i = 0; i < formatParts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatParts.get(i));
        }
        sb.append("}");
        return fieldNames;
    }

    private void appendPredicateText(StringBuilder sb, Predicate predicate) {
        if (predicate instanceof ComparisonPredicate cp) {
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
