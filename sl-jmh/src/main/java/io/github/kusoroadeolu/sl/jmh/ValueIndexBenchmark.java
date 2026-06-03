package io.github.kusoroadeolu.sl.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class ValueIndexBenchmark {

    private static final int ARRAY_CAP = 64;
    private int[] data;
    private int target;

    @Param({"COMBINED", "SPLIT"})
    public String type;

    @Setup
    public void setup() {
        data = new int[ARRAY_CAP];

        // fill with some values, leave some null to be realistic
        for (int i = 0; i < ARRAY_CAP; i++) {
            data[i] = (i % (ThreadLocalRandom.current().nextInt(4) + 1) == 0) ? -1 : i;
        }

        target = 7; // something in the middle
    }

    @Benchmark
    public void loopOptimization(Blackhole blackhole){
        if (type.equals("COMBINED")) blackhole.consume(combined());
        else blackhole.consume(split());
    }

    public int[] combined() {
        int[] indices = new int[2];
        int size = 0;
        var d = data;
        for (int i = 0; i < ARRAY_CAP; ++i) {
            int v = d[i];
            if (v != -1) {
                if (target == v) {
                    indices[0] = i;
                }
                size++;
            }
        }

        indices[1] = size;
        return indices;
    }


    public int[] split() {
        int[] indices = new int[2];
        var d = data;
        for (int i = 0; i < ARRAY_CAP; ++i) {
            int v = d[i];
            if (v != -1 && target == v) {
                indices[0] = i;
                break;
            }
        }

        // Loop 2: just count non-nulls
        int size = 0;
        for (int i = 0; i < ARRAY_CAP; ++i) {
            if (d[i] != -1) size++;
        }

        indices[1] = size;
        return indices;
    }
}