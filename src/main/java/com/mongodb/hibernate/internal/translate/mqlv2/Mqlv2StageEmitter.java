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

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.mqlv2.ast.Assignment;
import com.mongodb.mqlv2.ast.BinaryOpType;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.JoinType;
import com.mongodb.mqlv2.ast.SortDirection;
import com.mongodb.mqlv2.ast.SortSpec;
import com.mongodb.mqlv2.ast.Stage;
import com.mongodb.mqlv2.ast.Value;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.SetOperator;
import org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression;
import org.hibernate.query.sqm.sql.internal.BasicValuedPathInterpretation;
import org.hibernate.query.sqm.sql.internal.EntityValuedPathInterpretation;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Star;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.predicate.BooleanExpressionPredicate;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.NullnessPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate;
import org.hibernate.sql.ast.tree.select.QueryGroup;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectClause;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Translates Hibernate SQL AST stage-level constructs into driver-mqlv2 {@link Stage} nodes. Covers the full pipeline
 * construction sequence: FROM ({@link #translateFromStage}), JOIN/UNWIND ({@link #translateJoins}), WHERE
 * ({@link #translateMatch}), GROUP BY ({@link #translateGroup}), HAVING ({@link #translateHaving}), scalar aggregates
 * ({@link #translateScalarAgg}), ORDER BY ({@link #translateSort}), LIMIT ({@link #translateLimit}), SELECT projection
 * ({@link #translateFormat}), and set-operator query groups ({@link #translateQueryGroupStage}).
 *
 * <p>Stateless on purpose: all qualifier rules (joins, unnest aliases) are carried by the
 * {@link Mqlv2TranslationContext} passed to each method. Expression and predicate arguments embedded in stage
 * construction are delegated to {@link Mqlv2ExpressionEmitter}; the dependency is strictly one-way (StageEmitter →
 * ExpressionEmitter).
 *
 * @hidden
 */
public final class Mqlv2StageEmitter {

    private Mqlv2StageEmitter() {}

    /**
     * Return value of {@link #translateFormat}: the updated pipeline {@link Stage} and the ordered list of field names
     * for the JdbcOperationQuerySelect result-set mapping.
     *
     * @hidden
     */
    public record FormatTranslation(Stage stage, List<String> fieldNames) {}

    /**
     * Build a {@link Stage.FromStageSimple} or {@link Stage.FromStageNested} for the primary table reference of the
     * FROM clause root.
     *
     * <ul>
     *   <li>No joins: {@code from $collName} — uses {@link Stage.FromStageSimple}.
     *   <li>Has joins: {@code from alias=$collName} — uses {@link Stage.FromStageNested} so that subsequent join stages
     *       can address fields by qualifier.
     * </ul>
     *
     * @param ntr the primary {@link NamedTableReference} of the FROM root.
     * @param hasJoins true iff the query has at least one non-unnest join (determines which from-stage shape to emit).
     * @return the from {@link Stage} node.
     */
    public static Stage translateFromStage(NamedTableReference ntr, boolean hasJoins) {
        var collName = ntr.getTableExpression();
        if (!hasJoins) {
            return new Stage.FromStageSimple(new Expr.VarRef(collName));
        }
        var alias = ntr.getIdentificationVariable();
        return new Stage.FromStageNested(List.of(Map.entry(alias, new Expr.VarRef(collName))));
    }

    /**
     * Traverses the table-group-join list and builds a chain of {@link Stage} nodes ({@link Stage.UnwindComplexStage}
     * for unnest joins, {@link Stage.JoinStage} for regular joins).
     *
     * <p>Populates {@code unnestAliasToFieldPath} (via {@link Mqlv2TranslationContext#unnestAliasToFieldPath()}) so
     * that subsequent column-reference rendering resolves unnest aliases correctly.
     */
    public static Stage translateJoins(Stage prev, TableGroup root, QuerySpec querySpec, Mqlv2TranslationContext ctx) {
        var s = prev;
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var primaryRef = joinedGroup.getPrimaryTableReference();
            if (primaryRef instanceof FunctionTableReference ftr) {
                if ("unnest".equals(ftr.getFunctionExpression().getFunctionName())) {
                    var rootAlias = root.getPrimaryTableReference().getIdentificationVariable();
                    s = buildUnnestJoinStage(s, tgj, ftr, querySpec, rootAlias, ctx);
                } else {
                    throw new FeatureNotSupportedException(
                            "Unsupported table-valued function in join: "
                                    + ftr.getFunctionExpression().getFunctionName());
                }
            } else if (primaryRef instanceof NamedTableReference joinNtr) {
                var joinCollName = joinNtr.getTableExpression();
                var joinAlias = joinNtr.getIdentificationVariable();
                var joinPredicate = tgj.getPredicate();
                if (joinPredicate == null) {
                    throw new FeatureNotSupportedException("Join without ON condition is not supported in MQLv2");
                }
                var condExpr = Mqlv2ExpressionEmitter.translatePredicate(joinPredicate, ctx);
                s = new Stage.JoinStage(
                        s, irJoinType(tgj.getJoinType()), joinAlias, new Expr.VarRef(joinCollName), condExpr);
                s = translateJoins(s, joinedGroup, querySpec, ctx);
            } else {
                throw new FeatureNotSupportedException(
                        "Unsupported table reference type in join: " + primaryRef.getClass().getSimpleName());
            }
        }
        return s;
    }

    /**
     * Append a {@link Stage.MatchStage} if the {@code QuerySpec} has a WHERE clause; otherwise return {@code prev}
     * unchanged.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec whose WHERE clause is consulted.
     * @param ctx translation context for column-reference qualifier rules.
     * @return the updated stage (either a new {@link Stage.MatchStage} or {@code prev} unchanged).
     */
    public static Stage translateMatch(Stage prev, QuerySpec qs, Mqlv2TranslationContext ctx) {
        var pred = qs.getWhereClauseRestrictions();
        if (pred == null || pred.isEmpty()) {
            return prev;
        }
        return new Stage.MatchStage(prev, Mqlv2ExpressionEmitter.translatePredicate(pred, ctx));
    }

    /**
     * Append a {@link Stage.SortStage} if the {@code QuerySpec} has ORDER BY specifications; otherwise return
     * {@code prev} unchanged.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec whose sort specifications are consulted.
     * @param ctx translation context for column-reference qualifier rules.
     * @return the updated stage (either a new {@link Stage.SortStage} or {@code prev} unchanged).
     */
    public static Stage translateSort(Stage prev, QuerySpec qs, Mqlv2TranslationContext ctx) {
        if (!qs.hasSortSpecifications()) {
            return prev;
        }
        var specs = qs.getSortSpecifications();
        var sortSpecs = new ArrayList<SortSpec>(specs.size());
        for (var spec : specs) {
            var expr = Mqlv2ExpressionEmitter.translateExpression(spec.getSortExpression(), ctx);
            var dir = spec.getSortOrder() == org.hibernate.query.SortDirection.DESCENDING
                    ? SortDirection.DESC
                    : SortDirection.ASC;
            sortSpecs.add(new SortSpec(expr, dir));
        }
        return new Stage.SortStage(prev, sortSpecs);
    }

    /**
     * Append a {@link Stage.LimitStage} if the {@code QuerySpec} has a FETCH clause or the {@code QueryOptions} specify
     * a max-rows limit; otherwise return {@code prev} unchanged.
     *
     * <p>When a dynamic max-rows limit is active the caller-supplied {@code onSetMaxResults} callback is invoked so the
     * translator can allocate the {@link org.hibernate.sql.ast.tree.expression.JdbcParameter} binder and record the
     * {@code limitJdbcParameter} field before this method reads the resulting binder index from the context.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec (consulted for the HQL FETCH clause).
     * @param queryOptions dynamic query options; may be {@code null} for sub-queries.
     * @param ctx translation context (parameter binders are appended via {@code allocateParameter}).
     * @param onSetMaxResults callback invoked when {@code queryOptions.getLimit().getMaxRows()} is non-null; the
     *     callback must push the limit binder to {@code ctx}'s parameter list before returning.
     * @return the updated stage (either a new {@link Stage.LimitStage} or {@code prev} unchanged).
     */
    public static Stage translateLimit(
            Stage prev,
            QuerySpec qs,
            @Nullable QueryOptions queryOptions,
            Mqlv2TranslationContext ctx,
            Runnable onSetMaxResults) {
        if (queryOptions != null) {
            var limit = queryOptions.getLimit();
            if (limit != null && limit.getFirstRow() != null) {
                throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
            }
            if (limit != null && limit.getMaxRows() != null) {
                onSetMaxResults.run();
                // The callback pushed the LimitJdbcParameter binder as the last entry; its index
                // is therefore (size - 1).
                int idx = ctx.parameterBinders().size() - 1;
                return new Stage.LimitStage(prev, new Expr.VarRef("p" + idx));
            }
        }
        // Fall through to the HQL FETCH clause.
        var fetchExpr = qs.getFetchClauseExpression();
        if (fetchExpr == null) {
            return prev;
        }
        if (qs.getOffsetClauseExpression() != null) {
            throw new FeatureNotSupportedException("OFFSET is not supported in MQLv2");
        }
        return new Stage.LimitStage(prev, Mqlv2ExpressionEmitter.translateExpression(fetchExpr, ctx));
    }

    /**
     * Build a {@link Stage.FormatStage} wrapping {@code prev} and return a {@link FormatTranslation} that also carries
     * the ordered field-name list for the result-set mapping.
     *
     * <p>Field-name assignment rules:
     *
     * <ul>
     *   <li>Aggregate selection (aggName non-null): synthetic {@code _fN} key; value is a bare field reference to the
     *       aggregate alias.
     *   <li>{@link ColumnReference} / {@link BasicValuedPathInterpretation}: the column expression becomes both the
     *       document key and the field name. After a group stage, the value is also a bare field reference to the group
     *       key.
     *   <li>All other expressions: a synthetic {@code _fN} name is assigned.
     * </ul>
     *
     * @param prev the pipeline stage to wrap.
     * @param selectClause the SELECT clause whose non-virtual selections drive the projection.
     * @param aggNames per-selection aggregate alias list, or {@code null} when no group/agg stage precedes the format
     *     stage. When non-null, each entry is the assigned {@code _aggN} alias for aggregate selections and
     *     {@code null} for non-aggregate selections.
     * @param ctx translation context.
     * @return a {@link FormatTranslation} containing the updated stage and the field names.
     */
    public static FormatTranslation translateFormat(
            Stage prev,
            SelectClause selectClause,
            @Nullable List<@Nullable String> aggNames,
            Mqlv2TranslationContext ctx) {
        var fieldNames = new ArrayList<String>();
        var fields = new ArrayList<Map.Entry<Expr, Expr>>();
        var syntheticIdx = 0;
        // Collect the set of array field paths from unnest joins. These columns require re-wrapping
        // in a single-element array in the format stage so Hibernate's getArray() call succeeds.
        // (The | unwind body maps the array field to the element document for match purposes;
        // the format stage then wraps it back so the result set delivers an ARRAY.)
        var unnestArrayFields = new LinkedHashSet<>(ctx.unnestAliasToFieldPath().values());

        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();

        for (var i = 0; i < selections.size(); i++) {
            var selExpr = selections.get(i).getExpression();
            var aggName = aggNames != null ? aggNames.get(i) : null;
            String key;
            Expr valueExpr;
            if (aggName != null) {
                // Aggregate selection: synthetic key, bare alias reference as value.
                key = "_f" + syntheticIdx++;
                valueExpr = Mqlv2ExpressionEmitter.translateAggregateReference(aggName);
            } else if (selExpr instanceof ColumnReference cr) {
                key = cr.getColumnExpression();
                // After a group stage the key field is addressed directly; otherwise translate normally.
                var rawValue = aggNames != null
                        ? new Expr.FieldAccess(new Expr.CurrentValue(), key)
                        : Mqlv2ExpressionEmitter.translateExpression(selExpr, ctx);
                // Re-wrap unnest array fields.
                valueExpr = (!unnestArrayFields.isEmpty() && unnestArrayFields.contains(key))
                        ? new Expr.ArrayConstructor(List.of(rawValue))
                        : rawValue;
            } else if (selExpr instanceof BasicValuedPathInterpretation<?> bvpi) {
                key = requireNonNull(bvpi.getColumnReference()).getColumnExpression();
                var rawValue = aggNames != null
                        ? new Expr.FieldAccess(new Expr.CurrentValue(), key)
                        : Mqlv2ExpressionEmitter.translateExpression(selExpr, ctx);
                valueExpr = (!unnestArrayFields.isEmpty() && unnestArrayFields.contains(key))
                        ? new Expr.ArrayConstructor(List.of(rawValue))
                        : rawValue;
            } else {
                key = "_f" + syntheticIdx++;
                valueExpr = Mqlv2ExpressionEmitter.translateExpression(selExpr, ctx);
            }
            fieldNames.add(key);
            fields.add(Map.entry(new Expr.ValueLit(new Value.VString(key)), valueExpr));
        }

        var docConstructor = new Expr.DocumentConstructor(fields);
        return new FormatTranslation(new Stage.FormatStage(prev, docConstructor), fieldNames);
    }

    // ---- Group / Having / Scalar-agg stage translation helpers ----

    /**
     * Build a {@link Stage.GroupStage} for a GROUP BY query.
     *
     * <p>Group keys are named after the column expression (column name only, no qualifier). Aggregate keys come from
     * (a) non-null entries in {@code aggNames} (SELECT-position aggregates) and (b) HAVING-only aggregates in
     * {@code havingOnlyAggs}.
     *
     * @param prev the pipeline stage to wrap.
     * @param groupByExprs the GROUP BY expressions.
     * @param selectClause the SELECT clause.
     * @param aggNames per-selection aggregate alias list (same contract as in {@link #translateFormat(Stage,
     *     SelectClause, List, Mqlv2TranslationContext)}).
     * @param havingOnlyAggs aggregates referenced only in HAVING (not in SELECT), keyed by their assigned alias.
     * @param ctx translation context.
     * @return a new {@link Stage.GroupStage}.
     */
    public static Stage translateGroup(
            Stage prev,
            List<Expression> groupByExprs,
            SelectClause selectClause,
            List<@Nullable String> aggNames,
            Map<String, SelfRenderingFunctionSqlAstExpression<?>> havingOnlyAggs,
            Mqlv2TranslationContext ctx) {
        // Build groupKeys: one Assignment per group-by expression, named after the column.
        var groupKeys = new ArrayList<Assignment>(groupByExprs.size());
        for (var ge : groupByExprs) {
            String name = Mqlv2ExpressionEmitter.simpleColumnName(ge);
            groupKeys.add(new Assignment(List.of(name), Mqlv2ExpressionEmitter.translateExpression(ge, ctx)));
        }

        // Build aggKeys: one Assignment per aggregate function.
        var aggKeys = new ArrayList<Assignment>();
        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            var fn =
                    (SelfRenderingFunctionSqlAstExpression<?>) selections.get(i).getExpression();
            aggKeys.add(new Assignment(List.of(aggName), translateAggregateCall(fn, ctx)));
        }
        // HAVING-only aggregates also go into the group stage.
        for (var entry : havingOnlyAggs.entrySet()) {
            aggKeys.add(new Assignment(List.of(entry.getKey()), translateAggregateCall(entry.getValue(), ctx)));
        }

        return new Stage.GroupStage(prev, groupKeys, aggKeys);
    }

    /**
     * Build a {@link Stage.AggStage} for a scalar-aggregate query (aggregates without GROUP BY).
     *
     * @param prev the pipeline stage to wrap.
     * @param selectClause the SELECT clause.
     * @param aggNames per-selection aggregate alias list.
     * @param ctx translation context.
     * @return a new {@link Stage.AggStage}.
     */
    public static Stage translateScalarAgg(
            Stage prev, SelectClause selectClause, List<@Nullable String> aggNames, Mqlv2TranslationContext ctx) {
        var fields = new ArrayList<Map.Entry<Expr, Expr>>();
        var selections = selectClause.getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        for (var i = 0; i < selections.size(); i++) {
            var aggName = aggNames.get(i);
            if (aggName == null) continue;
            var fn =
                    (SelfRenderingFunctionSqlAstExpression<?>) selections.get(i).getExpression();
            fields.add(
                    Map.entry(new Expr.ValueLit(new Value.VString(aggName)), translateAggregateCall(fn, ctx)));
        }
        return new Stage.AggStage(prev, new Expr.DocumentConstructor(fields));
    }

    /**
     * Append a {@link Stage.MatchStage} for the HAVING clause if it is non-empty; otherwise return {@code prev}
     * unchanged.
     *
     * <p>The HAVING predicate may reference aggregate aliases (e.g., {@code _agg0}) via the {@code aggregateAliases}
     * map in {@code ctx}; these are resolved by {@link Mqlv2ExpressionEmitter#translateExpression}.
     *
     * @param prev the pipeline stage to wrap.
     * @param qs the query spec whose HAVING clause is consulted.
     * @param ctx translation context (must have {@code aggregateAliases} populated).
     * @return the updated stage.
     */
    public static Stage translateHaving(Stage prev, QuerySpec qs, Mqlv2TranslationContext ctx) {
        var having = qs.getHavingClauseRestrictions();
        if (having == null || having.isEmpty()) return prev;
        return new Stage.MatchStage(prev, Mqlv2ExpressionEmitter.translatePredicate(having, ctx));
    }

    /**
     * Builds the driver-mqlv2 {@link Stage} for a UNION/UNION_ALL/INTERSECT/EXCEPT query group.
     *
     * <ul>
     *   <li>UNION_ALL: {@code from <<(sub1), (sub2), ...>> | unwind $*}
     *   <li>UNION: {@code from <<(sub1), (sub2), ...>> | unwind $* | distinct}
     *   <li>INTERSECT: {@code left | match (count(let $__vN = $ in (right | match ($ == $__vN))) > 0)}
     *   <li>EXCEPT: same shape but with {@code == 0}
     * </ul>
     *
     * @param queryGroup the set-operation query group.
     * @param subStages the already-translated stage for each query-group member.
     * @param nextCorrelatedVar supplier for the next globally-unique {@code __vN} integer index.
     * @return the translated {@link Stage}.
     */
    public static Stage translateQueryGroupStage(
            QueryGroup queryGroup, List<Stage> subStages, IntSupplier nextCorrelatedVar) {
        var operator = queryGroup.getSetOperator();
        return switch (operator) {
            case UNION_ALL, UNION -> {
                var subPipeExprs = subStages.stream()
                        .map(s -> (Expr) new Expr.SubPipelineExpr(s))
                        .toList();
                Stage source = new Stage.FromStageSimple(new Expr.BagConstructor(subPipeExprs));
                Stage unwind = new Stage.UnwindSimpleStage(source, new Expr.UnwindExpr(new Expr.CurrentValue()));
                yield operator == SetOperator.UNION ? new Stage.DistinctStage(unwind) : unwind;
            }
            case INTERSECT, EXCEPT -> {
                var leftStage = subStages.get(0);
                var rightStage = subStages.get(1);
                // right | match ($ == $__vN)
                var varName = "__v" + nextCorrelatedVar.getAsInt();
                Stage rightWithMatch = new Stage.MatchStage(
                        rightStage,
                        new Expr.BinaryOp(BinaryOpType.EQ, new Expr.CurrentValue(), new Expr.VarRef(varName)));
                // let $__vN = $ in (right | match ...)
                Expr letExpr = new Expr.LetExpr(
                        List.of(Map.entry(varName, new Expr.CurrentValue())), new Expr.SubPipelineExpr(rightWithMatch));
                // count(let ...) > 0  or  == 0
                Expr countExpr = new Expr.FunctionCall("count", List.of(letExpr));
                Expr predicate = new Expr.BinaryOp(
                        operator == SetOperator.INTERSECT ? BinaryOpType.GT : BinaryOpType.EQ,
                        countExpr,
                        new Expr.ValueLit(new Value.VInt(0)));
                yield new Stage.MatchStage(leftStage, predicate);
            }
            default -> throw new FeatureNotSupportedException("Unsupported set operator: " + operator);
        };
    }

    // ---- Aggregate call helpers ----

    /**
     * Translate an aggregate function call (not a reference to an already-assigned alias) into its driver-mqlv2
     * {@link Expr.FunctionCall} form.
     *
     * <p>Argument mapping:
     *
     * <ul>
     *   <li>{@code count(*)} / {@code count(entity)} / no argument → {@code count($)} (current value).
     *   <li>Regular field argument → {@code fn($->field)} using {@link Expr.ArrowOp}.
     * </ul>
     *
     * @param fn the aggregate function call AST node.
     * @param ctx translation context (for qualifier resolution on the argument column).
     * @return the equivalent driver-mqlv2 {@link Expr.FunctionCall} node.
     */
    private static Expr translateAggregateCall(
            SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
        var name = fn.getFunctionName();
        var args = fn.getArguments();
        Expr argExpr;
        if (args.isEmpty() || args.get(0) instanceof Star || args.get(0) instanceof EntityValuedPathInterpretation<?>) {
            // count(*) / count(entity) → count($)
            argExpr = new Expr.CurrentValue();
        } else {
            if (!(args.get(0) instanceof Expression firstArg)) {
                throw new FeatureNotSupportedException(name + "() requires an expression argument in MQLv2");
            }
            argExpr = translateAggFieldRef(firstArg, ctx);
        }
        return new Expr.FunctionCall(name, List.of(argExpr));
    }

    /**
     * Translate an aggregate function field argument into the {@code $->field} arrow-op form.
     *
     * <p>Rule: the arrow-op always starts from {@code $} (current value). In a join context the qualifier is preserved:
     * {@code qualifier->column}. For unnest aliases the mapped field path is used: {@code arrayFieldPath->column}.
     * Without joins: bare column.
     *
     * @param expr the aggregate argument expression (column reference or path interpretation).
     * @param ctx translation context.
     * @return an {@link Expr.ArrowOp} rooted at the current value, resolving the field.
     */
    private static Expr translateAggFieldRef(Expression expr, Mqlv2TranslationContext ctx) {
        ColumnReference cr;
        if (expr instanceof ColumnReference c) {
            cr = c;
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            cr = bvpi.getColumnReference();
        } else {
            throw new FeatureNotSupportedException("Expected column reference in aggregate argument; got: "
                    + expr.getClass().getSimpleName());
        }
        var qualifier = cr.getQualifier();
        String field;
        if (qualifier != null && ctx.unnestAliasToFieldPath().containsKey(qualifier)) {
            field = ctx.unnestAliasToFieldPath().get(qualifier) + "->" + cr.getColumnExpression();
        } else if (ctx.hasJoins() && qualifier != null && !qualifier.isEmpty()) {
            field = qualifier + "->" + cr.getColumnExpression();
        } else {
            field = cr.getColumnExpression();
        }
        // Build the arrow-op chain: $->a->b for "a->b" paths (unnest), or plain $->column.
        Expr target = new Expr.CurrentValue();
        int arrowIdx;
        int start = 0;
        while ((arrowIdx = field.indexOf("->", start)) != -1) {
            target = new Expr.ArrowOp(target, field.substring(start, arrowIdx));
            start = arrowIdx + 2;
        }
        target = new Expr.ArrowOp(target, field.substring(start));
        return target;
    }

    // ---- Join-stage helpers ----

    /**
     * Builds a {@link Stage.UnwindComplexStage} that explodes the array field and preserves all parent-entity columns
     * in the unwind body.
     *
     * <p>Also records the alias→array-field-path mapping in {@link Mqlv2TranslationContext#unnestAliasToFieldPath()} so
     * that subsequent column-reference rendering resolves {@code alias.column} to {@code arrayFieldPath.column}.
     */
    private static Stage buildUnnestJoinStage(
            Stage prev,
            TableGroupJoin tgj,
            FunctionTableReference ftr,
            QuerySpec querySpec,
            @Nullable String rootAlias,
            Mqlv2TranslationContext ctx) {
        var arrayPath = Mqlv2ExpressionEmitter.extractUnnestArrayPath(ftr);
        var alias = tgj.getJoinedGroup().getPrimaryTableReference().getIdentificationVariable();
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
        ctx.unnestAliasToFieldPath().put(alias, arrayFieldPath);

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
     * "parent" if its qualifier matches {@code rootAlias} (or is null/empty).
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
        } else if (predicate instanceof SelfRenderingPredicate) {
            // SelfRenderingPredicate (array function predicates) — not walked here,
            // their subqueries are separate query scopes and don't contribute parent columns.
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
        } else if (expr instanceof org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation spi) {
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
            return requireNonNull(bvpi.getColumnReference()).getColumnExpression();
        }
        throw new FeatureNotSupportedException("Expected simple column reference for unwind path; got: "
                + expr.getClass().getSimpleName());
    }
}
