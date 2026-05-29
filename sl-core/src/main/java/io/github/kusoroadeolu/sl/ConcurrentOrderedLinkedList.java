package io.github.kusoroadeolu.sl;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

//A lock free ordered linked singly linked list set
// States - marked (linearization point for removal), null key (means the pred node has been logically fully deleted)
// While I'd build a skip list, the ordering of each level of a linked list in a skip list is the meatier problem, so i'll just save myself all the extra work and build the linked list instead
// The next node ideas were borrowed from fraser's linked thesis and the JDK Skip list map

// ADD
// A node can be said to be inserted when it successfully when it successfully performs a CAS on a node whose value is less than it,
// if a node's value is equal to t and the node is not marked as deleted, we return false
// On cas failure, a thread retries, moving its next pointer to the new next variable
// If a next pointer indicates a pred node has been deleted, we retry from the left of the list (which is pretty expensive, but we cant move backwards in this list)

// DELETES
// A node can be said to be deleted if we find an unmarked node equal to T, and we try CAS it to be marked as deleted
// If we don't succeed the cas we retry as another thread could've added a new unmarked node, returning false if we fail to find a new node
// We then try to cas pred.next to the next, if we fail, we continue from outer and try to help other threads going through deletion
// During helping we ensure pred is always on an unmarked node while moving curr to the closest unmarked node from curr,
// if pred is ever marked,  we reset pred to left and curr to left.next
// We exit the loop when pred // curr = right


public class ConcurrentOrderedLinkedList<T extends Comparable<T>> implements ConcurrentListSet<T>{
    private final Node<T> left;
    private final Node<T> right;

    public ConcurrentOrderedLinkedList() {
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
                if (curr.isDummy() && curr != l && curr != r) continue restartFromLeft;
                if (!curr.isMarked() && compare(t, curr, l, r) == 0) return false;
                if (curr.isMarked()) { //If curr is marked try to unlink then restart from left
                    tryUnlink(pred, curr);
                    continue restartFromLeft;
                }
                if (compare(t, curr, l, r) > 0) {
                    pred = curr; curr = pred.loNext();
                } else {
                    //Ensure we immediately set curr = next; backed by volatile write
                    node.spNext(curr);
                    if (pred.casNext(curr, node)) { //Linearization point
                        break restartFromLeft;
                    }
                    if (pred.isMarked()) {
                        continue restartFromLeft;
                        //Move backwards, don't change pred, two things could've happened, pred was deleted (it's next dummy next was marked) or a new node greater than pred was added
                    } else curr = pred.loNext();
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
                if (curr == r) return false;
                if (curr.isDummy() && curr != l) continue restartFromLeft; //If we find a dummy node, restart from left
                if (compare(t, curr, l, r) == 0) {
                    if (curr.casMarked()) {
                        tryUnlink(pred, curr);
                        return true;
                    } //else continue restartFromLeft; //We try and cas if not, restart from left
                }

                if (curr.isMarked() && !curr.isDummy()) {
                    curr = tryUnlink(pred, curr);
                }

                if (curr == r) return false;

                pred = curr; curr = pred.loNext();
            }

            // A(pred) - B(marked) - C(dummy) - R
            // A(pred) - R (curr)

        }
    }


    //Returns the new pred
    Node<T> tryUnlink(Node<T> pred, Node<T> curr) {
        var s = curr;
        Node<T> next;
        Node<T> dummy = new Node<>(null, true); //Always set as marked
        do {
            next = curr.loNext();
            if (next.isDummy()) break;
            dummy.spNext(next); //Backed by cas
        } while (!curr.casNext(next, dummy));

        while (curr.isMarked()) curr = curr.loNext();
        pred.casNext(s, curr);
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
        return toList().size();
    }

    public List<T> toList() {
        List<T> ls = new ArrayList<>();
        var l = left;
        var r = right;
        var curr = l.loNext();
        //If we've reached the end of the list
        while (curr != r) {
            if (curr.t != null && !curr.isMarked()) {
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