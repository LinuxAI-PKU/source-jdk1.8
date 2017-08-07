import sun.misc.Unsafe;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by zhaoshengqi on 2017/7/24.
 */
public class MyJUCTemplate {


    public class AtomicInteger extends Number implements java.io.Serializable {
        private static final long serialVersionUID = 6214790243416807050L;

        // setup to use Unsafe.compareAndSwapInt for updates
        private static final Unsafe unsafe = Unsafe.getUnsafe();
        //表示变量值在内存中的偏移地址
        private static final long valueOffset;

        static {
            try {
                valueOffset = unsafe.objectFieldOffset
                        (java.util.concurrent.atomic.AtomicInteger.class.getDeclaredField("value"));
            } catch (Exception ex) {
                throw new Error(ex);
            }
        }

        private volatile int value;

        /**
         * Creates a new AtomicInteger with the given initial value.
         *
         * @param initialValue the initial value
         */
        public AtomicInteger(int initialValue) {
            value = initialValue;
        }

        /**
         * Creates a new AtomicInteger with initial value {@code 0}.
         */
        public AtomicInteger() {
        }

        public final int incrementAndGet() {
            return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
        }

    }


    static final class Node {
        /**
         * Marker to indicate a node is waiting in shared mode
         */
        static final Node SHARED = new Node();
        /**
         * Marker to indicate a node is waiting in exclusive mode
         */
        static final Node EXCLUSIVE = null;
        /**
         * 取消
         */
        static final int CANCELLED = 1;
        /**
         * 等待触发
         */
        static final int SIGNAL = -1;
        /**
         * 等待条件
         */
        static final int CONDITION = -2;
        /**
         * 状态需要向后传播
         */
        static final int PROPAGATE = -3;

        volatile int waitStatus;
        volatile Node prev;
        volatile Node next;
        volatile Thread thread;
        Node nextWaiter;

        /**
         * 该线程是否正在独占资源
         */
        protected boolean isHeldExclusively() {
            throw new UnsupportedOperationException();
        }

        /**
         * 独占锁.尝试获取资源,成功返回true
         */
        protected boolean tryAcquire(int arg) {
            throw new UnsupportedOperationException();
        }

        /**
         * 独占锁.尝试释放资源,成功返回true
         */
        protected boolean tryRelease(int arg) {
            throw new UnsupportedOperationException();
        }

        /**
         * 共享锁.尝试获取资源,负数表示失败；0表示成功，但没有剩余可用资源；正数表示成功，且有剩余资源。
         */
        protected int tryAcquireShared(int arg) {
            throw new UnsupportedOperationException();
        }

        /**
         * 共享锁.成功返回true
         */
        protected boolean tryReleaseShared(int arg) {
            throw new UnsupportedOperationException();
        }


        public final void acquire(int arg) {
            if (!tryAcquire(arg) &&
                    acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
                selfInterrupt();
        }

        private Node addWaiter(Node mode) {
            //以给定模式构造节点.mode有两种：EXCLUSIVE（独占）和SHARED（共享）
            Node node = new Node(Thread.currentThread(), mode);
            //尝试快速方式直接放到队尾。
            Node pred = tail;
            if (pred != null) {
                node.prev = pred;
                if (compareAndSetTail(pred, node)) {
                    pred.next = node;
                    return node;
                }
            }
            //上一步失败则通过enq入队。
            enq(node);
            return node;
        }

        private Node enq(final Node node) {
            //CAS"自旋"，直到成功加入队尾
            for (; ; ) {
                Node t = tail;
                if (t == null) { // 队列为空，创建一个空的标志结点作为head结点，并将tail也指向它。
                    if (compareAndSetHead(new Node()))
                        tail = head;
                } else {//正常流程，放入队尾
                    node.prev = t;
                    if (compareAndSetTail(t, node)) {
                        t.next = node;
                        return t;
                    }
                }
            }
        }

        final boolean acquireQueued(final Node node, int arg) {
            boolean failed = true;//标记是否成功拿到资源
            try {
                boolean interrupted = false;//标记等待过程中是否被中断过
                //自旋
                for (; ; ) {
                    final Node p = node.predecessor();//拿到前驱
                    //如果前驱是head，即该结点已成老二，那么便有资格去尝试获取资源（可能是老大释放完资源唤醒自己的，当然也可能被interrupt了）。
                    if (p == head && tryAcquire(arg)) {
                        setHead(node);//拿到资源后，将head指向该结点。所以head所指的标杆结点，就是当前获取到资源的那个结点或null。
                        p.next = null; // help GC
                        failed = false;
                        return interrupted;//返回等待过程中是否被中断过
                    }
                    //如果自己可以休息了，就进入waiting状态，直到被unpark()
                    if (shouldParkAfterFailedAcquire(p, node) &&
                            parkAndCheckInterrupt())
                        interrupted = true;//如果等待过程中被中断过，就将interrupted标记为true
                }
            } finally {
                if (failed)
                    cancelAcquire(node);
            }
        }

        private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
            int ws = pred.waitStatus;//拿到前驱的状态
            if (ws == Node.SIGNAL)
                //如果已经告诉前驱拿完号后通知自己一下，那就可以安心休息了
                return true;
            if (ws > 0) {
                /**
                 * 如果前驱放弃了，那就一直往前找，直到找到最近一个正常等待的状态，并排在它的后边。
                 * 注意：那些放弃的结点，由于被自己“加塞”到它们前边，它们相当于形成一个无引用链，稍后就会被GC回收
                 */
                do {
                    node.prev = pred = pred.prev;
                } while (pred.waitStatus > 0);
                pred.next = node;
            } else {
                //如果前驱正常，那就把前驱的状态设置成SIGNAL，告诉它拿完号后通知自己一下。有可能失败，人家说不定刚刚释放完呢
                compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
            }
            return false;
        }
        /**如果线程找好安全休息点后，那就可以安心去休息了。此方法就是让线程去休息，真正进入等待状态。*/
        private final boolean parkAndCheckInterrupt() {
            LockSupport.park(this);//使线程进入waiting状态
            return Thread.interrupted();//如果被唤醒，查看自己是不是被中断的。
        }

        public final boolean release(int arg) {
            if (tryRelease(arg)) {
                Node h = head;//找到头节点
                if (h != null && h.waitStatus != 0)
                    unparkSuccessor(h);//唤醒等待队列的下一个线程
                return true;
            }
            return false;
        }

        private void unparkSuccessor(Node node) {
            //这里，node一般为当前线程所在的结点。
            int ws = node.waitStatus;
            if (ws < 0)//置零当前线程所在的结点状态，允许失败。
                compareAndSetWaitStatus(node, ws, 0);

            Node s = node.next;//找到下一个需要唤醒的结点
            if (s == null || s.waitStatus > 0) {
                s = null;
                for (Node t = tail; t != null && t != node; t = t.prev)
                    if (t.waitStatus <= 0)
                        s = t;
            }
            if (s != null)
                LockSupport.unpark(s.thread);
        }


        public final void acquireShared(int arg) {
            if (tryAcquireShared(arg) < 0)
                doAcquireShared(arg);
        }

        private void doAcquireShared(int arg) {
            final Node node = addWaiter(Node.SHARED);//加入队列尾部
            boolean failed = true;//是否成功标记
            try {
                boolean interrupted = false;//等待过程中是否被中断过标记
                for (;;) {
                    final Node p = node.predecessor();//前节点
                    if (p == head) {//如果前节点为head,此时唤醒node
                        int r = tryAcquireShared(arg);//尝试获取资源
                        if (r >= 0) {
                            setHeadAndPropagate(node, r);//将head指向自己,如果还有剩余资源可以再唤醒之后的线程
                            p.next = null; // help GC
                            if (interrupted)//如果等待过程中被中断,此时将中断标记补上
                                selfInterrupt();
                            failed = false;
                            return;
                        }
                    }
                    //判断状态,寻找安全点,进入waiting状态,等待被unpark()或interrupt()
                    if (shouldParkAfterFailedAcquire(p, node) &&
                            parkAndCheckInterrupt())
                        interrupted = true;
                }
            } finally {
                if (failed)
                    cancelAcquire(node);
            }
        }

        private void setHeadAndPropagate(Node node, int propagate) {
            Node h = head; // Record old head for check below
            setHead(node);//head指向自己
            //如果还有剩余资源,继续唤醒下一个线程
            if (propagate > 0 || h == null || h.waitStatus < 0 ||
                    (h = head) == null || h.waitStatus < 0) {
                Node s = node.next;
                if (s == null || s.isShared())
                    doReleaseShared();
            }
        }

        private void doReleaseShared() {
            //自旋
            for (;;) {
                Node h = head;
                if (h != null && h != tail) {
                    int ws = h.waitStatus;
                    if (ws == Node.SIGNAL) {
                        if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                            continue;            // loop to recheck cases
                        unparkSuccessor(h);//唤醒后续线程
                    }
                    else if (ws == 0 &&
                            !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                        continue;                // loop on failed CAS
                }
                if (h == head)                   // loop if head changed
                    break;
            }
        }

        public final boolean tryAcquireNanos(int arg, long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            return tryAcquire(arg) ||
                    doAcquireNanos(arg, nanosTimeout);
        }

        public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            return tryAcquireShared(arg) >= 0 ||
                    doAcquireSharedNanos(arg, nanosTimeout);
        }

        //非公平锁获取
        final void lock() {
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }
        //公平锁获取
        final void lock() {
            acquire(1);
        }
        //公平锁
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (!hasQueuedPredecessors() &&
                        compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }
        //非公平锁
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }

        /***************Condition函数列表*******************************************/
        // 造成当前线程在接到信号或被中断之前一直处于等待状态。
        void await()
        // 造成当前线程在接到信号、被中断或到达指定等待时间之前一直处于等待状态。
        boolean await(long time, TimeUnit unit)
        // 造成当前线程在接到信号、被中断或到达指定等待时间之前一直处于等待状态。
        long awaitNanos(long nanosTimeout)
        // 造成当前线程在接到信号之前一直处于等待状态。
        void awaitUninterruptibly()
        // 造成当前线程在接到信号、被中断或到达指定最后期限之前一直处于等待状态。
        boolean awaitUntil(Date deadline)
        // 唤醒一个等待线程。
        void signal()
        // 唤醒所有等待线程。
        void signalAll();

        /**********LockSupport函数列表******************************************************************/
        // 返回提供给最近一次尚未解除阻塞的 park 方法调用的 blocker 对象，如果该调用不受阻塞，则返回 null。
        static Object getBlocker(Thread t)
        // 为了线程调度，禁用当前线程，除非许可可用。
        static void park()
        // 为了线程调度，在许可可用之前禁用当前线程。
        static void park(Object blocker)
        // 为了线程调度禁用当前线程，最多等待指定的等待时间，除非许可可用。
        static void parkNanos(long nanos)
        // 为了线程调度，在许可可用前禁用当前线程，并最多等待指定的等待时间。
        static void parkNanos(Object blocker, long nanos)
        // 为了线程调度，在指定的时限前禁用当前线程，除非许可可用。
        static void parkUntil(long deadline)
        // 为了线程调度，在指定的时限前禁用当前线程，除非许可可用。
        static void parkUntil(Object blocker, long deadline)
        // 如果给定线程的许可尚不可用，则使其可用。
        static void unpark(Thread thread);

        public interface ReadWriteLock {
            /**
             * 读锁,共享锁
             */
            Lock readLock();

            /**
             * 写锁,独占锁
             */
            Lock writeLock();
        }

        // 计数器
        static final class HoldCounter {
            // 计数
            int count = 0;
            // Use id, not reference, to avoid garbage retention
            // 获取当前线程的TID属性的值
            final long tid = getThreadId(Thread.currentThread());
        }

        // 本地线程计数器
        static final class ThreadLocalHoldCounter
                extends ThreadLocal<HoldCounter> {
            // 重写初始化方法，在没有进行set的情况下，获取的都是该HoldCounter值
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        abstract static class Sync extends AbstractQueuedSynchronizer {
            private static final long serialVersionUID = 6317671515068378041L;
            // 最多支持65535个写锁和65535个读锁；低16位表示写锁计数，高16位表示持有读锁的线程数
            static final int SHARED_SHIFT   = 16;
            // 由于读锁用高位部分，读锁个数加1，其实是状态值加 2^16
            // 0000000000000001|0000000000000000
            static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
            // 读锁最大数量,0000000000000000|1111111111111111
            static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
            // 写锁的掩码，用于状态的低16位有效值0000000000000000|1111111111111111
            static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;
            /** 读锁计数，当前持有读锁的线程数，c的高16位 */
            static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
            /** 写锁的计数，也就是它的重入次数,c的低16位*/
            static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

            //表示占有读锁的线程数量
            static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
            //表示占有写锁的线程数量
            static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }
            /**
             * 当前线程持有的可重入读锁的数量，仅在构造方法和readObject(反序列化)
             * 时被初始化，当持有锁的数量为0时，移除此对象。
             */
            private transient ThreadLocalHoldCounter readHolds;
            /**
             * 最近一个成功获取读锁的线程的计数。这省却了ThreadLocal查找，
             * 通常情况下，下一个释放线程是最后一个获取线程。这不是 volatile 的，
             * 因为它仅用于试探的，线程进行缓存也是可以的
             * （因为判断是否是当前线程是通过线程id来比较的）。
             */
            private transient HoldCounter cachedHoldCounter;
            // 第一个读线程
            private transient Thread firstReader = null;
            // 第一个读线程的计数
            private transient int firstReaderHoldCount;
        }
        // 构造函数
        Sync() {
            // 本地线程计数器
            readHolds = new ThreadLocalHoldCounter();
            // 设置AQS的状态
            setState(getState()); // ensures visibility of readHolds
        }


        /*******ReentrantReadWriteLock函数列表********************************************/
        // 创建一个新的 ReentrantReadWriteLock，默认是采用“非公平策略”。
        ReentrantReadWriteLock()
        // 创建一个新的 ReentrantReadWriteLock，fair是“公平策略”。fair为true，意味着公平策略；否则，意味着非公平策略。
        ReentrantReadWriteLock(boolean fair)

        // 返回当前拥有写入锁的线程，如果没有这样的线程，则返回 null。
        protected Thread getOwner()
        // 返回一个 collection，它包含可能正在等待获取读取锁的线程。
        protected Collection<Thread> getQueuedReaderThreads()
        // 返回一个 collection，它包含可能正在等待获取读取或写入锁的线程。
        protected Collection<Thread> getQueuedThreads()
        // 返回一个 collection，它包含可能正在等待获取写入锁的线程。
        protected Collection<Thread> getQueuedWriterThreads()
        // 返回等待获取读取或写入锁的线程估计数目。
        int getQueueLength()
        // 查询当前线程在此锁上保持的重入读取锁数量。
        int getReadHoldCount()
        // 查询为此锁保持的读取锁数量。
        int getReadLockCount()
        // 返回一个 collection，它包含可能正在等待与写入锁相关的给定条件的那些线程。
        protected Collection<Thread> getWaitingThreads(Condition condition)
        // 返回正等待与写入锁相关的给定条件的线程估计数目。
        int getWaitQueueLength(Condition condition)
        // 查询当前线程在此锁上保持的重入写入锁数量。
        int getWriteHoldCount()
        // 查询是否给定线程正在等待获取读取或写入锁。
        boolean hasQueuedThread(Thread thread)
        // 查询是否所有的线程正在等待获取读取或写入锁。
        boolean hasQueuedThreads()
        // 查询是否有些线程正在等待与写入锁有关的给定条件。
        boolean hasWaiters(Condition condition)
        // 如果此锁将公平性设置为 ture，则返回 true。
        boolean isFair()
        // 查询是否某个线程保持了写入锁。
        boolean isWriteLocked()
        // 查询当前线程是否保持了写入锁。
        boolean isWriteLockedByCurrentThread()
        // 返回用于读取操作的锁。
        ReentrantReadWriteLock.ReadLock readLock()
        // 返回用于写入操作的锁。
        ReentrantReadWriteLock.WriteLock writeLock()


        public static class ReadLock implements Lock, java.io.Serializable {
            private static final long serialVersionUID = -5992448646407690164L;
            //持有的AQS对象
            private final Sync sync;

            protected ReadLock(ReentrantReadWriteLock lock) {
                sync = lock.sync;
            }

            //获取共享锁
            public void lock() {
                sync.acquireShared(1);
            }

            //获取共享锁(响应中断)
            public void lockInterruptibly() throws InterruptedException {
                sync.acquireSharedInterruptibly(1);
            }

            //尝试获取共享锁
            public boolean tryLock(long timeout, TimeUnit unit)
                    throws InterruptedException {
                return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
            }

            //释放锁
            public void unlock() {
                sync.releaseShared(1);
            }

            //新建条件
            public Condition newCondition() {
                throw new UnsupportedOperationException();
            }

            public String toString() {
                int r = sync.getReadLockCount();
                return super.toString() +
                        "[Read locks = " + r + "]";
            }
        }


        public void lock() {
            sync.acquireShared(1);
        }

        protected final int tryAcquireShared(int unused) {
            //获取当前线程
            Thread current = Thread.currentThread();
            int c = getState();
            //如果“锁”是“独占锁”，并且获取锁的线程不是current线程；则返回-1。
            if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current)
                return -1;
            //获取共享计数
            int r = sharedCount(c);
            // 如果“不需要阻塞等待”，并且“读取锁”的共享计数小于MAX_COUNT
            // 则通过CAS函数更新“锁的状态”，将“读取锁”的共享计数+1
            if (!readerShouldBlock() &&
                    r < MAX_COUNT &&
                    compareAndSetState(c, c + SHARED_UNIT)) {
                // 第1次获取“共享锁”
                if (r == 0) {
                    firstReader = current;
                    firstReaderHoldCount = 1;
                //如果想要获取锁的线程(current)是第1个获取锁(firstReader)的线程
                } else if (firstReader == current) {
                    firstReaderHoldCount++;
                } else {
                    // HoldCounter用来统计该线程获取“读取锁”的次数。
                    HoldCounter rh = cachedHoldCounter;
                    if (rh == null || rh.tid != getThreadId(current))
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                        readHolds.set(rh);
                    // 将该线程获取“读取锁”的次数+1。
                    rh.count++;
                }
                return 1;
            }
            return fullTryAcquireShared(current);
        }

        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            for (;;) {
                // 获取“锁”的状态
                int c = getState();
                // 如果“锁”是“互斥锁”，并且获取锁的线程不是current线程；则返回-1。
                if (exclusiveCount(c) != 0) {
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                    // 如果“需要阻塞等待”。
                    // (01) 当“需要阻塞等待”的线程是第1个获取锁的线程的话，则继续往下执行。
                    // (02) 当“需要阻塞等待”的线程获取锁的次数=0时，则返回-1。
                } else if (readerShouldBlock()) {
                    // 如果想要获取锁的线程(current)是第1个获取锁(firstReader)的线程
                    if (firstReader == current) {
                    } else {
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != current.getId()) {
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        // 如果当前线程获取锁的计数=0,则返回-1。
                        if (rh.count == 0)
                            return -1;
                    }
                }
                // 如果“不需要阻塞等待”，则获取“读取锁”的共享统计数；
                // 如果共享统计数超过MAX_COUNT，则抛出异常。
                if (sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // 将线程获取“读取锁”的次数+1。
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    // 如果是第1次获取“读取锁”，则更新firstReader和firstReaderHoldCount。
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                        // 如果想要获取锁的线程(current)是第1个获取锁(firstReader)的线程，
                        // 则将firstReaderHoldCount+1。
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != current.getId())
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        // 更新线程的获取“读取锁”的共享计数
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }


        protected final boolean tryReleaseShared(int unused) {
            // 获取当前线程，即释放共享锁的线程。
            Thread current = Thread.currentThread();
            // 如果想要释放锁的线程(current)是第1个获取锁(firstReader)的线程，
            // 并且“第1个获取锁的线程获取锁的次数”=1，则设置firstReader为null；
            // 否则，将“第1个获取锁的线程的获取次数”-1。
            if (firstReader == current) {
                // assert firstReaderHoldCount > 0;
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
                // 获取rh对象，并更新“当前线程获取锁的信息”。
            } else {

                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != current.getId())
                    rh = readHolds.get();
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            for (;;) {
                // 获取锁的状态
                int c = getState();
                // 将锁的获取次数-1。
                int nextc = c - SHARED_UNIT;
                // 通过CAS更新锁的状态。
                if (compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }
    }

    /***************************CountDownLatch***********************************/
    CountDownLatch(int count)
    构造一个用给定计数初始化的 CountDownLatch。

    // 使当前线程在锁存器倒计数至零之前一直等待，除非线程被中断。
    void await()
    // 使当前线程在锁存器倒计数至零之前一直等待，除非线程被中断或超出了指定的等待时间。
    boolean await(long timeout, TimeUnit unit)
    // 递减锁存器的计数，如果计数到达零，则释放所有等待的线程。
    void countDown()
    // 返回当前计数。
    long getCount();

    private void doAcquireSharedInterruptibly(long arg)
            throws InterruptedException {
        // 创建"当前线程"的Node节点，且Node中记录的锁是"共享锁"类型；并将该节点添加到CLH队列末尾。
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                // 获取上一个节点。
                // 如果上一节点是CLH队列的表头，则"尝试获取共享锁"。
                final Node p = node.predecessor();
                if (p == head) {
                    long r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                // (上一节点不是CLH队列的表头) 当前线程一直等待，直到获取到共享锁。
                // 如果线程在等待过程中被中断过，则再次中断该线程(还原之前的中断状态)。
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /******************************CyclicBarrier************************************/

    // 每次对barrier的使用可以表现为一个 generation 实例。当条件 trip 改变或者重置 generation 也会
    // 随之改变。可以有多个 generation 和使用barrier的线程关联，但是只有一个可以获得锁。
    private static class Generation {
        boolean broken = false;
    }
    /** 守护barrier入口的锁 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 等待条件，直到所有线程到达barrier */
    private final Condition trip = lock.newCondition();
    /** 要屏障的线程数 */
    private final int parties;
    /* 当线程都到达barrier，运行的 Runnable */
    private final Runnable barrierCommand;
    /** The current generation */
    private Generation generation = new Generation();

    //还要等待多少个线程到达。线程到达屏障点就减去 1。
    //每次新建 generation 的时候或者屏障 broken，count重新设置为 parties 参数值
    private int count;

    //创建一个新的 CyclicBarrier，它将在给定数量的参与者（线程）处于等待状态时启动，但它不会在启动 barrier 时执行预定义的操作。
    CyclicBarrier(int parties)
    //创建一个新的 CyclicBarrier，它将在给定数量的参与者（线程）处于等待状态时启动，并在启动 barrier 时执行给定的屏障操作，该操作由最后一个进入 barrier 的线程执行。
    CyclicBarrier(int parties, Runnable barrierAction);
    //在 一个 barrier 完成后, 重新初始化值
    private void nextGeneration();
    //用于等待的线程当被中断, 或等待超时执行
    private void breakBarrier();
    private int dowait(boolean timed, long nanos);
    //在所有参与者都已经在此 barrier 上调用 await 方法之前，将一直等待。
    public int await();
    //在所有参与者都已经在此屏障上调用 await 方法之前将一直等待,或者超出了指定的等待时间。
    public int await(long timeout, TimeUnit unit);
    //返回要求启动此 barrier 的参与者数目。
    public int getParties();
    //查询此屏障是否处于损坏状态。
    public boolean isBroken();
    //将屏障重置为其初始状态。
    public void reset();
    //返回当前在屏障处等待的参与者数目。
    public int getNumberWaiting();

    private int dowait(boolean timed, long nanos)
            throws InterruptedException, BrokenBarrierException,
            TimeoutException {
        final ReentrantLock lock = this.lock;
        // 获取“独占锁(lock)”
        lock.lock();
        try {
            // 保存“当前的generation”
            final Generation g = generation;

            // 若“当前generation已损坏”，则抛出异常。
            if (g.broken)
                throw new BrokenBarrierException();

            // 如果当前线程被中断，则通过breakBarrier()终止CyclicBarrier，唤醒CyclicBarrier中所有等待线程。
            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }

            // 将“count计数器”-1
            int index = --count;
            // 如果index=0，则意味着“有parties个线程到达barrier”。
            if (index == 0) {  // tripped
                boolean ranAction = false;
                try {
                    // 如果barrierCommand不为null，则执行该动作。
                    final Runnable command = barrierCommand;
                    if (command != null)
                        command.run();
                    ranAction = true;
                    // 唤醒所有等待线程，并更新generation。
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction)
                        breakBarrier();
                }
            }

            // 当前线程一直阻塞，直到“有parties个线程到达barrier” 或 “当前线程被中断” 或 “超时”这3者之一发生，
            // 当前线程才继续执行。
            for (;;) {
                try {
                    // 如果不是“超时等待”，则调用awati()进行等待；否则，调用awaitNanos()进行等待。
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    // 如果等待过程中，线程被中断，则执行下面的函数。
                    if (g == generation && ! g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        Thread.currentThread().interrupt();
                    }
                }

                // 如果“当前generation已经损坏”，则抛出异常。
                if (g.broken)
                    throw new BrokenBarrierException();

                // 如果“generation已经换代”，则返回index。
                if (g != generation)
                    return index;

                // 如果是“超时等待”，并且时间已到，则通过breakBarrier()终止CyclicBarrier，唤醒CyclicBarrier中所有等待线程。
                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            // 释放“独占锁(lock)”
            lock.unlock();
        }
    }

    /****************************Semaphore************************************/

    // 创建具有给定的许可数和非公平的公平设置的 Semaphore。
    Semaphore(int permits)
    // 创建具有给定的许可数和给定的公平设置的 Semaphore。
    Semaphore(int permits, boolean fair)

    // 从此信号量获取一个许可，在提供一个许可前一直将线程阻塞，否则线程被中断。
    void acquire()
    // 从此信号量获取给定数目的许可，在提供这些许可前一直将线程阻塞，或者线程已被中断。
    void acquire(int permits)
    // 从此信号量中获取许可，在有可用的许可前将其阻塞。
    void acquireUninterruptibly()
    // 从此信号量获取给定数目的许可，在提供这些许可前一直将线程阻塞。
    void acquireUninterruptibly(int permits)
    // 返回此信号量中当前可用的许可数。
    int availablePermits()
    // 获取并返回立即可用的所有许可。
    int drainPermits()
    // 返回一个 collection，包含可能等待获取的线程。
    protected Collection<Thread> getQueuedThreads()
    // 返回正在等待获取的线程的估计数目。
    int getQueueLength()
    // 查询是否有线程正在等待获取。
    boolean hasQueuedThreads()
    // 如果此信号量的公平设置为 true，则返回 true。
    boolean isFair()
    // 根据指定的缩减量减小可用许可的数目。
    protected void reducePermits(int reduction)
    // 释放一个许可，将其返回给信号量。
    void release()
    // 释放给定数目的许可，将其返回到信号量。
    void release(int permits)
    // 返回标识此信号量的字符串，以及信号量的状态。
    String toString()
    // 仅在调用时此信号量存在一个可用许可，才从信号量获取许可。
    boolean tryAcquire()
    // 仅在调用时此信号量中有给定数目的许可时，才从此信号量中获取这些许可。
    boolean tryAcquire(int permits)
    // 如果在给定的等待时间内此信号量有可用的所有许可，并且当前线程未被中断，则从此信号量获取给定数目的许可。
    boolean tryAcquire(int permits, long timeout, TimeUnit unit)
    // 如果在给定的等待时间内，此信号量有可用的许可并且当前线程未被中断，则从此信号量获取一个许可。
    boolean tryAcquire(long timeout, TimeUnit unit);

    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1192457210091910933L;

        Sync(int permits) {
            setState(permits);
        }
        //获取许可
        final int getPermits() {
            return getState();
        }
        //非公平获取
        final int nonfairTryAcquireShared(int acquires) {
            for (;;) {
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    return remaining;
            }
        }
        //释放
        protected final boolean tryReleaseShared(int releases) {
            for (;;) {
                int current = getState();
                int next = current + releases;
                if (next < current) // overflow
                    throw new Error("Maximum permit count exceeded");
                if (compareAndSetState(current, next))
                    return true;
            }
        }
        //减少指定许可数
        final void reducePermits(int reductions) {
            for (;;) {
                int current = getState();
                int next = current - reductions;
                if (next > current) // underflow
                    throw new Error("Permit count underflow");
                if (compareAndSetState(current, next))
                    return;
            }
        }
        //获取并返回立即可用的所有许可
        final int drainPermits() {
            for (;;) {
                int current = getState();
                if (current == 0 || compareAndSetState(current, 0))
                    return current;
            }
        }
    }
    /**非公平Sync*/
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -2694183684443567898L;

        NonfairSync(int permits) {
            super(permits);
        }

        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }
    /**公平Sync*/
    static final class FairSync extends Sync {
        private static final long serialVersionUID = 2014338818796000944L;

        FairSync(int permits) {
            super(permits);
        }

        protected int tryAcquireShared(int acquires) {
            for (;;) {
                if (hasQueuedPredecessors())
                    return -1;
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    return remaining;
            }
        }
    }

    //获取信号量
    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }
    //获取指定permits数的信号量
    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireSharedInterruptibly(permits);
    }
    //获取指定permits数的信号量,不响应中断
    public void acquireUninterruptibly(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireShared(permits);
    }


    //非公平信号量获取
    protected int tryAcquireShared(int acquires) {
        return nonfairTryAcquireShared(acquires);
    }
    //公平信号量获取
    protected int tryAcquireShared(int acquires) {
        for (;;) {
            // 判断当前线程是否还有前任线程
            if (hasQueuedPredecessors())
                return -1;
            //可获得的信号数
            int available = getState();
            //获取信号数之后剩余的信号数
            int remaining = available - acquires;
            if (remaining < 0 ||
                    compareAndSetState(available, remaining))
                return remaining;
        }
    }
    //释放信号量
    public void release() {
        sync.releaseShared(1);
    }
    //释放指定permits数的信号量
    public void release(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.releaseShared(permits);
    }

    protected final boolean tryReleaseShared(int releases) {
        for (;;) {
            //可获得的信号数
            int current = getState();
            //释放releases个信号后,剩余可获得的信号数
            int next = current + releases;
            if (next < current) // overflow
                throw new Error("Maximum permit count exceeded");
            //设置可获得的信号数为next
            if (compareAndSetState(current, next))
                return true;
        }
    }


        //获取CPU的可用线程数量，用于确定自旋的时候循环次数
        private static final int NCPU = Runtime.getRuntime().availableProcessors();
        //根据NCPU确定自旋的次数限制(并不是一定这么多次，因为实际代码中是随机的)
        private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;
        //头节点上的自旋次数
        private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;
        //头节点上的最大自旋次数
        private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;
        private static final int LG_READERS = 7;
        //一个读状态单位
        private static final long RUNIT = 1L;
        //写状态标识
        private static final long WBIT  = 1L << LG_READERS;
        //读状态标识(前7位)
        private static final long RBITS = WBIT - 1L;
        //最大的读状态
        private static final long RFULL = RBITS - 1L;
        //用于获取读写状态
        private static final long ABITS = RBITS | WBIT;
        private static final long SBITS = ~RBITS; // note overlap with ABITS
        //初始化状态
        private static final long ORIGIN = WBIT << 1;
        //中断标识
        private static final long INTERRUPTED = 1L;
        // 等待/取消
        private static final int WAITING   = -1;
        private static final int CANCELLED =  1;
        //读/写状态
        private static final int RMODE = 0;
        private static final int WMODE = 1;
        //因为读状态只有7位很小，所以当超过了128之后将使用一个int变量来记录
        private transient int readerOverflow;


        /************************CopyOnWriteArrayList*************************************/
        // 创建一个空列表。
        CopyOnWriteArrayList();
        // 创建一个按 collection 的迭代器返回元素的顺序包含指定 collection 元素的列表。
        CopyOnWriteArrayList(Collection<? extends E> c)
        //创建一个保存给定数组的副本的列表。
        CopyOnWriteArrayList(E[] toCopyIn)

        // 将指定元素添加到此列表的尾部。
        boolean add(E e)
        // 在此列表的指定位置上插入指定元素。
        void add(int index, E element)
        // 按照指定 collection 的迭代器返回元素的顺序，将指定 collection 中的所有元素添加此列表的尾部。
        boolean addAll(Collection<? extends E> c)
        // 从指定位置开始，将指定 collection 的所有元素插入此列表。
        boolean addAll(int index, Collection<? extends E> c)
        // 按照指定 collection 的迭代器返回元素的顺序，将指定 collection 中尚未包含在此列表中的所有元素添加列表的尾部。
        int addAllAbsent(Collection<? extends E> c)
        // 添加元素（如果不存在）。
        boolean addIfAbsent(E e)
        // 从此列表移除所有元素。
        void clear()
        // 返回此列表的浅表副本。
        Object clone()
        // 如果此列表包含指定的元素，则返回 true。
        boolean contains(Object o)
        // 如果此列表包含指定 collection 的所有元素，则返回 true。
        boolean containsAll(Collection<?> c)
        // 比较指定对象与此列表的相等性。
        boolean equals(Object o)
        // 返回列表中指定位置的元素。
        E get(int index)
        // 返回此列表的哈希码值。
        int hashCode()
        // 返回第一次出现的指定元素在此列表中的索引，从 index 开始向前搜索，如果没有找到该元素，则返回 -1。
        int indexOf(E e, int index)
        // 返回此列表中第一次出现的指定元素的索引；如果此列表不包含该元素，则返回 -1。
        int indexOf(Object o)
        // 如果此列表不包含任何元素，则返回 true。
        boolean isEmpty()
        // 返回以恰当顺序在此列表元素上进行迭代的迭代器。
        Iterator<E> iterator()
        // 返回最后一次出现的指定元素在此列表中的索引，从 index 开始向后搜索，如果没有找到该元素，则返回 -1。
        int lastIndexOf(E e, int index)
        // 返回此列表中最后出现的指定元素的索引；如果列表不包含此元素，则返回 -1。
        int lastIndexOf(Object o)
        // 返回此列表元素的列表迭代器（按适当顺序）。
        ListIterator<E> listIterator()
        // 返回列表中元素的列表迭代器（按适当顺序），从列表的指定位置开始。
        ListIterator<E> listIterator(int index)
        // 移除此列表指定位置上的元素。
        E remove(int index)
        // 从此列表移除第一次出现的指定元素（如果存在）。
        boolean remove(Object o)
        // 从此列表移除所有包含在指定 collection 中的元素。
        boolean removeAll(Collection<?> c)
        // 只保留此列表中包含在指定 collection 中的元素。
        boolean retainAll(Collection<?> c)
        // 用指定的元素替代此列表指定位置上的元素。
        E set(int index, E element)
        // 返回此列表中的元素数。
        int size()
        // 返回此列表中 fromIndex（包括）和 toIndex（不包括）之间部分的视图。
        List<E> subList(int fromIndex, int toIndex)
        // 返回一个按恰当顺序（从第一个元素到最后一个元素）包含此列表中所有元素的数组。
        Object[] toArray()
        // 返回以恰当顺序（从第一个元素到最后一个元素）包含列表所有元素的数组；返回数组的运行时类型是指定数组的运行时类型。
        <T> T[] toArray(T[] a)
        // 返回此列表的字符串表示形式。
        String toString();


    private transient volatile Object[] array;
    final Object[] getArray() {
        return array;
    }
    final void setArray(Object[] a) {
        array = a;
    }

    public CopyOnWriteArrayList() {
        setArray(new Object[0]);
    }

    public CopyOnWriteArrayList(Collection<? extends E> c) {
        Object[] elements;
        if (c.getClass() == CopyOnWriteArrayList.class)
            elements = ((CopyOnWriteArrayList<?>)c).getArray();
        else {
            elements = c.toArray();
            // c.toArray might (incorrectly) not return Object[] (see 6260652)
            if (elements.getClass() != Object[].class)
                elements = Arrays.copyOf(elements, elements.length, Object[].class);
        }
        setArray(elements);
    }

    public CopyOnWriteArrayList(E[] toCopyIn) {
        setArray(Arrays.copyOf(toCopyIn, toCopyIn.length, Object[].class));
    }

    public boolean add(E e) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] elements = getArray();
            int len = elements.length;
            Object[] newElements = Arrays.copyOf(elements, len + 1);
            newElements[len] = e;
            setArray(newElements);
            return true;
        } finally {
            lock.unlock();
        }
    }
    public void add(int index, E element) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] elements = getArray();
            int len = elements.length;
            if (index > len || index < 0)
                throw new IndexOutOfBoundsException("Index: "+index+
                        ", Size: "+len);
            Object[] newElements;
            //计算偏移量
            int numMoved = len - index;
            if (numMoved == 0)
                //作为add(E)处理
                newElements = Arrays.copyOf(elements, len + 1);
            else {
                newElements = new Object[len + 1];
                //调用native方法根据index拷贝原数组的前半段
                System.arraycopy(elements, 0, newElements, 0, index);
                //拷贝后半段
                System.arraycopy(elements, index, newElements, index + 1,
                        numMoved);
            }
            newElements[index] = element;
            setArray(newElements);
        } finally {
            lock.unlock();
        }
    }

    public boolean addIfAbsent(E e) {
        Object[] snapshot = getArray();
        return indexOf(e, snapshot, 0, snapshot.length) >= 0 ? false :
                addIfAbsent(e, snapshot);
    }

    private boolean addIfAbsent(E e, Object[] snapshot) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] current = getArray();
            int len = current.length;
            if (snapshot != current) {
                // Optimize for lost race to another addXXX operation
                //操作中有别的线程对array做了修改,取较小的那个length
                int common = Math.min(snapshot.length, len);
                for (int i = 0; i < common; i++)
                    if (current[i] != snapshot[i] && eq(e, current[i]))
                        return false;
                if (indexOf(e, current, common, len) >= 0)
                    return false;
            }
            Object[] newElements = Arrays.copyOf(current, len + 1);
            newElements[len] = e;
            setArray(newElements);
            return true;
        } finally {
            lock.unlock();
        }
    }


    public E remove(int index) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] elements = getArray();
            int len = elements.length;
            E oldValue = get(elements, index);
            int numMoved = len - index - 1;
            if (numMoved == 0)
                setArray(Arrays.copyOf(elements, len - 1));
            else {
                Object[] newElements = new Object[len - 1];
                System.arraycopy(elements, 0, newElements, 0, index);
                System.arraycopy(elements, index + 1, newElements, index,
                        numMoved);
                setArray(newElements);
            }
            return oldValue;
        } finally {
            lock.unlock();
        }
    }

    //移除某个元素
    public boolean remove(Object o) {
        Object[] snapshot = getArray();
        int index = indexOf(o, snapshot, 0, snapshot.length);
        return (index < 0) ? false : remove(o, snapshot, index);
    }

    private boolean remove(Object o, Object[] snapshot, int index) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] current = getArray();
            int len = current.length;
            //判断是否有别的线程对array做了修改
            if (snapshot != current) findIndex: {
                int prefix = Math.min(index, len);
                for (int i = 0; i < prefix; i++) {
                    //找到其他线程修改后的不同的元素 比较是否为此线程需要操作的元素
                    if (current[i] != snapshot[i] && eq(o, current[i])) {
                        index = i;
                        break findIndex;
                    }
                }
                if (index >= len)
                    return false;
                if (current[index] == o)
                    break findIndex;
                index = indexOf(o, current, index, len);
                if (index < 0)
                    return false;
            }
            Object[] newElements = new Object[len - 1];
            System.arraycopy(current, 0, newElements, 0, index);
            System.arraycopy(current, index + 1,
                    newElements, index,
                    len - index - 1);
            setArray(newElements);
            return true;
        } finally {
            lock.unlock();
        }
    }

    void removeRange(int fromIndex, int toIndex) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] elements = getArray();
            int len = elements.length;

            if (fromIndex < 0 || toIndex > len || toIndex < fromIndex)
                throw new IndexOutOfBoundsException();
            int newlen = len - (toIndex - fromIndex);
            int numMoved = len - toIndex;
            if (numMoved == 0)
                setArray(Arrays.copyOf(elements, newlen));
            else {
                Object[] newElements = new Object[newlen];
                System.arraycopy(elements, 0, newElements, 0, fromIndex);
                System.arraycopy(elements, toIndex, newElements,
                        fromIndex, numMoved);
                setArray(newElements);
            }
        } finally {
            lock.unlock();
        }
    }

    public Iterator<E> iterator() {
        return new CopyOnWriteArrayList.COWIterator<E>(getArray(), 0);
    }

    /*********************CopyOnWriteArraySet*****************************************/
    // 创建一个空 set。
    CopyOnWriteArraySet()
    // 创建一个包含指定 collection 所有元素的 set。
    CopyOnWriteArraySet(Collection<? extends E> c)

    // 如果指定元素并不存在于此 set 中，则添加它。
    boolean add(E e)
    // 如果此 set 中没有指定 collection 中的所有元素，则将它们都添加到此 set 中。
    boolean addAll(Collection<? extends E> c)
    // 移除此 set 中的所有元素。
    void clear()
    // 如果此 set 包含指定元素，则返回 true。
    boolean contains(Object o)
    // 如果此 set 包含指定 collection 的所有元素，则返回 true。
    boolean containsAll(Collection<?> c)
    // 比较指定对象与此 set 的相等性。
    boolean equals(Object o)
    // 如果此 set 不包含任何元素，则返回 true。
    boolean isEmpty()
    // 返回按照元素添加顺序在此 set 中包含的元素上进行迭代的迭代器。
    Iterator<E> iterator()
    // 如果指定元素存在于此 set 中，则将其移除。
    boolean remove(Object o)
    // 移除此 set 中包含在指定 collection 中的所有元素。
    boolean removeAll(Collection<?> c)
    // 仅保留此 set 中那些包含在指定 collection 中的元素。
    boolean retainAll(Collection<?> c)
    // 返回此 set 中的元素数目。
    int size()
    // 返回一个包含此 set 所有元素的数组。
    Object[] toArray()
    // 返回一个包含此 set 所有元素的数组；返回数组的运行时类型是指定数组的类型。
    <T> T[] toArray(T[] a);

}
