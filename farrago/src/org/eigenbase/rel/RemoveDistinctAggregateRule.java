/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2006-2006 The Eigenbase Project
// Copyright (C) 2006-2006 Disruptive Tech
// Copyright (C) 2006-2006 LucidEra, Inc.
// Portions Copyright (C) 2006-2006 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.eigenbase.rel;

import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.RexNode;
import org.eigenbase.rex.RexBuilder;
import org.eigenbase.rex.RexInputRef;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.util.Util;

import java.util.*;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.Array;

/**
 * Rule to remove distinct aggregates from a {@link AggregateRel}.
 *
 * @author jhyde
 * @version $Id$
 *
 * @since 3 February, 2006
 */
public final class RemoveDistinctAggregateRule extends RelOptRule
{
    /**
     * The singleton.
     */
    public static final RemoveDistinctAggregateRule instance =
        new RemoveDistinctAggregateRule();

    //~ Constructors ----------------------------------------------------------
    private RemoveDistinctAggregateRule()
    {
        super(new RelOptRuleOperand(AggregateRel.class, null));
    }

    public void onMatch(RelOptRuleCall call)
    {
        AggregateRel aggregate = (AggregateRel) call.rels[0];
        if (!aggregate.containsDistinctCall()) {
            return;
        }

        // Find all of the agg expressions. We use a LinkedHashSet to ensure
        // determinism.
        int nonDistinctCount = 0;
        Set<List<Integer> > argListSets = new LinkedHashSet<List<Integer> >();
        for (AggregateRelBase.Call aggCall : aggregate.aggCalls) {
            if (!aggCall.isDistinct()) {
                ++nonDistinctCount;
                continue;
            }
            ArrayList<Integer> argList = new ArrayList<Integer>();
            for (int arg : aggCall.getArgs()) {
                argList.add(arg);
            }
            argListSets.add(argList);
        }
        Util.permAssert(argListSets.size() > 0, "containsDistinctCall lied");

        // If all of the agg expressions are distinct and have the same
        // arguments then we can use a more efficient form.
        if (nonDistinctCount == 0 &&
            argListSets.size() == 1) {
            RelNode converted = convertMonopole(
                aggregate, argListSets.iterator().next());
            call.transformTo(converted);
            return;
        }

        // Create a list of the expressions which will yield the final result.
        // Initially, the expressions point to the input field.
        RelDataTypeField[] aggFields = aggregate.getRowType().getFields();
        RexInputRef[] refs = new RexInputRef[aggFields.length];
        String[] fieldNames = RelOptUtil.getFieldNames(aggregate.getRowType());
        final int groupCount = aggregate.getGroupCount();
        for (int i = 0; i < groupCount; i++) {
            refs[i] = new RexInputRef(i, aggFields[i].getType());
        }

        // Aggregate the original relation, including any non-distinct aggs.
        List<AggregateRelBase.Call> newAggCallList =
            new ArrayList<AggregateRelBase.Call>();
        int i = -1;
        for (AggregateRelBase.Call aggCall : aggregate.aggCalls) {
            ++i;
            if (aggCall.isDistinct()) {
                continue;
            }
            assert refs[groupCount + i] == null;
            refs[groupCount + i] =
                new RexInputRef(
                    groupCount + newAggCallList.size(),
                    aggFields[i].getType());
            newAggCallList.add(aggCall);
        }

        RelNode rel = new AggregateRel(
            aggregate.getCluster(),
            aggregate.getChild(),
            groupCount,
            (AggregateRelBase.Call[]) newAggCallList.toArray(
                new AggregateRelBase.Call[newAggCallList.size()]));

        // For each set of operands, find and rewrite all calls which have that
        // set of operands.
        for (List<Integer> argList : argListSets) {
            rel = doRewrite(aggregate, rel, argList, refs);
        }

        rel = CalcRel.createProject(rel, refs, fieldNames);

        call.transformTo(rel);
    }

    /**
     * Converts an aggregrate relational expression which contains just one
     * distinct aggregate function (or perhaps several over the same arguments)
     * and no non-distinct aggregate functions.
     */
    private RelNode convertMonopole(
        AggregateRel aggregate,
        List<Integer> argList)
    {
        // For example,
        //    SELECT deptno, COUNT(DISTINCT sal), SUM(DISTINCT sal)
        //    FROM emp
        //    GROUP BY deptno
        //
        // becomes
        //
        //    SELECT deptno, COUNT(distinct_sal), SUM(distinct_sal)
        //    FROM (
        //      SELECT DISTINCT deptno, sal AS distinct_sal
        //      FROM EMP GROUP BY deptno)
        //    GROUP BY deptno

        // Project the columns of the GROUP BY plus the arguments
        // to the agg function.
        Map<Integer,Integer> sourceOf = new HashMap<Integer, Integer>();
        final AggregateRel distinct =
            createSelectDistinct(aggregate, argList, sourceOf);

        // Create an aggregate on top, with the new aggregate list.
        final AggregateRelBase.Call[] newAggCalls = aggregate.aggCalls.clone();
        rewriteAggCalls(newAggCalls, argList, sourceOf);
        AggregateRel newAggregate = new AggregateRel(
            aggregate.getCluster(),
            distinct,
            aggregate.groupCount,
            newAggCalls);

        return newAggregate;
    }

    /**
     * Converts all distinct aggregate calls to a given set of arguments.
     *
     * <p>This method is called several times, one for each set of arguments.
     * Each time it is called, it generates a JOIN to a new SELECT DISTINCT
     * relational expression, and modifies the set of top-level calls.
     *
     * @param aggregate Original aggregate
     * @param left Child relational expression (either the original aggregate,
     *   or the output from the previous call to this method)
     * @param argList Arguments to the distinct aggregate function
     * @param refs Array of expressions which will be the projected by the
     *   result of this rule. Those relating to this arg list will be modified
     * @return Relational expression
     */
    private RelNode doRewrite(
        AggregateRel aggregate,
        RelNode left,
        List<Integer> argList,
        RexInputRef[] refs)
    {
        final RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
        final int groupCount = aggregate.getGroupCount();
        final RelDataTypeField[] leftFields = left.getRowType().getFields();

        // AggregateRel(
        //   child,
        //   {COUNT(DISTINCT 1), SUM(DISTINCT 1), SUM(2)})
        //
        // becomes
        //
        // AggregateRel(
        //   JoinRel(
        //     child,
        //     AggregateRel(
        //       child,
        //       < all columns >
        //       {})
        //     INNER,
        //     <f2 = f5>))
        //
        // E.g.
        //   SELECT deptno, SUM(sal), COUNT(DISTINCT gender)
        //   FROM Emps
        //
        // becomes
        //
        //   SELECT e.deptno, adsal.sum_sal, adgender.count_gender
        //   FROM Emps AS e
        //   JOIN (
        //     SELECT deptno, COUNT(gender) AS count_gender
        //     FROM (
        //       SELECT DISTINCT deptno, gender
        //       FROM Emps) AS dgender
        //     GROUP BY deptno) AS adgender
        //   ON e.deptno = adgender.deptno
        //   JOIN (
        //     SELECT deptno, SUM(sal) AS sum_sal
        //     FROM (
        //       SELECT DISTINCT deptno, sal
        //       FROM Emps) AS dsal
        //     GROUP BY deptno) AS adsal
        //   ON e.deptno = adsal.deptno
        //   GROUP BY e.deptno

        // Project the columns of the GROUP BY plus the arguments
        // to the agg function.
        Map<Integer,Integer> sourceOf = new HashMap<Integer, Integer>();
        final AggregateRel distinct =
            createSelectDistinct(aggregate, argList, sourceOf);

        // Now compute the aggregate functions on top of the distinct dataset.
        // Each distinct agg becomes a non-distinct call to the corresponding
        // field from the right; for example,
        //   "COUNT(DISTINCT e.sal)"
        // becomes
        //   "COUNT(distinct_e.sal)".
        List<AggregateRelBase.Call> aggCallList =
            new ArrayList<AggregateRelBase.Call>();
        final AggregateRelBase.Call[] aggCalls = aggregate.getAggCalls();

        for (int i = 0; i < aggCalls.length; i++) {
            final AggregateRelBase.Call aggCall = aggCalls[i];
            // Ignore agg calls which are not distinct or have the wrong set
            // arguments. If we're rewriting aggs whose args are {sal}, we will
            // rewrite COUNT(DISTINCT sal) and SUM(DISTINCT sal) but ignore
            // COUNT(DISTINCT gender) or SUM(sal).
            if (!aggCall.isDistinct()) {
                continue;
            }
            if (!equals(aggCall.getArgs(), argList)) {
                continue;
            }
            // Re-map arguments.
            final int[] newArgs = aggCall.getArgs().clone();
            for (int j = 0; j < newArgs.length; j++) {
                newArgs[j] = sourceOf.get(newArgs[j]);
            }
            final AggregateRelBase.Call newAggCall =
                new AggregateRelBase.Call(
                    aggCall.getAggregation(),
                    false,
                    newArgs,
                    aggCall.getType());
            assert refs[groupCount + i] == null;
            refs[groupCount + i] =
                new RexInputRef(
                    leftFields.length + groupCount + aggCallList.size(),
                    newAggCall.getType());
            aggCallList.add(newAggCall);
        }

        AggregateRelBase.Call[] newAggCalls = (AggregateRelBase.Call[])
            aggCallList.toArray(new AggregateRelBase.Call[aggCallList.size()]);
        AggregateRel distinctAgg =
            new AggregateRel(
                aggregate.getCluster(),
                distinct,
                groupCount,
                newAggCalls);


        // Create the join condition. It is of the form
        //  'left.f0 = right.f0 and left.f1 = right.f1 and ...'
        // where {f0, f1, ...} are the GROUP BY fields.
        final RelDataTypeField[] distinctFields =
            distinctAgg.getRowType().getFields();
        RexNode condition = rexBuilder.makeLiteral(true);
        for (int i = 0; i < groupCount; ++i) {
            final int leftOrdinal = i;
            final int rightOrdinal = sourceOf.get(i);
            RexNode equi = rexBuilder.makeCall(
                SqlStdOperatorTable.equalsOperator,
                new RexInputRef(
                    leftOrdinal,
                    leftFields[leftOrdinal].getType()),
                new RexInputRef(
                    leftFields.length + rightOrdinal,
                    distinctFields[rightOrdinal].getType()));
            if (i == 0) {
                condition = equi;
            } else {
                condition = rexBuilder.makeCall(
                    SqlStdOperatorTable.andOperator,
                    condition,
                    equi);
            }
        }

        // Join in the new 'select distinct' relation.
        final RelNode join =
            new JoinRel(
                aggregate.getCluster(),
                left,
                distinctAgg,
                condition,
                JoinRelType.INNER,
                Collections.emptySet());

        return join;
    }

    private static void rewriteAggCalls(
        AggregateRelBase.Call[] newAggCalls,
        List<Integer> argList,
        Map<Integer, Integer> sourceOf)
    {
        // Rewrite the agg calls. Each distinct agg becomes a non-distinct call
        // to the corresponding field from the right; for example,
        //   "COUNT(DISTINCT e.sal)"
        // becomes
        //   "COUNT(distinct_e.sal)".
        for (int i = 0; i < newAggCalls.length; i++) {
            final AggregateRelBase.Call aggCall = newAggCalls[i];
            // Ignore agg calls which are not distinct or have the wrong set
            // arguments. If we're rewriting aggs whose args are {sal}, we will
            // rewrite COUNT(DISTINCT sal) and SUM(DISTINCT sal) but ignore
            // COUNT(DISTINCT gender) or SUM(sal).
            if (!aggCall.isDistinct()) {
                continue;
            }
            if (!equals(aggCall.getArgs(), argList)) {
                continue;
            }
            // Re-map arguments.
            final int[] newArgs = aggCall.getArgs().clone();
            for (int j = 0; j < newArgs.length; j++) {
                newArgs[j] = sourceOf.get(newArgs[j]);
            }
            final AggregateRelBase.Call newAggCall =
                new AggregateRelBase.Call(
                    aggCall.getAggregation(),
                    false,
                    newArgs,
                    aggCall.getType());
            newAggCalls[i] = newAggCall;
        }
    }

    /**
     * Given an {@link AggregateRel} and the ordinals of the arguments to a
     * particular call to an aggregate function, creates a 'select distinct'
     * relational expression which projects the group columns and those
     * arguments but nothing else.
     *
     * <p>For example, given
     *
     * <blockquote><pre>select f0, count(distinct f1), count(distinct f2)
     * from t group by f0</pre></blockquote>
     *
     * and the arglist
     *
     * <blockquote>{2}</blockquote>
     *
     * returns
     *
     * <blockquote><pre>select distinct f0, f2 from t</pre></blockquote>
     '
     * <p>The <code>sourceOf</code> map is populated with the source of each
     * column; in this case sourceOf.get(0) = 0, and sourceOf.get(1) = 2.</p>
     *
     * @param aggregate Aggregate relational expression
     * @param argList Ordinals of columns to distinctify
     * @param sourceOf Out paramer, is populated with a map of where each
     *          output field came from
     * @return Aggregate relational expression which projects the required
     *          columns
     */
    private static AggregateRel createSelectDistinct(
        AggregateRel aggregate,
        List<Integer> argList,
        Map<Integer, Integer> sourceOf)
    {
        List<RexNode> exprList = new ArrayList<RexNode>();
        List<String> nameList = new ArrayList<String>();
        final RelNode child = aggregate.getChild();
        final RelDataTypeField[] childFields = child.getRowType().getFields();
        for (int i = 0; i < aggregate.getGroupCount(); i++) {
            exprList.add(new RexInputRef(i, childFields[i].getType()));
            nameList.add(childFields[i].getName());
            sourceOf.put(i, i);
        }
        for (Integer arg : argList) {
            if (sourceOf.get(arg) != null) {
                continue;
            }
            sourceOf.put(arg, exprList.size());
            exprList.add(new RexInputRef(arg, childFields[arg].getType()));
            nameList.add(childFields[arg].getName());
        }
        final RelNode project =
            CalcRel.createProject(
                child,
                (RexNode[]) exprList.toArray(new RexNode[exprList.size()]),
                (String[]) nameList.toArray(new String[nameList.size()]));

        // Get the distinct values of the GROUP BY fields and the arguments
        // to the agg functions.
        List<AggregateRelBase.Call> distinctAggCallList =
            new ArrayList<AggregateRelBase.Call>();
        final AggregateRel distinct =
            new AggregateRel(
                aggregate.getCluster(),
                project,
                exprList.size(),
                (AggregateRelBase.Call[]) distinctAggCallList.toArray(
                    new AggregateRelBase.Call[distinctAggCallList.size()]));
        return distinct;
    }

    /**
     * Returns whether an integer array has the same content as an integer
     * list.
     */
    private static boolean equals(int[] args, List<Integer> argList)
    {
        if (args.length != argList.size()) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] != argList.get(i)) {
                return false;
            }
        }
        return true;
    }
}

// End RemoveDistinctAggregateRule.java