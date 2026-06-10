package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


/*
* Benchmark                              (type)   Mode  Cnt  Score    Error   Units
ListWriteHeavyBench.eightThreads         LF_FR  thrpt   30  0.153 ± 0.007  ops/us
ListWriteHeavyBench.eightThreads         LAZY  thrpt   30  0.143 ±  0.004  ops/us
ListWriteHeavyBench.eightThreads         LOCK  thrpt   30  0.026 ±  0.001  ops/us
ListWriteHeavyBench.eightThreads  LAZY_COARSE  thrpt   30  0.147 ±  0.002  ops/us
ListWriteHeavyBench.fourThreads          LF_FR  thrpt   30  0.080 ± 0.003  ops/us
ListWriteHeavyBench.fourThreads          LAZY  thrpt   30  0.081 ±  0.002  ops/us
ListWriteHeavyBench.fourThreads          LOCK  thrpt   30  0.026 ±  0.001  ops/us
ListWriteHeavyBench.fourThreads   LAZY_COARSE  thrpt   30  0.083 ±  0.004  ops/us
ListWriteHeavyBench.twoThreads           LF_FR  thrpt   30  0.043 ± 0.001  ops/us
ListWriteHeavyBench.twoThreads           LAZY  thrpt   30  0.053 ±  0.002  ops/us
ListWriteHeavyBench.twoThreads           LOCK  thrpt   30  0.029 ±  0.001  ops/us
ListWriteHeavyBench.twoThreads    LAZY_COARSE  thrpt   30  0.054 ±  0.002  ops/us
* */

/*
* Benchmark                              (type)  Mode  Cnt    Score    Error  Units
ListWriteHeavyBench.eightThreads         LF_FR  avgt   30  51.434 ± 0.890  us/op
ListWriteHeavyBench.eightThreads         LAZY  avgt   30   54.703 ±  1.227  us/op
ListWriteHeavyBench.eightThreads         LOCK  avgt   30  322.620 ± 20.590  us/op
ListWriteHeavyBench.eightThreads  LAZY_COARSE  avgt   30   52.474 ±  1.720  us/op
ListWriteHeavyBench.fourThreads          LF_FR  avgt   30  51.607 ± 2.077  us/op
ListWriteHeavyBench.fourThreads          LAZY  avgt   30   45.701 ±  1.228  us/op
ListWriteHeavyBench.fourThreads          LOCK  avgt   30  153.047 ±  1.507  us/op
ListWriteHeavyBench.fourThreads   LAZY_COARSE  avgt   30   45.808 ±  1.560  us/op
ListWriteHeavyBench.twoThreads           LF_FR  avgt   30  46.381 ± 1.604  us/op
ListWriteHeavyBench.twoThreads           LAZY  avgt   30   37.030 ±  1.524  us/op
ListWriteHeavyBench.twoThreads           LOCK  avgt   30   68.582 ±  1.676  us/op
ListWriteHeavyBench.twoThreads    LAZY_COARSE  avgt   30   36.923 ±  1.415  us/op
* */

//UNROLLED Variants
/* ArraySize Per Node = 64
Benchmark                           (type)   Mode  Cnt  Score   Error   Units
ListWriteHeavyBench.eightThreads  UNROLLED  thrpt   30  3.871 ± 0.056  ops/us
ListWriteHeavyBench.fourThreads   UNROLLED  thrpt   30  2.512 ± 0.043  ops/us
ListWriteHeavyBench.twoThreads    UNROLLED  thrpt   30  1.706 ± 0.079  ops/us

Benchmark                           (type)  Mode  Cnt  Score   Error  Units
ListWriteHeavyBench.eightThreads  UNROLLED  avgt   30  1.938 ± 0.032  us/op
ListWriteHeavyBench.fourThreads   UNROLLED  avgt   30  1.194 ± 0.022  us/op
ListWriteHeavyBench.twoThreads    UNROLLED  avgt   30  0.844 ± 0.044  us/op
* */

/*
Benchmark                              (type)   Mode  Cnt  Score   Error   Units
ListWriteHeavyBench.eightThreads  EF_UNROLLED  thrpt   30  0.041 ± 0.006  ops/us
ListWriteHeavyBench.fourThreads   EF_UNROLLED  thrpt   30  0.038 ± 0.005  ops/us
ListWriteHeavyBench.twoThreads    EF_UNROLLED  thrpt   30  0.032 ± 0.003  ops/us
*
* */

/*
*
Benchmark                                (type)   Mode  Cnt  Score   Error   Units
ListWriteHeavyBench.eightThreads  ELIM_UNROLLED  thrpt   30  2.694 ± 0.210  ops/us
ListWriteHeavyBench.fourThreads   ELIM_UNROLLED  thrpt   30  2.136 ± 0.103  ops/us
ListWriteHeavyBench.twoThreads    ELIM_UNROLLED  thrpt   30  1.546 ± 0.082  ops/us
* */

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class ListWriteHeavyBench { //50% adds, 40% removes, 10% contains
    private ConcurrentListSet<Integer> set;

    @Param({"UNROLLED", "LF_FR", "LAZY", "LAZY_COARSE", "LOCK", "EF_UNROLLED", "ELIM_UNROLLED"})
    private String type;

    @Setup
    public void setup() {
        set = switch (type) {
            case "LF_FR" -> new ConcurrentOrderedList<>();
            case "ELIM_UNROLLED" -> new EliminationUnrolledLinkedList<>(128, 32);
            case "LAZY" -> new LazySyncList<>();
            case "LAZY_COARSE" -> new LazyCoarseSyncList<>();
            case "LOCK" -> new LockedOrderedLL<>();
            case "UNROLLED" -> new UnrolledConcurrentList<>();
            case "EF_UNROLLED" -> new EFUnrolledConcurrentList<>();
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


    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        doWork(bh);
    }

    @Threads(4)
    @Benchmark
    public void fourThreads(Blackhole bh) {
        doWork(bh);
    }


    @Threads(2)
    @Benchmark
    public void twoThreads(Blackhole bh) {
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

    static class BenchRunner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(ListWriteHeavyBench.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }
}
