package io.github.kusoroadeolu.sl;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;

//A lock free ordered linked singly linked list set
// States - marked (linearization point for removal), null key (means the pred node has been logically fully deleted), next pointer marked as a tombstone (node is going to be unlinked, don't cas to it's next ptr)
// The next node ideas were borrowed from fraser's thesis and the JDK Skip list map

// ADD
// A node can be said to be inserted when it successfully when it successfully performs a CAS on a node whose value is less than it,
// if a node's value is equal to t and the node is not marked as deleted, we return false
// On cas failure, a thread retries either moving its next pointer to the new next variable or checking if its predecessor has been deleted
// If its predecessor has been deleted, we retry from the left of the list (which is pretty expensive, but we cant move backwards in this list)

// During traversal if our curr node is marked as deleted, we mark it as ready to be unlinked and then try to link our pred to the closest alive node

// DELETES
// A node can be said to be deleted if we find an unmarked node equal to T, and we successfully CAS it to be marked
// If we don't succeed the cas we retry as another thread could've added a new unmarked node, returning false if we fail to find a new node
// We then try to cas pred.next to the next, if we fail, we continue from outer and try to help other threads going through deletion
// During helping we ensure pred is always on an unmarked node while moving curr to the closest unmarked node from curr,
// if pred is ever marked, we restart from left
// We exit the loop when curr.t > t

/**
 * @author kusoroadeolu
 * */
public class ConcurrentOrderedList<T extends Comparable<T>> implements ConcurrentListSet<T>{
    private final Node<T> left;
    private final Node<T> right;

    public ConcurrentOrderedList() {
        this.left = new LeftNode<>();
        this.right = new RightNode<>();
        left.next = right;
    }

    // A - B - D
    // A - B - n - C - D
    // B = pred, C = curr
    //

    /*
     * We start iterating the set from the "left" node keeping pred and curr pointers
     * We iterate until pred.t < t && curr.t > t,
     * If curr.key == null, we restart from left
     * If curr.key == ours, we return false //We don't care too much about marked pointers, ideally, if we fail the cas, since we move forward, and we'll recheck if a sentinel node was inserted
     * Otherwise we try cas pred.next -> our node
     * if that fails we set curr = pred.next
     * */
    public boolean add(T t) {
        Objects.requireNonNull(t);
        var l = left;
        var r = right;
        var node = new Node<>(t);
        restartFromLeft: for (; ;) {
            var pred = l;
            var curr = pred.loNext();
            for (;;) {
                if (curr.isDummy()) continue restartFromLeft; //Curr can never be left

                if (curr.isMarked()) { //If curr is marked try to help unlink
                    curr = helpUnlink(pred, curr); //Only shift curr
                    continue;
                }

                int res;
                if ((res = compare(t, curr, l, r)) == 0) return false;

                if (res > 0) pred = curr;
                else {
                    //Ensure we immediately set curr = next; backed by volatile write
                    node.spNext(curr);
                    if (pred.casNext(curr, node)) { //Linearization point
                        return true;
                    }
                    //Move backwards, don't change pred, two things could've happened, pred was deleted (its dummy tombstone was introduced) or a new node greater than pred was added

                    //Here we could rather just move to next rather than checking if pred is marked
                    //If pred is marked, worst case scenario is that pred's tombstone has been inserted, and we have to restart from left on the next loop iteration

                }

                curr = pred.loNext();

            }
        }

    }

    //We iterate until we find t keeping track of pred, curr vars
    // If we reach value where curr = null, we restart from head
    //Otherwise, if curr == v, we try cas, if we fail the cas, we retry as another thread could've added an equal at that time
    public boolean remove(Object o) {
        T t = (T) o;
        Objects.requireNonNull(t);
        var l = left;
        var r = right;

        restartFromLeft: for (; ;) {
            var pred = l;
            var curr = pred.loNext();

            while (true) {
                if (curr.isDummy())
                    continue restartFromLeft; //If we find a dummy node, restart from left

                if (curr.isMarked()) {
                    curr = helpUnlink(pred, curr);
                    continue;
                }

                int res;
                if ((res = compare(t, curr, l, r)) < 0) return false;


                if (res == 0) {
                    if (curr.casMarked()) {
                        helpUnlink(pred, curr);
                        return true;
                    } else return false; //We return false
                }



                pred = curr; curr = pred.loNext();
            }

            // A(pred) - B(marked) - C(dummy) - R
            // A(pred) - R (curr)

        }
    }


    //Returns the next undead node
    Node<T> helpUnlink(Node<T> pred, Node<T> curr) {
        var s = curr;
        Node<T> next;
        Node<T> dummy;


        //If we find any marked node that's not a dummy while traversing, we need to ensure it already has it's dummy tombstone, otherwise we can have lost writs
        while (curr.isMarked()) {
            if (!curr.isDummy()) {
                dummy = new Node<>(null, true);
                do {
                    next = curr.loNext();
                    if (next.isDummy()) break;
                    dummy.spNext(next); //Backed by cas
                } while (!curr.casNext(next, dummy));
            }
           curr = curr.loNext(); //We might not have been the ones to cas dummy to so we still need to use curr to move ahead
        }

        pred.casNext(s, curr); //try to link. failure is alright, another node has unlinked this , all we need is the new unmarked (at this point) curr node
        return curr;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(Object o) {
        T t = (T) o;
        Objects.requireNonNull(t);
        var l = left;
        var r = right;
        var curr = l.loNext();
        int res;
        for (;;) {
            var isDummy = curr.isDummy();
            if (isDummy || (res = compare(t, curr, l, r)) > 0) curr = curr.loNext();
            else return res == 0 && !curr.isMarked();
        }
    }

    public int size() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        int size = 0;
        //If we've reached the end of the list
        while (curr != r) {
            if (!curr.isMarked()) { //Since dummy nodes are marked, a marked flag is alright here
                ++size;
            }
            curr = curr.loNext();
        }

        return size;
    }

    public List<T> toList() {
        List<T> ls = new ArrayList<>();
        var l = left;
        var r = right;
        var curr = l.loNext();
        //If we've reached the end of the list
        while (curr != r) {
            if (!curr.isMarked()) {
                ls.add(curr.t);
            }
            curr = curr.loNext();
        }

        return ls;
    }

    int compare(T t, Node<T> curr, Node<T> l, Node<T> r) {
        if (curr == r) return -1;       // right sentinel, stop
        if (curr == l) return 1; //cant happen
        return t.compareTo(curr.t);
    }

    private static class Node<T extends Comparable<T>> {
        private final T t;
        private volatile boolean marked;
        volatile Node<T> next;

        public Node(T t) {
            this.t = t;
            this.marked = false;
        }

        public Node(T t, boolean marked) {
            this.t = t;
            this.marked = marked;
        }

        boolean isDummy() {
            return t == null;
        }

        public Node<T> loNext(){
            return (Node<T>) NEXT.getAcquire(this);
        }

        public boolean casNext(Node<T> seen, Node<T> ours) {
            return NEXT.compareAndSet(this, seen, ours);
        }

        public boolean isMarked(){
            return (boolean) MARKED.getAcquire(this);
        }

        public void spNext(Node<T> next) {
            NEXT.set(this, next);
        }

        Node(T t,  boolean marked , Node<T> next) {
            this.t = t;
            this.marked = marked;
            NEXT.set(this, next); //Backed by a volatile write
        }

        public boolean casMarked() {
            return MARKED.compareAndSet(this, false, true);
        }

        @Override
        public String toString() {
            return t.toString() + " -> " + next.toString();
        }
    }

    //Left sentinel node
    private static class LeftNode<T extends Comparable<T>> extends Node<T> {


        public LeftNode(T t, boolean b , Node<T> next) {
            super(t, b ,next);
        }

        public LeftNode() {
            this(null, false ,null);
        }

        public void soNext(Node<T> next){
            NEXT.setRelease(this, next);
        }

        @Override
        boolean isDummy() {
            return false;
        }

        @Override
        public String toString() {
            return "LeftNode -> " + next.toString();
        }


    }

    private static class RightNode<T extends Comparable<T>> extends Node<T> {

        public RightNode(T t, boolean b, Node<T> next) {
            super(t, b ,next);
        }

        public RightNode() {
            this(null, false, null);
        }

        @Override
        boolean isDummy() {
            return false;
        }

        @Override
        public String toString() {
            return "RightNode";
        }
    }

    @Override
    public String toString() {
        var l = left;
        var curr = l.loNext();
        var sb = new StringBuilder();
        while (curr != right) {
            sb.append(curr).append(" ");
            curr = curr.loNext();
        }
        return sb.toString();
    }

    private static final VarHandle NEXT;
    private static final VarHandle MARKED;

    static {
        var l = MethodHandles.lookup();
        try {
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
            MARKED = l.findVarHandle(Node.class, "marked", boolean.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}