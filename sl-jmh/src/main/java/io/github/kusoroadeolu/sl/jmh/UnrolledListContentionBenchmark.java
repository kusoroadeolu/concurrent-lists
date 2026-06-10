package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Benchmark)
@Threads(8)

/*
* Benchmark                                              (keySpaceSize)         (type)   Mode  Cnt  Score   Error   Units
UnrolledListContentionBenchmark.eightyWriteTwentyRead              64       UNROLLED  thrpt   30  2.070 ± 0.035  ops/us
UnrolledListContentionBenchmark.eightyWriteTwentyRead              64  ELIM_UNROLLED  thrpt   30  2.466 ± 0.135  ops/us
UnrolledListContentionBenchmark.eightyWriteTwentyRead             128       UNROLLED  thrpt   30  1.873 ± 0.247  ops/us
UnrolledListContentionBenchmark.eightyWriteTwentyRead             128  ELIM_UNROLLED  thrpt   30  2.337 ± 0.126  ops/us
UnrolledListContentionBenchmark.eightyWriteTwentyRead             256       UNROLLED  thrpt   30  2.180 ± 0.122  ops/us
UnrolledListContentionBenchmark.eightyWriteTwentyRead             256  ELIM_UNROLLED  thrpt   30  2.215 ± 0.289  ops/us
* */
public class UnrolledListContentionBenchmark {

    @Param({"64", "128", "256"})
    int keySpaceSize;

    @Param({"UNROLLED", "ELIM_UNROLLED"})
    private String type;

    private ConcurrentListSet<Integer> set;
    private ZipfianGenerator zipf;

    @State(Scope.Thread)
    public static class ThreadState {
        SplittableRandom rng;

        @Setup(Level.Trial)
        public void setup() {
            rng = new SplittableRandom();
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
            case "UNROLLED" -> new UnrolledConcurrentList<>();
            default -> throw new IllegalArgumentException();
        };
        zipf      = new ZipfianGenerator(keySpaceSize, 1.2);
    }


    @Benchmark
    public void eightyWriteTwentyRead(ThreadState ts, Blackhole bh) {
        op(set, ts, bh);
    }

    private void op(Set<Integer> set, ThreadState ts, Blackhole bh) {
        int key = zipf.nextInt(ts.rng);
        if (ts.rng.nextDouble() < 0.80) {
            if (ts.rng.nextBoolean()) set.add(key);
            else                      set.remove(key);
        } else {
            bh.consume(set.contains(key));
        }
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
}