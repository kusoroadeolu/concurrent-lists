package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

public class UnrolledListStress {
    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class OrderedAnchorStress {
        public  UnrolledConcurrentList<Integer> set;

        public OrderedAnchorStress() {
            this.set = new UnrolledConcurrentList<>(2, 1);
        }


        @Actor
        public void actor() {
            set.add(2);
            set.remove(3);
        }

        @Actor
        public void actor2() {
            set.add(3);
            set.add(4);
        }

        @Actor
        public void actor3() {
            set.remove(2);
            set.add(5);
        }



        @Arbiter
        public void arbiter(I_Result r) {
            var ls = set.anchorList();
            boolean isSorted = IntStream.range(0, ls.size() - 1)
                    .allMatch(i -> ls.get(i) <= ls.get(i + 1));
            r.r1 = isSorted ? 1 : 0;
        }
    }


    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class AnchorInvariantStress {
        public  UnrolledConcurrentList<Integer> set;

        public AnchorInvariantStress() {
            this.set = new UnrolledConcurrentList<>(2, 1);
        }


        @Actor
        public void actor() {
            set.add(2);
            set.remove(3);
        }

        @Actor
        public void actor2() {
            set.add(3);
            set.add(4);
        }

        @Actor
        public void actor3() {
            set.remove(2);
            set.add(5);
        }



        @Arbiter
        public void arbiter(I_Result r) {
            var map = set.nodeMap();
            boolean isLess = true;

            for(Map.Entry<Integer, List<Integer>> entry : map.entrySet()) {
                var anchor = entry.getKey();
                var ls = entry.getValue();
                for (int i : ls) {
                    if (i < anchor) {
                        isLess = false;
                        break;
                    }
                }

            }

            r.r1 = isLess ? 1 : 0;
        }
    }
}
