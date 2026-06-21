package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

import java.lang.invoke.VarHandle;
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

    @JCStressTest
    @Outcome(id = {"1, 1", "0, 1", "0, 0"}, expect = ACCEPTABLE, desc = "Acceptable")
    @State
    public static class RAFenceStress {
        int a = 0;
        int b = 0;


        @Actor
        public void writer() {
            a = 1;
            VarHandle.releaseFence();
            b = 1;
        }


        //Invalid a = 0, b = 1;
        @Actor
        public void reader(II_Result r) {
            r.r1 = a;
            r.r2 = b;
        }
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = ACCEPTABLE,             desc = "Gracefully finished")
    @Outcome(id = "STALE",      expect = ACCEPTABLE_INTERESTING, desc = "Test is stuck")
    @State
    public static class RAFenceVisibilityStress {
        boolean ready = false;

        @Actor
        public void actor() {
            do {
                VarHandle.acquireFence();
            }while (!ready);
        }

        @Signal
        public void signaller() {
            ready = true;
            VarHandle.releaseFence();
        }
    }
    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = ACCEPTABLE,             desc = "Gracefully finished")
    @Outcome(id = "STALE",      expect = ACCEPTABLE_INTERESTING, desc = "Test is stuck")
    @State
    public static class RAFenceMultiVisibilityStress {
        boolean ready = false;
        boolean done = false;

        @Actor
        public void actor() {
            do {
                VarHandle.acquireFence();
            }while (!ready && !done);
        }

        @Signal
        public void signaller() {
            ready = true;
            VarHandle.releaseFence();
            done = true;
            VarHandle.releaseFence();
        }
    }

}
