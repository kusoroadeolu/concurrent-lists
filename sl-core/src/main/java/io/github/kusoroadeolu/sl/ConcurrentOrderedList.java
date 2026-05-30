package io.github.kusoroadeolu.sl;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

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
    private final LongAdder size;

    public ConcurrentOrderedList() {
        this.left = new LeftNode<>();
        this.right = new RightNode<>();
        left.next = right;
        size = new LongAdder();
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
                if (curr.isDummy() && curr != l && curr != r) continue restartFromLeft;
                if (!curr.isMarked() && compare(t, curr, l, r) == 0) return false;
                if (curr.isMarked()) { //If curr is marked try to unlink
                    curr = helpUnlink(pred, curr); //Only shift curr
                    continue;
                }
                if (compare(t, curr, l, r) > 0) {
                    pred = curr; curr = pred.loNext();
                } else {
                    //Ensure we immediately set curr = next; backed by volatile write
                    node.spNext(curr);
                    if (pred.casNext(curr, node)) { //Linearization point
                        size.increment();
                        break restartFromLeft;
                    }
                    //Move backwards, don't change pred, two things could've happened, pred was deleted (its dummy tombstone was introduced) or a new node greater than pred was added

                    if (pred.isMarked()) continue restartFromLeft;
                    else curr = pred.loNext();
                }

            }
        }


        return true;
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
                if (!curr.isMarked() && compare(t, curr, l, r) < 0) return false;

                if (curr.isDummy() && curr != l) continue restartFromLeft; //If we find a dummy node, restart from left

                if (curr.isMarked() && !curr.isDummy()) {
                    curr = helpUnlink(pred, curr);
                    continue;
                }

                if (compare(t, curr, l, r) == 0) {
                    if (curr.casMarked()) {
                        size.decrement();
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

        pred.casNext(s, curr); //try to link. failure is alright, all we need is the new unmarked(at this point) curr node
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
        for (;;) {
            if (curr == r) return false; //If we've reached the end of the list
            if (curr.isDummy()) curr = curr.loNext();
            else if (!curr.isMarked() && compare(t, curr, l, r) == 0) return true;

            if (compare(t, curr, l, r) > 0) curr = curr.loNext();
            else return false;

        }
    }

    public int size() {
        return size.intValue();
    }

    public List<T> toList() {
        List<T> ls = new ArrayList<>();
        var l = left;
        var r = right;
        var curr = l.loNext();
        //If we've reached the end of the list
        while (curr != r) {
            if (!curr.isDummy() && !curr.isMarked()) {
                ls.add(curr.t);
            }
            curr = curr.loNext();
        }

        return ls;
    }

    int compare(T t, Node<T> curr, Node<T> l, Node<T> r) {
        if (curr == r) return -1;       // right sentinel, stop
        if (curr == l) return 1;
        return t.compareTo(curr.t);
    }

    boolean isLeftOrRight(Node<T> node, Node<T> l, Node<T> r) {
        return isNode(node, l) || isNode(node, r);
    }

    boolean isNode(Node<T> node, Node<T> n) {
        return node == n;
    }



    private static class Node<T extends Comparable<T>> {
        private final T t;
        private volatile boolean marked;
        private volatile Node<T> next;

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
            return t.toString();
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
        public String toString() {
            return "LeftNode";
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