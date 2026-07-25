# concurrent-lists

A collection of concurrent, ordered set implementations in Java, written to explore different concurrency control strategies (lock free, lazy synchronization, coarse locking, fine grained locking, elimination, flat combining, path copying, and skip lists).

This is primarily an experimentation/benchmarking project. The implementations are compared against each other, and against `ConcurrentSkipListSet`, to see how different design tradeoffs actually perform under contention.

## Modules

- **sl-core** the actual implementations, all under `io.github.kusoroadeolu.sl`
- **sl-jmh** JMH benchmarks comparing throughput and latency across implementations
- **sl-stress** jcstress tests that check correctness invariants under concurrent access

## Implementations

All implementations follow the `ConcurrentCollection<T>` interface (a subset of `Collection`).

### Linked list based sets

- `ConcurrentOrderedList` a lock free ordered singly linked list, using tombstone nodes for unlinking (based on ideas from Fraser's thesis and the JDK's `ConcurrentSkipListMap`)
- `LazySyncList` lazy synchronization with per node locks, based on Heller et al.'s "Lazy Concurrent List Based Set Algorithm"
- `LazyCoarseSyncList` same lazy synchronization idea, but with one global lock instead of per node locks
- `LockedOrderedLL` a plain single lock ordered linked list, mostly there as a baseline
- `PCLinkedList` a path copying linked list. Reads are wait free since they never touch locks or CAS, writes copy the path from head and swap the whole head with a CAS

### Unrolled linked lists

These store arrays of values per node instead of one value per node, for better cache locality and less pointer chasing.

- `UnrolledConcurrentList` the base unrolled list, uses per node locks with splitting/merging/redistribution of nodes as they fill up or empty out
- `EliminationUnrolledConcurrentList` adds an elimination array per node so opposing add/remove operations on the same node can pair up and return without touching the lock
- `LocalEFUnrolledConcurrentList` combines elimination with flat combining per node, the thread that holds a node's lock becomes the combiner for other threads' pending requests on that node
- `EFUnrolledConcurrentList` / `EFUnrolledLinkedList` an experimental elimination + flat combining variant applied at the whole list level rather than per node. Noted in the code as not performing that well

### Skip lists

- `OptimisticSkipList` a lock based skip list using optimistic traversal with fullyLinked/marked flags for atomic visibility, based on "A Simple Optimistic Skip-list Algorithm"
- `FineGrainedSkipList` referenced in the benchmarks as a comparison point, not included in this repo

## Running benchmarks

Benchmarks live in `sl-jmh` and are written with JMH.

```bash
cd sl-jmh
mvn clean package
java -jar target/benchmark.jar
```

Main benchmark classes:
- `ListReadHeavyBench` mostly reads, few writes
- `ListWriteHeavyBench` mixed adds/removes/reads, more write heavy
- `ZipfianBenchmark` skewed key access pattern to see how elimination performs when threads collide on the same keys
- `SkipListBench` compares skip list implementations against the JDK's

## Running stress tests

Stress tests live in `sl-stress` and use jcstress to check invariants (sortedness, no lost updates, etc.) hold under actual concurrent execution.

```bash
cd sl-stress
mvn clean package
java -jar target/jcstress.jar
```

## Notes

Most classes have fairly detailed comments at the top explaining the design and tradeoffs of that particular approach, worth reading if you want the reasoning behind a specific implementation.

## License
MIT