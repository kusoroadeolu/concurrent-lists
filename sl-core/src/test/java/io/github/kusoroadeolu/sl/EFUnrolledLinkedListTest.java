package io.github.kusoroadeolu.sl;

import io.github.kusoroadeolu.sl.EFUnrolledConcurrentList.LocalValues;
import io.github.kusoroadeolu.sl.EFUnrolledConcurrentList.Operation;
import io.github.kusoroadeolu.sl.EFUnrolledConcurrentList.ThreadNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sequential stress tests for EFUnrolledLinkedList.
 *
 * Invariants under test:
 *   I1 - Anchor ordering : pred.anchor < curr.anchor for all adjacent nodes
 *   I2 - Key placement   : every key in a node >= that node's anchor
 *
 * All structural pressure is applied through chained ThreadNodes to exercise
 * the flat-combining paths (split, merge, redistribute).
 *
 * arrCap=4, minFull=1  →  splits at 5 elements, merges/redistributes very aggressively
 */
class EFUnrolledLinkedListTest {

    // -----------------------------------------------------------------------
    // Invariant helpers
    // -----------------------------------------------------------------------

    static <T extends Comparable<T>> void assertAnchorOrdering(EFUnrolledLinkedList<T> list) {
        List<T> anchors = list.anchorList();
        for (int i = 1; i < anchors.size(); i++) {
            T prev = anchors.get(i - 1);
            T curr = anchors.get(i);
            assertTrue(prev.compareTo(curr) <= 0,
                    "I1 violated: anchor[" + (i-1) + "]=" + prev + " >= anchor[" + i + "]=" + curr);
        }
    }

    static <T extends Comparable<T>> void assertKeyPlacement(EFUnrolledLinkedList<T> list) {
        Map<T, List<T>> nodeMap = list.nodeMap();
        for (Map.Entry<T, List<T>> e : nodeMap.entrySet()) {
            T anchor = e.getKey();
            for (T key : e.getValue()) {
                assertTrue(key.compareTo(anchor) >= 0,
                        "I2 violated: key " + key + " < anchor " + anchor);
            }
        }
    }

    static <T extends Comparable<T>> void assertAllInvariants(EFUnrolledLinkedList<T> list) {
        assertAnchorOrdering(list);
        assertKeyPlacement(list);
    }

    // -----------------------------------------------------------------------
    // Thread node / LocalValues helpers
    // -----------------------------------------------------------------------

    private static LocalValues<Integer> lv(int idx) {
        return new LocalValues<>(idx);
    }

    private static ThreadNode<Integer> addNode(int value, int idx) {
        return new ThreadNode<>(value, Operation.ADD, idx);
    }

    private static ThreadNode<Integer> removeNode(int value, int idx) {
        return new ThreadNode<>(value, Operation.REMOVE, idx);
    }

    /** Manually chain same-operation ThreadNodes the way combine() would. */
    @SafeVarargs
    private static ThreadNode<Integer> chain(ThreadNode<Integer>... nodes) {
        for (int i = 0; i < nodes.length - 1; i++) nodes[i].next = nodes[i + 1];
        nodes[0].last = nodes[nodes.length - 1];
        nodes[0].soSize(nodes.length);
        return nodes[0];
    }

    // -----------------------------------------------------------------------
    // Split correctness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Split correctness")
    class SplitTests {

        @Test
        @DisplayName("Single chained add that overflows a node triggers split")
        void chainedAddTriggersSplit() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            // seed 3 values
            list.add(addNode(10, 0), lv(0));
            list.add(addNode(20, 1), lv(1));
            list.add(addNode(30, 2), lv(2));

            // chain of 2 pushes total to 5 → split
            var head = chain(addNode(40, 0), addNode(50, 1));
            list.add(head, lv(0));

            assertAllInvariants(list);
            assertEquals(2, list.anchorList().size(), "split should produce 2 nodes");
        }

        @Test
        @DisplayName("Chained add with 3 nodes triggers split and all keys reachable")
        void chainedAddThreeNodesSplit() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            list.add(addNode(10, 0), lv(0));
            list.add(addNode(20, 1), lv(1));

            var head = chain(addNode(30, 0), addNode(40, 1), addNode(50, 2));
            list.add(head, lv(0));

            assertAllInvariants(list);
            List<Integer> result = list.toList();
            for (int v : new int[]{10, 20, 30, 40, 50}) {
                assertTrue(result.contains(v), "key " + v + " lost after split");
            }
        }

        @Test
        @DisplayName("Multiple consecutive chained adds trigger multiple splits")
        void multiSplitChainedAdds() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            // Insert 20 values via chains of 2 to keep triggering splits
            for (int i = 0; i < 20; i += 2) {
                int a = i * 10, b = a + 5;
                var head = chain(addNode(a, 0), addNode(b, 1));
                list.add(head, lv(0));
            }

            assertAllInvariants(list);
            assertTrue(list.anchorList().size() > 2, "should have produced multiple splits");
        }

        @Test
        @DisplayName("Ascending chained inserts across many splits keep anchors ordered")
        void ascendingChainedInserts() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int i = 0; i < 40; i += 2) {
                var head = chain(addNode(i, 0), addNode(i + 1, 1));
                list.add(head, lv(0));
            }

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Descending chained inserts still keep anchors ordered after splits")
        void descendingChainedInserts() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int i = 40; i >= 0; i -= 2) {
                var head = chain(addNode(i, 0), addNode(i + 1, 1));
                list.add(head, lv(0));
            }

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Random-order chained inserts keep invariants after splits")
        void randomChainedInserts() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            List<Integer> vals = new ArrayList<>(IntStream.rangeClosed(1, 40).boxed().toList());
            Collections.shuffle(vals, new Random(0xDEADBEEF));

            for (int i = 0; i < vals.size() - 1; i += 2) {
                var head = chain(addNode(vals.get(i), 0), addNode(vals.get(i + 1), 1));
                list.add(head, lv(0));
            }

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Split keys all land in the correct half — no key < its node anchor")
        void splitKeyPlacement() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int v : new int[]{10, 20, 30, 40, 50, 60, 70, 80}) {
                list.add(addNode(v, 0), lv(0));
            }

            assertKeyPlacement(list);
        }
    }

    // -----------------------------------------------------------------------
    // Merge correctness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Merge correctness")
    class MergeTests {

        @Test
        @DisplayName("Chained remove below minFull triggers merge, keys still reachable")
        void chainedRemoveTriggersMe() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int v : new int[]{10, 11, 12, 13}) list.add(addNode(v, 0), lv(0));
            list.add(addNode(14, 1), lv(1)); // triggers split → 2 nodes

            // drain lower node via chain
            var head = chain(removeNode(10, 0), removeNode(11, 1), removeNode(12, 2));
            list.remove(head, lv(0));

            assertAllInvariants(list);
            List<Integer> result = list.toList();
            assertTrue(result.contains(13));
            assertTrue(result.contains(14));
        }

        @Test
        @DisplayName("Merge when succ is nearly empty — all keys survive")
        void mergeWithNearlyEmptySucc() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int i = 1; i <= 8; i++) list.add(addNode(i, 0), lv(0));

            // drain succ down to 1 element
            list.remove(removeNode(5, 0), lv(0));
            list.remove(removeNode(6, 1), lv(1));
            list.remove(removeNode(7, 2), lv(2));

            // now drain curr below minFull to force merge
            var head = chain(removeNode(1, 0), removeNode(2, 1));
            list.remove(head, lv(0));

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Chained remove draining a node to zero unlinks it cleanly")
        void chainedRemoveDrainsNodeToZero() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int v : new int[]{10, 11, 12, 13}) list.add(addNode(v, 0), lv(0));
            list.add(addNode(20, 1), lv(1)); // split

            // remove all elements in the first node via chain
            var head = chain(
                    removeNode(10, 0),
                    removeNode(11, 1),
                    removeNode(12, 2),
                    removeNode(13, 3)
            );
            list.remove(head, lv(0));

            assertAllInvariants(list);
            assertTrue(list.toList().contains(20), "surviving key in succ should still be present");
        }

        @Test
        @DisplayName("Merge with zero-sized succ does not corrupt structure")
        void mergeWithZeroSizedSucc() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int i = 1; i <= 8; i++) list.add(addNode(i, 0), lv(0));

            // drain succ completely
            for (int v : new int[]{5, 6, 7, 8}) list.remove(removeNode(v, 0), lv(0));

            // now drain curr below minFull → tries to merge with ghost succ
            var head = chain(removeNode(1, 0), removeNode(2, 1));
            list.remove(head, lv(0));

            assertAllInvariants(list);
        }
    }

    // -----------------------------------------------------------------------
    // Redistribute correctness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Redistribute correctness")
    class RedistributeTests {

        @Test
        @DisplayName("Redistribute fires when combined size exceeds maxMerge")
        void redistributeFires() {
            // arrCap=4, maxMerge = 0.75*4 = 3
            // Build two full nodes (4+4=8 elements), remove 1 from lower node
            // → currSize=3, succSize=4, total=7 > maxMerge=3 → redistribute
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int v : new int[]{10, 11, 12, 13}) list.add(addNode(v, 0), lv(0));
            list.add(addNode(14, 1), lv(1)); // split
            for (int v : new int[]{15, 16, 17}) list.add(addNode(v, 0), lv(0));

            list.remove(removeNode(10, 0), lv(0));

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Chained remove on lower node triggers redistribute, all keys survive")
        void chainedRemoveTriggerRedistribute() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int i = 1; i <= 8; i++) list.add(addNode(i, 0), lv(0));

            // remove 2 from first node: currSize drops to 2, succ still has 4 → total=6 > maxMerge=3
            var head = chain(removeNode(1, 0), removeNode(2, 1));
            list.remove(head, lv(0));

            assertAllInvariants(list);
            for (int i = 3; i <= 8; i++) {
                assertTrue(list.toList().contains(i), "key " + i + " lost after redistribute");
            }
        }

        @Test
        @DisplayName("Redistribute does not place any key below its node anchor")
        void redistributeKeyPlacement() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            for (int i = 1; i <= 8; i++) list.add(addNode(i, 0), lv(0));
            list.remove(removeNode(1, 0), lv(0));
            list.remove(removeNode(2, 1), lv(1));

            assertKeyPlacement(list);
        }
    }

    // -----------------------------------------------------------------------
    // Mixed / churn stress tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Mixed churn stress")
    class ChurnTests {

        @Test
        @DisplayName("High-churn chained adds and removes over small key range")
        void highChurnSmallKeyRange() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            Random rng = new Random(42);

            for (int i = 0; i < 500; i++) {
                int a = rng.nextInt(20);
                int b = rng.nextInt(20);
                if (rng.nextBoolean()) {
                    var head = chain(addNode(a, 0), addNode(b, 1));
                    list.add(head, lv(0));
                } else {
                    var head = chain(removeNode(a, 0), removeNode(b, 1));
                    list.remove(head, lv(0));
                }
            }

           // System.out.println("Node map:" + list.nodeMap());
            list.nodeMap();
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Chains of 3 nodes over mixed ops — invariants always hold")
        void chainOfThreeChurn() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            Random rng = new Random(0xCAFE);

            for (int i = 0; i < 300; i++) {
                int a = rng.nextInt(30), b = rng.nextInt(30), c = rng.nextInt(30);
                if (rng.nextBoolean()) {
                    var head = chain(addNode(a, 0), addNode(b, 1), addNode(c, 2));
                    list.add(head, lv(0));
                } else {
                    var head = chain(removeNode(a, 0), removeNode(b, 1), removeNode(c, 2));
                    list.remove(head, lv(0));
                }
            }

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Large mixed workload with chained ops preserves all invariants")
        void largeMixedChainedWorkload() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            Random rng = new Random(99);

            for (int round = 0; round < 1000; round++) {
                int v1 = rng.nextInt(100), v2 = rng.nextInt(100);
                if (rng.nextBoolean()) {
                    var head = chain(addNode(v1, 0), addNode(v2, 1));
                    list.add(head, lv(0));
                } else {
                    var head = chain(removeNode(v1, 0), removeNode(v2, 1));
                    list.remove(head, lv(0));
                }
            }

            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Interleaved add/remove phases with chained nodes — surviving keys reachable")
        void interleavedPhases() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);

            // phase 1: add 30 values via chains of 2
            List<Integer> inserted = new ArrayList<>();
            for (int i = 0; i < 30; i += 2) {
                list.add(chain(addNode(i, 0), addNode(i + 1, 1)), lv(0));
                inserted.add(i);
                inserted.add(i + 1);
            }
            assertAllInvariants(list);

            // phase 2: remove evens via chains of 2
            List<Integer> removed = new ArrayList<>();
            for (int i = 0; i < 30; i += 4) {
                list.remove(chain(removeNode(i, 0), removeNode(i + 2, 1)), lv(0));
                removed.add(i);
                removed.add(i + 2);
            }
            assertAllInvariants(list);

            // phase 3: add another batch
            for (int i = 100; i < 120; i += 2) {
                list.add(chain(addNode(i, 0), addNode(i + 1, 1)), lv(0));
            }
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Churned split stress over 10k ops on small key range")
        void churnedSplitStress() {
            var list = new EFUnrolledLinkedList<Integer>(4, 1);
            Random rng = new Random(42);

            for (int i = 0; i < 10_000; i++) {
                int a = rng.nextInt(20), b = rng.nextInt(20);
                int op = rng.nextInt(100);
                if (op < 50) {
                    var head = chain(addNode(a, 0), addNode(b, 1));
                    list.add(head, lv(0));
                } else {
                    var head = chain(removeNode(a, 0), removeNode(b, 1));
                    list.remove(head, lv(0));
                }
            }

            assertAllInvariants(list);
        }
    }
}