/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.benchmark;

import com.facebook.presto.operator.Driver;
import com.facebook.presto.operator.DriverFactory;
import com.facebook.presto.operator.HashBuilderOperator.HashBuilderOperatorFactory;
import com.facebook.presto.operator.LookupJoinOperators;
import com.facebook.presto.operator.OperatorFactory;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.testing.NullOutputOperator.NullOutputOperatorFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import java.util.List;

import static com.facebook.presto.benchmark.BenchmarkQueryRunner.createLocalQueryRunner;
import static com.facebook.presto.benchmark.BenchmarkQueryRunner.createLocalQueryRunnerHashEnabled;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;

public class HashBuildAndJoinBenchmark
        extends AbstractOperatorBenchmark
{
    private final boolean hashEnabled;
    private final OperatorFactory ordersTableScan = createTableScanOperator(0, "orders", "orderkey", "totalprice");
    private final OperatorFactory lineItemTableScan = createTableScanOperator(0, "lineitem", "orderkey", "quantity");

    public HashBuildAndJoinBenchmark(LocalQueryRunner localQueryRunner)
    {
        super(localQueryRunner, "hash_build_and_join_hash_enabled_" + localQueryRunner.isHashEnabled(), 4, 5);
        this.hashEnabled = localQueryRunner.isHashEnabled();
    }

    /*
    select orderkey, quantity, totalprice
    from lineitem join orders using (orderkey)
     */
    @Override
    protected List<Driver> createDrivers(TaskContext taskContext)
    {
        ImmutableList.Builder<OperatorFactory> driversBuilder = ImmutableList.builder();
        driversBuilder.add(ordersTableScan);
        OperatorFactory source = ordersTableScan;
        Optional<Integer> hashChannel = Optional.absent();
        if (hashEnabled) {
            source = createHashProjectOperator(1, ImmutableList.<Type>of(BIGINT, DOUBLE), Ints.asList(0));
            driversBuilder.add(source);
            hashChannel = Optional.of(2);
        }

        // hash build
        HashBuilderOperatorFactory hashBuilder = new HashBuilderOperatorFactory(2, source.getTypes(), Ints.asList(0), hashChannel, 1_500_000);
        driversBuilder.add(hashBuilder);
        DriverFactory hashBuildDriverFactory = new DriverFactory(true, false, driversBuilder.build());
        Driver hashBuildDriver = hashBuildDriverFactory.createDriver(taskContext.addPipelineContext(true, false).addDriverContext());

        // join
        ImmutableList.Builder<OperatorFactory> joinDriversBuilder = ImmutableList.builder();
        joinDriversBuilder.add(lineItemTableScan);
        source = lineItemTableScan;
        hashChannel = Optional.absent();
        if (hashEnabled) {
            source = createHashProjectOperator(1, ImmutableList.<Type>of(BIGINT, BIGINT), Ints.asList(0));
            joinDriversBuilder.add(source);
            hashChannel = Optional.of(2);
        }

        OperatorFactory joinOperator = LookupJoinOperators.innerJoin(1, hashBuilder.getLookupSourceSupplier(), source.getTypes(), Ints.asList(0), hashChannel);
        joinDriversBuilder.add(joinOperator);
        joinDriversBuilder.add(new NullOutputOperatorFactory(2, joinOperator.getTypes()));
        DriverFactory joinDriverFactory = new DriverFactory(true, true, joinDriversBuilder.build());
        Driver joinDriver = joinDriverFactory.createDriver(taskContext.addPipelineContext(true, true).addDriverContext());

        return ImmutableList.of(hashBuildDriver, joinDriver);
    }

    public static void main(String[] args)
    {
        new HashBuildAndJoinBenchmark(createLocalQueryRunner()).runBenchmark(new SimpleLineBenchmarkResultWriter(System.out));
        new HashBuildAndJoinBenchmark(createLocalQueryRunnerHashEnabled()).runBenchmark(new SimpleLineBenchmarkResultWriter(System.out));
    }
}
