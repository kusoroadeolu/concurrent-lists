package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;

public class MiscStress {
    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = ACCEPTABLE,             desc = "Gracefully finished")
    @Outcome(id = "STALE",      expect = ACCEPTABLE_INTERESTING, desc = "Test is stuck")
    @State
    public static class LockBasedReads {
        boolean ready = false;
        private Lock lock = new ReentrantLock();

        @Actor
        public void actor() {
            while (!ready);
        }

        @Signal
        public void signaller() {
            lock.lock();
            try {
                ready = true;
            }finally {
                lock.unlock();
            }
        }
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = ACCEPTABLE,             desc = "Gracefully finished")
    @Outcome(id = "STALE",      expect = ACCEPTABLE_INTERESTING, desc = "Test is stuck")
    @State
    public static class DoubleHappensBefore {
        boolean ready = false;
        private AtomicIntegerArray array = new AtomicIntegerArray(2);

        @Actor
        public void actor() {
            while (array.getAcquire(1) == 0 && !ready);
        }

        @Signal
        public void signaller() {
            ready = true;
            array.setRelease(0, 1);
            array.setRelease(1, 1);
        }
    }
}
