package io.github.kusoroadeolu.sl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// Based on the paper https://www.researchgate.net/publication/220440170_A_Lazy_Concurrent_List-Based_Set_Algorithm
/* The code is quite easy to reason about
* The main idea of the paper is we lazily acquire locks, traversing the list on modification operations only acquiring locks for nodes when we want to modify them
* Read operations however are wait free, as threads need not acquire locks during traversal and visibility is ensured by happens before guarantee of locks
*
* A simple optimization made in this impl is validation the state of a node before acquiring the node locks
* * */
public class LazyOptimisticList<T extends Comparable<T>> implements ConcurrentListSet<T>{

    private final Node<T> left;
    private final Node<T> right;
    private final LongAdder size;

    public LazyOptimisticList() {
        this.left = new Node<>(null);
        this.right = new Node<>(null);
        this.size = new LongAdder();
        left.lock();
        try {
            left.next = right; //Need to lock here to guarantee visibility
        }finally {
            left.unlock();
        }
    }

    @Override
    public boolean add(T t) {
        var l = left;
        var r = right;
        var s = size;

        while (true) {
            Node<T> pred = l;
            Node<T> curr = l.next;
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.next;
            }

            if (res == 0 && !curr.loMarked()) return false;

            //A - B - C
            if (pred.loMarked() || curr.loMarked()) continue;

            try {
                pred.lock();
                if (pred.lpMarked() || pred.next != curr) continue;
                try {
                    curr.lock();
                    if (curr.lpMarked()) continue;
                    Node<T> node = new Node<>(t);
                    node.next = curr;
                    pred.next = node; //Order here matters, we need to ensure node#next is set before we link pred to node
                    s.increment();
                    return true;
                }finally {
                    curr.unlock();
                }
            } finally {
                pred.unlock();
            }
        }

    }

    int compare(T t, Node<T> l, Node<T> r, Node<T> curr) {
        if (curr == r) return -1;
        if (curr == l) return 1;
        return t.compareTo(curr.t);
    }

    @Override
    public boolean remove(Object o) {
        T t = (T) o;
        var l = left;
        var r = right;
        var s = size;

        while (true) {
            Node<T> pred = l;
            Node<T> curr = l.next;
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.next;
            }

            if (res != 0) return false;

            if (curr.loMarked()) return false;
            if (pred.loMarked()) continue;

            try {
                pred.lock();
                if (pred.lpMarked() || pred.next != curr) continue; //Validate before trying to acquire curr lock
                try {
                    curr.lock();
                    if (curr.lpMarked()) return false;
                    curr.soMarked();
                    pred.next = curr.next; //Order here matters, we need to ensure node#next is set before we link pred to node
                    s.decrement();
                    return true;
                }finally {
                    curr.unlock();
                }
            } finally {
                pred.unlock();
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    public List<T> toList() {
        var l = left;
        var r = right;
        var curr = l.next;
        var ls = new ArrayList<T>();
        while (curr != r && !curr.loMarked()) {
            ls.add(curr.t);
            curr = curr.next;
        }

        return ls;
    }

    @Override
    public boolean contains(Object o) {
        T t = (T) o;
        var l = left;
        var r = right;
        Node<T> pred = l;
        Node<T> curr = pred.next;
        int res;
        while ((res = compare(t, l, r, curr)) > 0) {
            pred = curr; curr = pred.next;
        }

        return res == 0;
    }

    @Override
    public int size() {
        return size.intValue();
    }

    static class Node<T> {
        final T t;
        final AtomicBoolean marked;
        final Lock lock;
        Node<T> next;

        public Node(T t) {
            this.t = t;
            this.marked = new AtomicBoolean(false);
            this.lock = new ReentrantLock();
        }

        //Protected by the lock
        void soMarked() {
             marked.setRelease(true);
        }

        boolean loMarked() {
            return marked.getAcquire();
        }


        boolean lpMarked() {
            return marked.getPlain();
        }

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();;
        }
    }
}
