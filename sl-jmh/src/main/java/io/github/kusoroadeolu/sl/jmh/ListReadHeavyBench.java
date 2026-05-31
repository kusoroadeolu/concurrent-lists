package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


/*
* Benchmark                             (type)   Mode  Cnt  Score    Error   Units
ListReadHeavyBench.eightThreads        LF_FR  thrpt   30  0.071 ±  0.002  ops/us
ListReadHeavyBench.eightThreads         LAZY  thrpt   30  0.079 ±  0.003  ops/us
ListReadHeavyBench.eightThreads         LOCK  thrpt   30  0.015 ±  0.001  ops/us
ListReadHeavyBench.eightThreads  LAZY_COARSE  thrpt   30  0.078 ±  0.003  ops/us
ListReadHeavyBench.fourThreads         LF_FR  thrpt   30  0.049 ±  0.001  ops/us
ListReadHeavyBench.fourThreads          LAZY  thrpt   30  0.047 ±  0.003  ops/us
ListReadHeavyBench.fourThreads          LOCK  thrpt   30  0.017 ±  0.001  ops/us
ListReadHeavyBench.fourThreads   LAZY_COARSE  thrpt   30  0.051 ±  0.001  ops/us
ListReadHeavyBench.twoThreads          LF_FR  thrpt   30  0.027 ±  0.003  ops/us
ListReadHeavyBench.twoThreads           LAZY  thrpt   30  0.029 ±  0.002  ops/us
ListReadHeavyBench.twoThreads           LOCK  thrpt   30  0.018 ±  0.001  ops/us
ListReadHeavyBench.twoThreads    LAZY_COARSE  thrpt   30  0.032 ±  0.001  ops/us
* */

/*
* Benchmark                             (type)  Mode  Cnt    Score    Error  Units
ListReadHeavyBench.eightThreads        LF_FR  avgt   30  110.736 ±  4.079  us/op
ListReadHeavyBench.eightThreads         LAZY  avgt   30   97.138 ±  2.577  us/op
ListReadHeavyBench.eightThreads         LOCK  avgt   30  488.376 ± 15.631  us/op
ListReadHeavyBench.eightThreads  LAZY_COARSE  avgt   30   96.848 ±  2.490  us/op
ListReadHeavyBench.fourThreads         LF_FR  avgt   30   79.114 ±  1.126  us/op
ListReadHeavyBench.fourThreads          LAZY  avgt   30   76.837 ±  1.412  us/op
ListReadHeavyBench.fourThreads          LOCK  avgt   30  252.087 ± 14.362  us/op
ListReadHeavyBench.fourThreads   LAZY_COARSE  avgt   30   87.068 ±  6.050  us/op
ListReadHeavyBench.twoThreads          LF_FR  avgt   30   78.709 ±  4.626  us/op
ListReadHeavyBench.twoThreads           LAZY  avgt   30   83.519 ±  4.822  us/op
ListReadHeavyBench.twoThreads           LOCK  avgt   30  157.357 ± 16.030  us/op
ListReadHeavyBench.twoThreads    LAZY_COARSE  avgt   30   79.521 ±  3.725  us/op
* */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class ListReadHeavyBench {
    private ConcurrentListSet<Integer> set;
    @Param({"LF_FR", "LAZY", "LOCK", "LAZY_COARSE"}) //LOCK FREE, LAZY and a lock based tree set
    private String type;

    @Setup
    public void setup() {
        set = switch (type) {
            case "LF_FR" -> new ConcurrentOrderedList<>();
            case "LAZY" -> new LazySyncList<>();
            case "LAZY_COARSE" -> new LazyCoarseSyncList<>();
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

        if (op < 90) {
            bh.consume(set.contains(key));
        } else if (op < 99) {
            bh.consume(set.add(key));
        } else {
            bh.consume(set.remove(key));
        }
    }
}
