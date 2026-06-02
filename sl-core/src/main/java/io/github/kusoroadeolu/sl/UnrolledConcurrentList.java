package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
* Based on the thesis https://utd-ir.tdl.org/server/api/core/bitstreams/ca02e64a-84c8-45c9-9cb2-721ead65df84/content
* This structure is a linked list of unrolled nodes. Basically nodes which rather than storing one piece of data, they instead store arrays of data
* This improves cache locality as all needed data is immediately loaded to memory in the array and
* reduces pointer chasing as the probability of the node we land on containing the data is increased
*
* Invariants:
* The anchor of a node successor must be greater than its predecessor
* All keys in a node are greater than or equal to that node’s anchor key
*
*
* Note that there is no guarantee in which the anchor of a node will always exist in the node
* There is also no guarantee that the array in a node is will always be in sorted order
*
* Communication across node to indicate a node is deleted is done mainly through a marked flag
* Visibility during splits and merges is done using a set release write to a node's next pointer,
* as a thread traversing through a thread will always have to read a node's next flag to get to its dest
*
* Also, a node's next flag is always protected by its pred node
*
* To allow threads holding the lock less, while the compiler can optimize loops,
* on removes we try to pack the lower most index regions of the arrays with values,
*  though at the cost of reads starting from the higher most index values
* To prevent modifications on nodes who we're splitting or redistributing their arrays,
* we first copy their arrays before any operation, while this might seem expensive, we are just paying for the cost of a new array object,
* pre-existing objects are not copied
* */

@SuppressWarnings("unchecked")
public class UnrolledConcurrentList<T extends Comparable<T>> implements ConcurrentListSet<T>{
    private final Node<T> left;
    private final Node<T> right;
    private final ThreadLocal<Node<T>[]> tlNodes;
    private final int arrayCap;
    private final int minFull;

    public UnrolledConcurrentList() {
        this(8, 2);
    }

    public UnrolledConcurrentList(int arrCap, int minFull) {
        this.left = new SentinelNode<>();
        this.right = new SentinelNode<>();
        tlNodes = ThreadLocal.withInitial(() -> new Node[2]);
        this.minFull = minFull;
        this.arrayCap = arrCap;
        left.lock();
        try {
            left.soNext(right);
        }finally {
            left.unlock();
        }
    }

    public boolean add(T t) {
        Node<T> r = right;
        var nodes = tlNodes.get();
        while (true) {
            if (isPresent(t, nodes)) return false;
            var pred = nodes[0];
            var curr = nodes[1];
            pred.lock();
            try {
                if (!validate(pred, curr)) continue;
                if (getIndexPlain(t, curr) != -1) return false;
                if (curr == r  || t.compareTo(curr.anchor) < 0) {
                    Node<T> n = new Node<>(t, arrayCap);
                    n.spNext(curr);
                    pred.soNext(n);
                    return true;
                }

                int s = curr.size;
                if (s < arrayCap) {
                    int idx = findAvailableIndex(curr.array);
                    addToNode(t, idx, curr);
                    return true;
                } else { //Split
                    curr.lock(); //Lock to ensure no one can modify curr.next during the split
                    // So we have a consistent view of curr.next from when we start the split operation
                    var succ = curr.lpNext();
                    var arr = curr.array;
                    try {
                        split(arr, t ,nodes);
                        var n1 = nodes[0];
                        var n2 = nodes[1];

                        n1.spNext(n2);
                        n2.spNext(succ);
                        pred.soNext(n1); //Visibility

                        curr.loMarked();
                        return true;
                    }finally {
                        curr.unlock();
                    }
                }

            }finally {
                pred.unlock();
            }
        }
    }

    public List<T> anchorList() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        List<T> ls = new ArrayList<>();
        while (curr != r) {
            ls.add(curr.anchor);
            curr = curr.loNext();
        }

        return ls;
    }

    public Map<T, List<T>> nodeMap() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        var map = new HashMap<T, List<T>>();
        while (curr != r) {
            List<T> ls = Arrays
                    .stream(curr.array)
                    .map(a -> (T) a)
                    .filter(Objects::nonNull)
                    .toList();

            map.put(curr.anchor, ls);
            curr = curr.loNext();
        }

        return map;
    }

    public boolean remove(Object o) {
        T t = (T) o;
        Node<T> r = right;
        var nodes = tlNodes.get();
        while (true) {
            if (!isPresent(t, nodes)) return false;
            var pred = nodes[0];
            var curr = nodes[1];
            pred.lock();
            try {
                if (!validate(pred, curr)) continue;
                int index;
                if ((index = getIndexPlain(t, curr)) == -1) return false;
                nullifyIndex(index, curr);
                int size = --curr.size;
                if (size > minFull) return true;
                curr.lock();
                try {
                    var succ = curr.lpNext();
                    if (size == 0) {
                        pred.soNext(succ);
                        curr.soMarked();
                        return true;
                    }

                    if (succ == r) return true;

                    succ.lock(); //Ensure we lock succ to prevent other threads from making structural modifications to its array
                    try {
                        int total = curr.size + succ.size;
                        int[] emptyIndexes = new int[succ.size];
                        findEmptyIndexes(emptyIndexes, curr);
                        if (total <= arrayCap) {
                            merge(curr, succ, emptyIndexes);
                        } else { //Distribute the
                            redistribute(curr, succ, total);
                        }

                        return true;
                    }finally {
                        succ.unlock();
                    }

                }finally {
                    curr.unlock();
                }


            }finally {
                pred.unlock();
            }

        }
    }

    void redistribute(Node<T> curr, Node<T> succ, int total) {

        Object[] copy = Arrays.stream(succ.array.clone())
                .filter(Objects::nonNull)
                .toArray();

        int nodeCount = total / 2;
        int toMove = succ.size - nodeCount;
        Arrays.sort(copy);

        var nodeArr = Arrays.copyOf(copy, arrayCap);
        var node = new Node<>((T) nodeArr[toMove], nodeArr, nodeCount);
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            if (curr.lpArray(i) == null) {
                curr.soArray(i, (T) nodeArr[j]);
                nodeArr[j++] = null;
                if (j == toMove) break;
            }
        }


        curr.size += toMove;
        node.spNext(succ.lpNext());
        curr.soNext(node); //Write to node will make all the writes in the node array visible
        succ.soMarked();

    }

    void merge(Node<T> curr, Node<T> succ, int[] indexes) {
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = succ.lpArray(i);
            if (t != null) {
               var idx = indexes[j++];
               curr.soArray(idx, t);
            }
        }

        curr.size += succ.size;
        curr.soNext(succ.lpNext());
        succ.soMarked();
    }


    void findEmptyIndexes(int[] indexes, Node<T> node) {
        var arr = node.array;
        int size = indexes.length;
        for (int i = 0, j = 0; i < arr.length; ++i) {
            T t = node.lpArray(i);
            if (t == null) {
                indexes[j++] = i;
                if (j == size) return;
            }
        }
    }

    void nullifyIndex(int index, Node<T> curr) {
        var arr = curr.array;
        int nnIndex = findNonNullIndex(arr, index);
        if (nnIndex != -1 && index < nnIndex) { //Array is logically empty
            //We don't want to swap a value at a near index to a farther index.
            // For example if the index we're removing is 6 and the next non-null index is 2, we don't want to swap it
            //Here the set invariant is briefly violated
            curr.spArray(index, curr.lpArray(nnIndex)); //Move the value at nnIndex forward first before nulling out
            curr.soArray(nnIndex, null);
        } else {
            curr.soArray(index, null);
        }
    }

    void addToNode(T t, int s, Node<T> node) {
        node.soArray(s, t);
        ++node.size;
    }

    int findAvailableIndex(Object[] arr) {
        for (int i = 0; i < arrayCap; ++i) {
            if (arr[i] == null) return i;
        }

        return -1;
    }

    int findNonNullIndex(Object[] arr, int index) {
        for (int i = 0; i < arrayCap; ++i) {
            if (i != index && arr[i] != null) return i;
        }

        return -1;
    }

    void split(Object[] array, T t ,Node<T>[] nodes) {
        Object[] copy = Arrays.copyOf(array, arrayCap + 1); //Copy to prevent modifying the initial array
        copy[copy.length - 1] = t;
        Arrays.sort(copy);
        Object[] arr1 = new Object[arrayCap];
        Object[] arr2 = new Object[arrayCap];

        int half = copy.length / 2;
        int rem = copy.length - half;
        System.arraycopy(copy, 0, arr1, 0, half);
        System.arraycopy(copy, half, arr2, 0, rem);
        nodes[0] = new Node<>(half, arr1);
        nodes[1] = new Node<>(rem, arr2);
    }

    public boolean contains(Object o) {
        T t = (T) o;
        var nodes = tlNodes.get();
        return isPresent(t, nodes);
    }


    boolean validate(Node<T> pred, Node<T> curr) {
        return !pred.lpMarked() && !curr.lpMarked() && pred.lpNext() == curr;
    }

    boolean isPresent(T t, Node<T>[] nodes){
        int found = findNode(t, nodes);
        if (found == -1) return false;
        var curr = nodes[1];
        for (int i = arrayCap - 1; i >= 0; --i) { //We need to traverse from r to l due to how deletes are structured
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return !curr.loMarked();

        }

        return false;
    }


    int getIndexPlain(T t, Node<T> curr){
        if (curr == right) return -1;
        for (int i = 0; i < arrayCap; ++i) {
            T v;
            if ((v = curr.lpArray(i)) != null && t.compareTo(v) == 0) return i;
        }
        return -1;
    }

    int findNode(T t, Node<T>[] nodes) {
        Node<T> l = left, r = right;
        Node<T> pred = l;
        Node<T> curr = l.loNext();

        while (curr != r) {
            Node<T> next = curr.loNext();
            if (next == r || t.compareTo(next.anchor) < 0) break;
            pred = curr;
            curr = next;
        }

        nodes[0] = pred; nodes[1] = curr;
        if (curr == r) return -1;
        return 1;
    }

    static class Node<T extends Comparable<T>> {
        final T anchor;
        final Object[] array;
        final Lock lock;
        int size;
        volatile boolean marked;
        volatile Node<T> next;

        public Node(T anchor, int capacity) {
            this.anchor = anchor;
            this.array = new Object[capacity];
            ++size;
            ARRAY.setRelease(array, 0, anchor);
            this.lock = new ReentrantLock();
        }

        public Node(int size ,Object[] initialArray) {
            this.anchor = (T) initialArray[0];
            this.array = initialArray;
            this.size = size;
            this.lock = new ReentrantLock();

        }

        public Node(T anchor, Object[] array, int size) {
            this.anchor = anchor;
            this.size = size;
            this.array = array;
            this.lock = new ReentrantLock();
        }

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();
        }

        void spArray(int idx, T t) {
            ARRAY.set(array, idx, t);
        }

        void soArray(int idx, T t) {
            ARRAY.setRelease(array, idx, t);
        }

        T loArray(int idx) {
            return (T) ARRAY.getAcquire(array, idx);
        }

        T lpArray(int idx) {
            return (T) ARRAY.get(array, idx);
        }

        void soNext(Node<T> node) {
            NEXT.setRelease(this, node);
        }

        Node<T> lpNext() {
            return (Node<T>) NEXT.get(this);
        }

        boolean loMarked(){
            return (boolean) MARKED.getAcquire(this);
        }

        boolean lpMarked(){
            return (boolean) MARKED.get(this);
        }

        void soMarked(){
            MARKED.setRelease(this, true);
        }

        public Node<T> loNext() {
            return (Node<T>) NEXT.getAcquire(this);
        }

        public void spNext(Node<T> node) {
            NEXT.set(this, node);
        }

        @Override
        public String toString() {
            return anchor + " : " + Arrays.toString(array) + " -> " + next;
        }

        public String asString() {
            return anchor + " : " + Arrays.toString(array);
        }
    }

    static class SentinelNode<T extends Comparable<T>> extends Node<T>{

        public SentinelNode() {
            super(null, null, 0);
        }

        @Override
        public String toString() {
            return "Sentinel -> " + next;
        }
    }

    private static final VarHandle MARKED;
    private static final VarHandle NEXT;
    private static final VarHandle ARRAY;

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public List<T> toList() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        List<T> ls = new ArrayList<>();
        while (curr != r) {
            var arr = curr.array.clone();
            for (int i = 0; i < arrayCap; ++i) {
                T t = (T) arr[i];
                if (t != null) ls.add(t);
            }

            curr = curr.loNext();
        }

        return ls;
    }


    static {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
            MARKED = l.findVarHandle(Node.class, "marked", boolean.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
