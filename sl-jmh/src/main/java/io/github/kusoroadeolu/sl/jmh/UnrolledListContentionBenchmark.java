package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.EliminationMetrics;
import io.github.kusoroadeolu.sl.EliminationUnrolledLinkedList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Benchmark)
@Threads(8)


/* 100% Writes
Benchmark                                                 (keySpaceSize)         (type)   Mode  Cnt  Score   Error   Units
UnrolledListContentionBenchmark.fullWrite                             64  ELIM_UNROLLED  thrpt   30  3.131 ± 0.096  ops/us
UnrolledListContentionBenchmark.fullWrite:arenaSuccesses              64  ELIM_UNROLLED  thrpt   30  1.217 ± 0.037  ops/us
UnrolledListContentionBenchmark.fullWrite:nodeSuccesses               64  ELIM_UNROLLED  thrpt   30  1.307 ± 0.045  ops/us
UnrolledListContentionBenchmark.fullWrite                            128  ELIM_UNROLLED  thrpt   30  2.132 ± 0.426  ops/us
UnrolledListContentionBenchmark.fullWrite:arenaSuccesses             128  ELIM_UNROLLED  thrpt   30  0.806 ± 0.183  ops/us
UnrolledListContentionBenchmark.fullWrite:nodeSuccesses              128  ELIM_UNROLLED  thrpt   30  0.931 ± 0.157  ops/us
UnrolledListContentionBenchmark.fullWrite                            256  ELIM_UNROLLED  thrpt   30  2.363 ± 0.475  ops/us
UnrolledListContentionBenchmark.fullWrite:arenaSuccesses             256  ELIM_UNROLLED  thrpt   30  0.911 ± 0.191  ops/us
UnrolledListContentionBenchmark.fullWrite:nodeSuccesses              256  ELIM_UNROLLED  thrpt   30  0.999 ± 0.189  ops/us */

/*
* so i did some profiling(for the suspicious results) and its pretty surprising.
* My guess that  maybe it was benchmarking issue or JIT not warming up fully, but after looking at the profile data, i realized that the contention was situated solely in the add method(especially in the elimination arena).
* Now while this doesnt mean much, I dug deeper and the main path that was flagged by the profiler was the inner spin loop while a thread is waiting to be eliminated.
* That only meant one thing, threads were waiting the full sprint in the elimination arena, which also meant two things either
* 1. Removes were never reaching the elim arena or
* 2. Removes were just unlucky and the values of removes were never equal to that of adds in the elim arena.
* I then looked at the remove side, surely if removes were reaching the elim arena we'll see some cpu samples there, but they weren't.
* Upwards the main contention path for removes was checking if a value existed in a node. So all in all, for the structure to get such low thrpt,
*  removes are highly dependent on value, meaning if it doesnt exist in the node,
* they never make it to the elim arena, subsequently, for adds, if it exists in the node, it'd never make it to the elim arena which is counterintuitive haha.
*
* 2 simple ways to reduce this were:
* 1. Remove the set invariant
* 2. Force remove ops to always scan that node's elim array if the value wasn't present in the list
*
* While this didnt fully get rid of the issue(as the high err margins in some results) it increased the number of successful eliminations in the arena to an almost 1:1 ratio with the node successes
* and reduced the amount of times this happened throughout the benchmark
* */
public class UnrolledListContentionBenchmark {

    @Param({"64", "128", "256"})
    int keySpaceSize;

    @Param({"ELIM_UNROLLED"})
    private String type;

    private EliminationUnrolledLinkedList<Integer> set;
    private ZipfianGenerator zipf;

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class ThreadState {
        SplittableRandom rng;
        public int nodeSuccesses;
        public int arenaSuccesses;

        @Setup(Level.Trial)
        public void setup() {
            rng = new SplittableRandom();
        }

        @TearDown(Level.Iteration)
        public void teardown(UnrolledListContentionBenchmark benchmark) {
            EliminationMetrics m = benchmark.set.metrics();
            nodeSuccesses  = m.nodeSuccesses();
            arenaSuccesses = m.arenaSuccesses();
            m.reset();
        }
    }

    @TearDown
    public void teardown() {
        List<Integer> ls = set.toList();
        for (int i : ls) {
            set.remove(i);
        }

        ls.clear();
    }

    @Setup(Level.Trial)
    public void setup() {

        set = switch (type) {
            case "ELIM_UNROLLED" -> new EliminationUnrolledLinkedList<>();
            default -> throw new IllegalArgumentException();
        };
        zipf      = new ZipfianGenerator(keySpaceSize, 2.0);
    }


//    @Benchmark
//    public void eightyWriteTwentyRead(ThreadState ts, Blackhole bh) {
//        op(set, ts, bh);
//    }

    @Benchmark
    public void fullWrite(ThreadState ts, Blackhole bh) {
        fullWrite(set, ts, bh);
    }


    private void op(EliminationUnrolledLinkedList<Integer> set, ThreadState ts, Blackhole bh) {
        int key = zipf.nextInt(ts.rng);
        if (ts.rng.nextDouble() < 0.80) {
            if (ts.rng.nextBoolean()) bh.consume(set.add(key));
            else bh.consume(set.remove(key));
        } else {
            bh.consume(set.contains(key));
        }
    }

    private void fullWrite(EliminationUnrolledLinkedList<Integer> set, ThreadState ts, Blackhole bh) {
        int key = zipf.nextInt(ts.rng);
        if (ts.rng.nextDouble(1) < 0.5) bh.consume(set.add(key));
        else bh.consume(set.remove(key));
    }

    static final class ZipfianGenerator {
        private final int      n;
        private final double[] cdf;

        ZipfianGenerator(int n, double exponent) {
            this.n   = n;
            this.cdf = new double[n];
            double sum = 0;
            for (int i = 1; i <= n; i++) sum += 1.0 / Math.pow(i, exponent);
            double running = 0;
            for (int i = 0; i < n; i++) {
                running += (1.0 / Math.pow(i + 1, exponent)) / sum;
                cdf[i]   = running;
            }
        }

        int nextInt(SplittableRandom rng) {
            double u  = rng.nextDouble();
            int lo = 0, hi = n - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) lo = mid + 1;
                else              hi = mid;
            }
            return lo;
        }
    }


    static class BenchRunner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(UnrolledListContentionBenchmark.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }
}