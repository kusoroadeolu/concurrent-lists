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
* */

//UNROLLED Variants
/* ArraySize Per Node = 64
Benchmark                                (type)   Mode  Cnt  Score   Error   Units
ListWriteHeavyBench.eightThreads     LOCAL_ELIM  thrpt   30  2.317 ± 0.121  ops/us
ListWriteHeavyBench.eightThreads  ELIM_UNROLLED  thrpt   30  3.262 ± 0.266  ops/us
ListWriteHeavyBench.eightThreads       UNROLLED  thrpt   30  4.228 ± 0.140  ops/us
ListWriteHeavyBench.fourThreads      LOCAL_ELIM  thrpt   30  1.928 ± 0.107  ops/us
ListWriteHeavyBench.fourThreads   ELIM_UNROLLED  thrpt   30  2.707 ± 0.110  ops/us
ListWriteHeavyBench.fourThreads        UNROLLED  thrpt   30  3.025 ± 0.081  ops/us



Benchmark                                (type)  Mode  Cnt  Score   Error  Units
ListWriteHeavyBench.eightThreads     LOCAL_ELIM  avgt   30  2.969 ± 0.153  us/op
ListWriteHeavyBench.eightThreads  ELIM_UNROLLED  avgt   30  2.191 ± 0.108  us/op
ListWriteHeavyBench.eightThreads       UNROLLED  avgt   30  2.053 ± 0.086  us/op
ListWriteHeavyBench.fourThreads      LOCAL_ELIM  avgt   30  1.697 ± 0.044  us/op
ListWriteHeavyBench.fourThreads   ELIM_UNROLLED  avgt   30  1.200 ± 0.027  us/op
ListWriteHeavyBench.fourThreads        UNROLLED  avgt   30  1.224 ± 0.109  us/op
* */

/*
Benchmark                              (type)   Mode  Cnt  Score   Error   Units
ListWriteHeavyBench.eightThreads  EF_UNROLLED  thrpt   30  0.041 ± 0.006  ops/us
ListWriteHeavyBench.fourThreads   EF_UNROLLED  thrpt   30  0.038 ± 0.005  ops/us
*
* */



@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class ListWriteHeavyBench { //50% adds, 40% removes, 10% contains
    private ConcurrentCollection<Integer> set;

    @Param({"UNROLLED", "LF_FR", "LAZY", "LAZY_COARSE", "LOCK", "EF_UNROLLED", "ELIM_UNROLLED", "LOCAL_ELIM", "ELIM_UNROLLED", "UNROLLED"})
    private String type;

    @Setup
    public void setup() {
        set = switch (type) {
            case "LF_FR" -> new ConcurrentOrderedList<>();
            case "ELIM_UNROLLED" -> new EliminationUnrolledConcurrentList<>();
            case "LAZY" -> new LazySyncList<>();
            case "LAZY_COARSE" -> new LazyCoarseSyncList<>();
            case "LOCK" -> new LockedOrderedLL<>();
            case "UNROLLED" -> new UnrolledConcurrentList<>();
            case "EF_UNROLLED" -> new EFUnrolledConcurrentList<>();
            case "LOCAL_ELIM" -> new LocalEFUnrolledConcurrentList<>();
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
