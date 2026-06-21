package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.github.kusoroadeolu.sl.EliminationNode.NCPU;
import static io.github.kusoroadeolu.sl.UnrolledConcurrentList.Operation;

/*
* An improved variant of the EF Unrolled Concurrent List. The main issue with the previous version was:
* 1. It tried to add more concurrency to the global list (which is already highly concurrent)
* 2. The list could change under the combining thread (the thread that didn't hold the lock to its current node)
* forcing every thread even valid ones to rescan the whole list for their valid nodes
*
* We fix this by instead introducing concurrency per node (through similar elimination). A thread which holds a lock to a node now becomes the combiner for that node
* Each node keeps an arena of active requests to be combined. A combining thread can only combine a value on a node with 3 conditions
* 1. The pred node hasn't changed since the request was registered
* 2. The combining request's operation matches that of the combining thread
* 3. The request's operation(specifically in add) respects the anchor invariant from the parent unrolled concurrent list class
*
* This class maintains the set invariant. I do believe the combining path might incur some overhead and perform worse than its base class
* */
/**
 * @author kusoroadeolu
 * */
public class LocalEFUnrolledConcurrentList<T extends Comparable<T>> implements ConcurrentCollection<T> {

    private final LocalEFNode<T> left;
    private final LocalEFNode<T> right;
    private final int arrayCap;
    private final int minFull;
    private final int maxMerge;
    private final ThreadLocal<LocalArrays<T>> localArrays;
    private static final int MAX_SPINS = 500;
    private static final int SLOT_SPINS = MAX_SPINS / NCPU;
    private static final int ARENA_LEN = NCPU;
    private static final int ARENA_MASK = ARENA_LEN - 1;

    public LocalEFUnrolledConcurrentList() {
        this(64, 16);
    }

    public LocalEFUnrolledConcurrentList(int arrCap, int minFull) {
        this.left = new SentinelEFNode<>();
        this.right = new SentinelEFNode<>();
        left.lock(); //Visibility guarantees for plain reads under the lock
        try {
            left.next = right; //Visibility guarantees for threads traversing the lock
        }finally {
            left.unlock();
        }

        this.minFull = minFull;
        this.arrayCap = arrCap;
        maxMerge = (int) (0.75 * arrCap);
        localArrays = ThreadLocal.withInitial(LocalArrays::new);

    }

    public boolean add(T value) {
        Objects.requireNonNull(value);
        LocalEFNode<T> l = left;
        LocalEFNode<T> r = right;
        int aCap = arrayCap;
        var la = localArrays.get();
        var nodes = la.nodes();
        CombiningRequest<T> ours = null;
        for (;;) {
            findNode(value, l, r, nodes);
            var pred = nodes[0];
            var curr = nodes[1];
            if (curr.contains(value)) return false;

            if (pred.tryLock()) {
                try {
                    if (isNotValid(pred, curr) || curr.containsPlain(value)) return false;

                    if (curr == r || value.compareTo(curr.anchor) < 0) { //Don't scan if this is the right node
                        LocalEFNode<T> n = new LocalEFNode<>(value, aCap);

                        //pred - n - curr
                        n.soArray(0, value);
                        n.increment(1);
                        n.spNext(curr);
                        pred.soNext(n);
                        return true;
                    }

                    Set<T> matchedValues = new HashSet<>();
                    matchedValues.add(value);
                    scanAndMatchAdd(matchedValues, nodes);

                    List<T> matchedList = new ArrayList<>(matchedValues);
                    int matchedSize = matchedValues.size();
                    int size = curr.size();
                    int newSize = size + matchedSize;

                    if (newSize <= aCap) {
                        for (int i = 0, idx = 0; idx < matchedSize; ++i) {
                            if (curr.lpArray(i) == null) {
                                curr.soArray(i, matchedList.get(idx++));
                            }
                        }
                        curr.increment(matchedSize);
                        return true;
                    } else { //Split
                        curr.lock(); //Lock to ensure no one can modify curr.next during the split
                        // So we have a consistent view of curr.next from when we start the split operation
                        try {
                            var succ = curr.lpNext();
                            var arr = curr.array;
                            split(arr, matchedList ,newSize ,nodes);
                            var n1 = nodes[0];
                            var n2 = nodes[1];

                            curr.soMarked();
                            n1.spNext(n2);
                            n2.spNext(succ);
                            pred.soNext(n1); //Linearization point, makes n1 and n2 visible


//                            boolean invariant = (pred == left || n1.anchor.compareTo(pred.anchor) >= 0)  &&
//                                    (n2.anchor.compareTo(n1.anchor) >= 0) &&
//                                    (succ == right || succ.anchor.compareTo(n2.anchor) >= 0);
//                            if (!invariant) {
//                                throw new RuntimeException("Pred: %s, N1: %s, N2: %s, Succ: %s".formatted(pred, n1, n2, succ));
//                            }
                            return true;
                        }finally {
                            curr.unlock();
                        }
                    }

                }finally {
                    pred.unlock();
                }
            } else {
                 //We want to publish then wait
                if (ours == null) ours = new CombiningRequest<>(Operation.ADD,  value);

                if (nodes[1] != r && awaitExchange(ours, nodes, curr.arena, (int) Thread.currentThread().threadId())) {
                    Boolean status = awaitStatus(ours);
                    if (status != null) return status;
                }
            }
        }
    }

    void assertInvariants(LocalEFNode<T> pred, LocalEFNode<T> node, LocalEFNode<T> curr) {
        boolean invariant = (pred == left || node.anchor.compareTo(pred.anchor) >= 0) && (curr == right || node.anchor.compareTo(curr.anchor) < 0);
        if (!invariant) throw new RuntimeException("Error on initial add");
    }


    public boolean remove(Object o) {
        T t = (T) Objects.requireNonNull(o);
        LocalEFNode<T> l = left;
        LocalEFNode<T> r = right;
        int aCap = arrayCap;
        var la = localArrays.get();
        var nodes = la.nodes();
        CombiningRequest<T> ours = null;

        for (;;) {
            findNode(t, l, r, nodes);
            var pred = nodes[0];
            var curr = nodes[1];
            if (!curr.contains(t)) return false;

            if (curr == r || curr.anchor.compareTo(t) > 0) return false;
            if (ours == null) ours = new CombiningRequest<>(Operation.REMOVE, t);

            if (pred.tryLock()) {
                try {
                    if (isNotValid(pred, curr) || !curr.containsPlain(t)) return false;
                    int size = curr.size();
                    Map<T, CombiningRequest<T>> valuesToBeRemoved = new HashMap<>();
                    valuesToBeRemoved.put(t, ours);
                    scanAndMatchRemove(valuesToBeRemoved, nodes);

                    int removeCount = removeValues(valuesToBeRemoved, curr ,size, aCap);
                    curr.decrement(removeCount);
                    int currSize = size - removeCount;

                    if (currSize > minFull) return true;
                    curr.lock();
                    try {
                        var succ = curr.lpNext();
                        if (currSize == 0) {
                            curr.soMarked();
                            pred.soNext(succ);
                            return true;
                        }

                        if (succ == r) return true;

                        succ.lock(); //Ensure we lock succ to prevent other threads from making structural modifications to its array
                        try {
                            int succSize = succ.size();
                            int total = currSize + succSize;
                            int[] emptyIndexes = new int[succSize];
                            findEmptyIndexes(emptyIndexes, aCap ,curr);
                            if (total <= maxMerge) { // Merge to fill the lower indices
                                merge(curr, succ, aCap ,emptyIndexes);
                            } else { //Redistribute so the lower index is not sparse
                                redistribute(curr, succ, succSize, aCap ,total);
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
            } else {
                if (nodes[1] != r && awaitExchange(ours, nodes, curr.arena, (int) Thread.currentThread().threadId())) {
                    Boolean status = awaitStatus(ours);
                    if (status != null) return status;
                }
            }
        }

    }

    Boolean awaitStatus(CombiningRequest<T> ours) {
        for(;;) {
            var s = ours.loStatus();
            if (s == Status.SUCCESS) return true;
            else if (s == Status.FAIL) return false;
            else if (s == Status.RETRY) {
                ours.spStatus(Status.INIT);
                return null;
            }

            Thread.onSpinWait();
        }
    }

    private void scanAndMatchAdd(Set<T> valuesToAdd, LocalEFNode<T>[] nodes) {
        var pred = nodes[0];
        var curr = nodes[1];
        for (int i = 0; i < ARENA_LEN; ++i) {
            CombiningRequest<T> theirs = curr.arena.getAcquire(i);
            if (theirs != free() && theirs.op() == Operation.ADD && curr.arena.compareAndSet(i, theirs, free())) {
                //Then check if their pred our current pred. Second check should always be true, just there to be safe though
                if (pred == theirs.pred && curr == theirs.curr &&
                        curr.anchor.compareTo(theirs.value) < 0) { //If this is false, notify that they cannot return
                    //We still need the extra comparison check,
                    // in the case a thread fails to acquire a lock on a node where it is meant to create a new node to link to pred
                    //but it fails and leaves its value in the elimination array


                    if (valuesToAdd.add(theirs.value)) theirs.soStatus(Status.SUCCESS); //Notify if they can return true or not
                    else theirs.soStatus(Status.FAIL);
                } else theirs.soStatus(Status.RETRY); //list has changed under you, retry

            }
        }
    }

    private void scanAndMatchRemove(Map<T, CombiningRequest<T>> valuesToRemove, LocalEFNode<T>[] nodes) {
        var pred = nodes[0];
        var curr = nodes[1];
        var arena = curr.arena;
        for (int i = 0; i < ARENA_LEN; ++i) {
            CombiningRequest<T> theirs = arena.getAcquire(i);
            if (theirs != free() && theirs.op() == Operation.REMOVE && arena.compareAndSet(i, theirs, free())) {
                //Then check if their pred our current pred. Second check should always be true, just there to be safe though
                if (pred == theirs.pred && curr == theirs.curr) { //If this is false, notify that they cannot return
                    if (valuesToRemove.containsKey(theirs.value)) {
                        theirs.soStatus(Status.FAIL); //Duplicate remove entry
                        continue;
                    }

                    valuesToRemove.put(theirs.value, theirs);

                } else theirs.soStatus(Status.RETRY); //list has changed under you, retry

            }
        }
    }

    boolean awaitExchange(CombiningRequest<T> ours, LocalEFNode<T>[] nodes , AtomicReferenceArray<CombiningRequest<T>> arena, int start) {
        for (int i = 0, totalSpins = 0; totalSpins < MAX_SPINS && i < ARENA_LEN; ++i){
            int slot = (start + i) & ARENA_MASK;
            CombiningRequest<T> theirs = arena.getAcquire(slot);
            if (theirs == free()) {
                ours.pred = nodes[0]; //Backed by volatile write
                ours.curr = nodes[1];
                if (arena.compareAndSet(slot, free(), ours)) {
                    int slotSpins = 0;
                    for (;;) {
                        theirs = arena.getAcquire(slot);
                        if (theirs != ours) return true; //Someone has eliminated us
                        else if (slotSpins >= SLOT_SPINS) {
                            if (arena.getAcquire(slot) == ours && arena.compareAndSet(slot, ours, free())) {
                                totalSpins += slotSpins;
                                break;
                            }
                            else return true; //Someone else has eliminated us
                        }

                        slotSpins++;
                        Thread.onSpinWait();
                    }
                }
            }
//            else if (theirs.op() != ours.op() && theirs.value() == ours.value()
//                    && arena.compareAndSet(slot, theirs, free())) {
//                return true;
//            } Want to maintain strict set invaraiants for now, so we have to uncomment this

        }

        return false; //Failed to match
    }

    static <T extends Comparable<T>>boolean isNotValid(LocalEFNode<T> pred, LocalEFNode<T> curr) {
        return pred.lpMarked() || curr.lpMarked() || pred.lpNext() != curr;
    }

    void split(Object[] array, List<T> matchedValues, int newSize, LocalEFNode<T>[] nodes) {
        Object[] copy = new Object[newSize];
        int idx = 0;
        for (Object o : array) {
            if(o != null) copy[idx++] = o;
        }

        for (T h : matchedValues) {
            copy[idx++] = h;
        }

        Arrays.sort(copy);
        Object[] arr1 = new Object[arrayCap];
        Object[] arr2 = new Object[arrayCap];

        int half = newSize / 2;
        int rem = newSize - half;
        System.arraycopy(copy, 0, arr1, 0, half);
        System.arraycopy(copy, half, arr2, 0, rem);
        var n1 = new LocalEFNode<T>(arr1);
        var n2 = new LocalEFNode<T>(arr2);
        n1.increment(half);
        n2.increment(rem);

        nodes[0] = n1;
        nodes[1] = n2;
    }

    static <T extends Comparable<T>>void merge(LocalEFNode<T> curr, LocalEFNode<T> succ, int arrayCap ,int[] indexes) {
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = succ.lpArray(i);
            if (t != null) {
                var idx = indexes[j++];
                curr.soArray(idx, t);

            }
        }

        succ.soMarked();
        curr.increment(succ.size());
        curr.soNext(succ.lpNext());

    }

    static <T extends Comparable<T>> void findEmptyIndexes(int[] indexes, int arrayCap ,LocalEFNode<T> node) {
        int size = indexes.length;
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = node.lpArray(i);
            if (t == null) {
                if (j == size) return;
                indexes[j++] = i;
            }
        }
    }

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

    public boolean contains(Object o) {
        T t = (T) Objects.requireNonNull(o);
        var nodes = localArrays.get().nodes();
        LocalEFNode<T> curr;
        LocalEFNode<T> l = left, r = right;

        do {
            findNode(t, l, r ,nodes);
            curr = nodes[1];
        } while (curr.loMarked());

        if (curr == r || curr.anchor.compareTo(t) > 0) return false;

        for (int i = arrayCap - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }

    static <T extends Comparable<T>>void findNode(T t, LocalEFNode<T> left, LocalEFNode<T> right ,LocalEFNode<T>[] nodes) {
        LocalEFNode<T> pred = left;
        LocalEFNode<T> curr = left.loNext();
        while (curr != right) {
            LocalEFNode<T> next = curr.loNext();
            if (next == right || t.compareTo(next.anchor) < 0) break;
            pred = curr;
            curr = next;
        }

        nodes[0] = pred; nodes[1] = curr;
    }


    int removeValues(Map<T, CombiningRequest<T>> valuesToRemove, LocalEFNode<T> curr, int size , int arrayCap) {
        int removed = 0;
        for (int i = 0; size > 0 && i < arrayCap; ++i) {
            var value = curr.lpArray(i);
            CombiningRequest<T> current = null;
            if (value != null && (current =  valuesToRemove.remove(value)) != null) {
                //remove from the map as we will rescan to mark unseen values as failed
                curr.soArray(i, null);
                current.soStatus(Status.SUCCESS);
                ++removed;
            }
        }

        for (var entry : valuesToRemove.entrySet()) {
            entry.getValue().soStatus(Status.FAIL);
        }

        return removed;
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
        var map = new LinkedHashMap<T, List<T>>();
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

    private static final Object FREE = null;


    static <T extends Comparable<T>>void redistribute(LocalEFNode<T> curr, LocalEFNode<T> succ, int succSize, int arrayCap ,int total) {
        Object[] copy = Arrays.stream(succ.array.clone())
                .filter(Objects::nonNull)
                .toArray();

        int nodeCount = total / 2;
        int toMove = succSize - nodeCount;
        Arrays.sort(copy);
        var nodeArr = Arrays.copyOf(copy, arrayCap);
        var node = new LocalEFNode<>((T) nodeArr[toMove], nodeArr);
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            if (j == toMove) break;
            if (curr.lpArray(i) == null) {
                curr.soArray(i, (T) nodeArr[j]);
                nodeArr[j++] = null;
            }
        }

        succ.soMarked();
        curr.increment(toMove);
        node.increment(succSize - toMove);
        node.spNext(succ.lpNext());
        curr.soNext(node);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int size() {
        return 0;
    }

    static class CombiningRequest<T extends Comparable<T>> {
        final UnrolledConcurrentList.Operation operation;
        final T value;
        LocalEFNode<T> pred, curr; //Will be backed by ordered write to array index
        volatile Status status;

        public CombiningRequest(UnrolledConcurrentList.Operation operation, T value) {
            this.operation = operation;
            this.value = value;
        }

        public void soStatus(Status s) {
            STATUS.setRelease(this, s);
        }

        public void spStatus(Status s) {
            STATUS.set(this, s);
        }

        public Status loStatus() {
            return (Status) STATUS.getAcquire(this);
        }

        public Operation op() {
            return operation;
        }

        public T value() {
            return value;
        }
    }


    enum Status {
        RETRY,
        SUCCESS, //Return true
        FAIL,  //Return false
        INIT
    }


    static class LocalEFNode<T extends Comparable<T>> {
        final AtomicReferenceArray<CombiningRequest<T>> arena;
        public final T anchor;
        public final Object[] array;
        final Lock lock;
        int size;
        volatile boolean marked;
        volatile LocalEFNode<T> next;

        public LocalEFNode(T anchor, int capacity) {
            this.anchor = anchor;
            this.array = new Object[capacity];
            this.lock = new ReentrantLock();
            arena = fillArena();
        }

        public LocalEFNode(T anchor, int capacity, AtomicReferenceArray<CombiningRequest<T>> arena) {
            this.anchor = anchor;
            this.array = new Object[capacity];
            this.lock = new ReentrantLock();
            this.arena = arena;
        }


        public LocalEFNode(Object[] initialArray) {
            this.anchor = (T) initialArray[0];
            this.array = initialArray;
            this.lock = new ReentrantLock();
            arena = fillArena();
        }

        public LocalEFNode(T anchor, Object[] array) {
            this.anchor = anchor;
            this.array = array;
            this.lock = new ReentrantLock();
            arena = fillArena();
        }

        void lock() {
            lock.lock();
        }

        boolean tryLock() {
            return lock.tryLock();
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

        void soNext(LocalEFNode<T> node) {
            NEXT.setRelease(this, node);
        }

        LocalEFNode<T> lpNext() {
            return (LocalEFNode<T>) NEXT.get(this);
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

        public LocalEFNode<T> loNext() {
            return (LocalEFNode<T>) NEXT.getAcquire(this);
        }

        void increment(int by) {
            SIZE.getAndAddRelease(this, by);
        }

        void decrement(int by) {
            SIZE.getAndAddRelease(this, -by);
        }

        int size() {
            return (int) SIZE.getAcquire(this);
        }

        boolean contains(T value) {
            if (array == null)  return false;
            for (int i = 0; i < array.length; ++i) {
                var v = loArray(i);
                if (v != null && value.compareTo(v) == 0) {
                    return true;
                }
            }

            return false;
        }

        boolean containsPlain(T value) {
            for (int i = 0; i < array.length; ++i) {
                var v = lpArray(i);
                if (v != null && value.compareTo(v) == 0) {
                    return true;
                }
            }

            return false;
        }


        public void spNext(LocalEFNode<T> node) {
            NEXT.set(this, node);
        }

        static <T extends Comparable<T>> AtomicReferenceArray<CombiningRequest<T>> fillArena() {
            AtomicReferenceArray<CombiningRequest<T>> arena = new AtomicReferenceArray<>(ARENA_LEN);
            for (int i = 0; i < arena.length(); ++i) {
                arena.setRelease(i, free());
            }
            return arena;
        }

        @Override
        public String toString() {
            return
                    "anchor=" + anchor +
                    ", array=" + Arrays.toString(array);
        }
    }

    static class SentinelEFNode<T extends Comparable<T>> extends LocalEFNode<T> {
        public SentinelEFNode() {
            super(null, 0, fillArena());
        }
    }

    public static <T extends Comparable<T>> CombiningRequest<T> free() {
        return  (CombiningRequest<T>) FREE;
    }

    static class LocalArrays<T extends Comparable<T>> {
        //Used for storing pred and curr arrays;
        final LocalEFNode<T>[] nodes; //0 - pred, 1 - curr
        //Used for storing indices to prevent extra traversals to calculate size;
        final int[] indices; // 0 - index, 1 - size

        public LocalArrays() {
            this.nodes = new LocalEFNode[2];
            this.indices = new int[2];
        }

        public LocalEFNode<T>[] nodes() {
            return nodes;
        }

        public int[] indices() {
            return indices;
        }
    }

    private static final VarHandle MARKED;
    private static final VarHandle NEXT;
    private static final VarHandle ARRAY;
    private static final VarHandle STATUS;
    private static final VarHandle SIZE;

    static {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
            STATUS = l.findVarHandle(CombiningRequest.class, "status", Status.class);
            SIZE = l.findVarHandle(LocalEFNode.class, "size", int.class);
            MARKED = l.findVarHandle(LocalEFNode.class, "marked", boolean.class);
            NEXT = l.findVarHandle(LocalEFNode.class, "next", LocalEFNode.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
