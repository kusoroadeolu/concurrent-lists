package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    @Outcome(id = {"0, 0, 0", "0, 0, 1"}, expect = ACCEPTABLE, desc = "b and c not visible, a ~written to")
    @Outcome(id = {"0, 1, 1"}, expect = ACCEPTABLE_INTERESTING, desc = "c got ordered before b | c visible before b")
    @Outcome(id = {"1, 0, 1", "1, 1, 1"}, expect = ACCEPTABLE, desc = "boring")
    @Outcome(id = {"0, 1, 0", "1, 0, 0", "1, 1, 0"}, expect = ACCEPTABLE_INTERESTING, desc = "b and c might have been written to, a not visible")

    @State
    public static class RAFenceStress {
        int a, b, c = 0;


        @Actor
        public void writer() {
            a = 1;
            VarHandle.storeStoreFence();
            b = 1;
            c = 1;
        }

        /* //Invalid (wrote to b and c), but a is absent
        * Probable outcomes
        * //Did not write to b or c, maybe wrote to a (ran before writing to a)
        * 000, 001,
        * //c got ordered before b
        * 011,
        * // Borign
        * 101,  111
        *
        * Invalid
        * 010, 110, 100
        *
        * */


        //Invalid a = 0, b = 1;
        @Actor
        public void reader(III_Result r) {
            r.r1 = b;
            r.r2 = c;
            VarHandle.loadLoadFence();
            r.r3 = a;
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

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = ACCEPTABLE,             desc = "Gracefully finished")
    @Outcome(id = "STALE",      expect = ACCEPTABLE_INTERESTING, desc = "Test is stuck")
    @State
    //Assert deleted nodes are never inserted
    public static class RWVisibility {
        private ReadWriteLock rwLock = new ReentrantReadWriteLock();
        private boolean signal;

        @Signal
        public void readLocker() {
            rwLock.readLock().lock();
            try {
                signal = true;
            }finally {
                rwLock.readLock().unlock();
            }
        }

        @Actor
        public void writeLocker() {
            for (;;) {
                rwLock.writeLock().lock();
                try {
                    if (signal) break;
                }finally {
                    rwLock.writeLock().unlock();
                }
            }
        }

    }

}
