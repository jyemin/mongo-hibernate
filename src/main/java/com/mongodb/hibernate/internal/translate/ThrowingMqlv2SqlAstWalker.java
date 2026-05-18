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

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import org.hibernate.persister.internal.SqlFragmentPredicate;
import org.hibernate.sql.ast.SqlAstWalker;
import org.hibernate.sql.ast.spi.SqlSelection;
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
import org.hibernate.sql.ast.tree.from.FromClause;
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
import org.hibernate.sql.model.ast.ColumnWriteFragment;
import org.hibernate.sql.model.internal.OptionalTableUpdate;
import org.hibernate.sql.model.internal.TableDeleteCustomSql;
import org.hibernate.sql.model.internal.TableDeleteStandard;
import org.hibernate.sql.model.internal.TableInsertCustomSql;
import org.hibernate.sql.model.internal.TableInsertStandard;
import org.hibernate.sql.model.internal.TableUpdateCustomSql;
import org.hibernate.sql.model.internal.TableUpdateStandard;

/**
 * Throw-by-default skeleton for the MQLv2 translator's visit-method surface.
 *
 * <p>{@link Mqlv2SelectTranslator} drives its own walk via {@code buildQuerySpecTranslation} and
 * {@code buildQueryGroupTranslation}; the inherited {@code visitX} methods from {@link SqlAstWalker} only need to exist
 * to satisfy the interface contract — any actual call would indicate a bug in the translation dispatch, so each method
 * throws.
 *
 * @hidden
 */
interface ThrowingMqlv2SqlAstWalker extends SqlAstWalker {

    @Override
    default void visitSelectStatement(SelectStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitDeleteStatement(DeleteStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitUpdateStatement(UpdateStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitInsertStatement(InsertSelectStatement statement) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitAssignment(Assignment assignment) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitQueryGroup(QueryGroup queryGroup) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitQuerySpec(QuerySpec querySpec) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSortSpecification(SortSpecification sortSpecification) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitOffsetFetchClause(QueryPart querySpec) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSelectClause(SelectClause selectClause) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSqlSelection(SqlSelection sqlSelection) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitFromClause(FromClause fromClause) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitTableGroup(TableGroup tableGroup) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitTableGroupJoin(TableGroupJoin tableGroupJoin) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitNamedTableReference(NamedTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitValuesTableReference(ValuesTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitQueryPartTableReference(QueryPartTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitFunctionTableReference(FunctionTableReference tableReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitTableReferenceJoin(TableReferenceJoin tableReferenceJoin) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitColumnReference(ColumnReference columnReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitNestedColumnReference(NestedColumnReference nestedColumnReference) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitAggregateColumnWriteExpression(AggregateColumnWriteExpression aggregateColumnWriteExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitExtractUnit(ExtractUnit extractUnit) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitFormat(Format format) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitDistinct(Distinct distinct) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitOverflow(Overflow overflow) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitStar(Star star) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitTrimSpecification(TrimSpecification trimSpecification) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCastTarget(CastTarget castTarget) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitBinaryArithmeticExpression(BinaryArithmeticExpression arithmeticExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCaseSearchedExpression(CaseSearchedExpression caseSearchedExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCaseSimpleExpression(CaseSimpleExpression caseSimpleExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitAny(Any any) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitEvery(Every every) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSummarization(Summarization summarization) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitOver(Over<?> over) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSelfRenderingExpression(SelfRenderingExpression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSqlSelectionExpression(SqlSelectionExpression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitEntityTypeLiteral(EntityTypeLiteral expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitEmbeddableTypeLiteral(EmbeddableTypeLiteral expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitTuple(SqlTuple tuple) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCollation(Collation collation) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitParameter(JdbcParameter jdbcParameter) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitJdbcLiteral(JdbcLiteral<?> jdbcLiteral) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitQueryLiteral(QueryLiteral<?> queryLiteral) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default <N extends Number> void visitUnparsedNumericLiteral(UnparsedNumericLiteral<N> literal) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitUnaryOperationExpression(UnaryOperation unaryOperationExpression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitModifiedSubQueryExpression(ModifiedSubQueryExpression expression) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitBooleanExpressionPredicate(BooleanExpressionPredicate booleanExpressionPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitBetweenPredicate(BetweenPredicate betweenPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitFilterPredicate(FilterPredicate filterPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitFilterFragmentPredicate(FilterPredicate.FilterFragmentPredicate fragmentPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSqlFragmentPredicate(SqlFragmentPredicate predicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitGroupedPredicate(GroupedPredicate groupedPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitInListPredicate(InListPredicate inListPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitInSubQueryPredicate(InSubQueryPredicate inSubQueryPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitInArrayPredicate(InArrayPredicate inArrayPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitExistsPredicate(ExistsPredicate existsPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitJunction(Junction junction) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitLikePredicate(LikePredicate likePredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitNegatedPredicate(NegatedPredicate negatedPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitNullnessPredicate(NullnessPredicate nullnessPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitThruthnessPredicate(ThruthnessPredicate predicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitRelationalPredicate(ComparisonPredicate comparisonPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitSelfRenderingPredicate(SelfRenderingPredicate selfRenderingPredicate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitDurationUnit(DurationUnit durationUnit) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitDuration(Duration duration) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitConversion(org.hibernate.query.sqm.tree.expression.Conversion conversion) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitStandardTableInsert(TableInsertStandard tableInsert) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCustomTableInsert(TableInsertCustomSql tableInsert) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitStandardTableDelete(TableDeleteStandard tableDelete) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCustomTableDelete(TableDeleteCustomSql tableDelete) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitStandardTableUpdate(TableUpdateStandard tableUpdate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitOptionalTableUpdate(OptionalTableUpdate tableUpdate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitCustomTableUpdate(TableUpdateCustomSql tableUpdate) {
        throw new FeatureNotSupportedException();
    }

    @Override
    default void visitColumnWriteFragment(ColumnWriteFragment columnWriteFragment) {
        throw new FeatureNotSupportedException();
    }
}
