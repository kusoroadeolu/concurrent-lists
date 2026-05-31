package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


/*
* Benchmark                              (type)   Mode  Cnt  Score    Error   Units
ListWriteHeavyBench.eightThreads        LF_FR  thrpt   30  0.125 ±  0.003  ops/us
ListWriteHeavyBench.eightThreads         LAZY  thrpt   30  0.143 ±  0.004  ops/us
ListWriteHeavyBench.eightThreads         LOCK  thrpt   30  0.026 ±  0.001  ops/us
ListWriteHeavyBench.eightThreads  LAZY_COARSE  thrpt   30  0.147 ±  0.002  ops/us
ListWriteHeavyBench.fourThreads         LF_FR  thrpt   30  0.082 ±  0.003  ops/us
ListWriteHeavyBench.fourThreads          LAZY  thrpt   30  0.081 ±  0.002  ops/us
ListWriteHeavyBench.fourThreads          LOCK  thrpt   30  0.026 ±  0.001  ops/us
ListWriteHeavyBench.fourThreads   LAZY_COARSE  thrpt   30  0.083 ±  0.004  ops/us
ListWriteHeavyBench.twoThreads          LF_FR  thrpt   30  0.052 ±  0.002  ops/us
ListWriteHeavyBench.twoThreads           LAZY  thrpt   30  0.053 ±  0.002  ops/us
ListWriteHeavyBench.twoThreads           LOCK  thrpt   30  0.029 ±  0.001  ops/us
ListWriteHeavyBench.twoThreads    LAZY_COARSE  thrpt   30  0.054 ±  0.002  ops/us
* */

/*
* Benchmark                              (type)  Mode  Cnt    Score    Error  Units
ListWriteHeavyBench.eightThreads        LF_FR  avgt   30   57.631 ±  1.021  us/op
ListWriteHeavyBench.eightThreads         LAZY  avgt   30   54.703 ±  1.227  us/op
ListWriteHeavyBench.eightThreads         LOCK  avgt   30  322.620 ± 20.590  us/op
ListWriteHeavyBench.eightThreads  LAZY_COARSE  avgt   30   52.474 ±  1.720  us/op
ListWriteHeavyBench.fourThreads         LF_FR  avgt   30   46.756 ±  1.750  us/op
ListWriteHeavyBench.fourThreads          LAZY  avgt   30   45.701 ±  1.228  us/op
ListWriteHeavyBench.fourThreads          LOCK  avgt   30  153.047 ±  1.507  us/op
ListWriteHeavyBench.fourThreads   LAZY_COARSE  avgt   30   45.808 ±  1.560  us/op
ListWriteHeavyBench.twoThreads          LF_FR  avgt   30   37.604 ±  1.490  us/op
ListWriteHeavyBench.twoThreads           LAZY  avgt   30   37.030 ±  1.524  us/op
ListWriteHeavyBench.twoThreads           LOCK  avgt   30   68.582 ±  1.676  us/op
ListWriteHeavyBench.twoThreads    LAZY_COARSE  avgt   30   36.923 ±  1.415  us/op
* */

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class ListWriteHeavyBench { //50% adds, 40% removes, 10% contains
    private ConcurrentListSet<Integer> set;
    @Param({"LF_FR", "LAZY", "LOCK", "LAZY_COARSE"}) //LOCK FREE, LAZY and a lock based tree set
    private String type;

    @Setup
    public void setup() {
        set = switch (type) {
            case "LF_FR" -> new ConcurrentOrderedList<>();
            case "LAZY" -> new LazyOptimisticList<>();
            case "LAZY_COARSE" -> new LazyCoarseOptimisticList<>();
            case "LOCK" -> new LockedOrderedLL<>();
            default -> throw new IllegalArgumentException();
        };

    }

    @TearDown
    public void teardown() {
        List<Integer> ls = set.toList();
        for (int i : ls) {
            set.remove(i);
        }

        ls.clear();
    }

    @Threads(2)
    @Benchmark
    public void twoThreads(Blackhole bh) {
        doWork(bh);
    }

    @Threads(4)
    @Benchmark
    public void fourThreads(Blackhole bh) {
        doWork(bh);
    }

    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        doWork(bh);
    }


    private void doWork(Blackhole bh) {
        int key = ThreadLocalRandom.current().nextInt(10_000);
        int op = ThreadLocalRandom.current().nextInt(100);

        if (op < 50) {
            bh.consume(set.add(key));
        } else if (op < 90) {
            bh.consume(set.remove(key));
        } else{
            bh.consume(set.contains(key));
        }
    }
}
