package com.aengine.util.thread;


import com.aengine.util.collection.LinkedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 *
 * */
public final class OrderedExecutorService extends AbstractExecutorService {

	private static final Logger log = LoggerFactory.getLogger(OrderedExecutorService.class);

	private final int corePoolSize;

	private final int maxPoolSize;

	private final LinkedList<Runnable> tasks = new LinkedList<>();

	private final Lock lock = new ReentrantLock();

	private final Condition cond = lock.newCondition();

	private final int keepAliveTime;

	private int active;

	private final ThreadFactory factory;

	private volatile boolean stopped = false;

	private volatile boolean forceStopped = false;

	public OrderedExecutorService(int corePoolSize, int maxPoolSize, int keepAliveTime, ThreadFactory factory) {
		this.corePoolSize = corePoolSize;
		this.maxPoolSize = maxPoolSize;
		this.keepAliveTime = keepAliveTime;
		this.factory = factory;
	}

	@Override
	public void shutdown() {
		lock.lock();
		if (!stopped) {
			stopped = true;
			cond.signalAll();
		}
		lock.unlock();
	}

	@Override
	public List<Runnable> shutdownNow() {
		lock.lock();
		try {
			if (!stopped) {
				stopped = true;
				List<Runnable> list = new ArrayList<>();
				for (LinkedList.Node<Runnable> node = tasks.getFirst(); node != null; node = tasks.getNext(node)) {
					list.add(node.getItem());
				}
				tasks.clear();
				forceStopped = true;
				cond.signalAll();
				return list;
			} else {
				return new ArrayList<>();
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public boolean isShutdown() {
		return stopped;
	}

	@Override
	public boolean isTerminated() {
		lock.lock();
		try {
			return active == 0 && tasks.size() == 0;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		lock.lock();
		try {
			cond.await(timeout, unit);
			return isTerminated();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void execute(Runnable command) {
		lock.lock();
		try {
			if (stopped)
				return;
			if (active < corePoolSize) {
				if (((OrderedRunnable) command).tryLock()) {
					addWorker(command, true);
				} else {
					tasks.addLast(command);
					cond.signal();
				}
			} else if (active < maxPoolSize) {
				if (((OrderedRunnable) command).tryLock()) {
					addWorker(command, false);
				} else {
					tasks.addLast(command);
					cond.signal();
				}
			} else {
				tasks.addLast(command);
				cond.signal();
			}
		} finally {
			lock.unlock();
		}
	}

	private void addWorker(Runnable task, boolean core) {
		active++;
		factory.newThread(new Worker(task, core)).start();
	}

	private Runnable getAvailableTask() {
		for (LinkedList.Node<Runnable> node = tasks.getFirst(); node != null; node = tasks.getNext(node)) {
			OrderedRunnable orderedRunnable = (OrderedRunnable) node.getItem();
			if (!orderedRunnable.tryLock())
				continue;
			tasks.remove(node);
			return node.getItem();
		}
		return null;
	}

	private class Worker implements Runnable{
		private boolean core;

		private Runnable firstTask;

		public Worker(Runnable firstTask, boolean core) {
			this.core = core;
			this.firstTask = firstTask;
		}

		private Runnable getTask() {
			Runnable task;
			for (;;) {
				lock.lock();
				try {
					if (forceStopped || (stopped && tasks.size() == 0)) {
						return null;
					}
					task = getAvailableTask();
					if (task == null) {
						cond.await(keepAliveTime, TimeUnit.SECONDS);
						task = getAvailableTask();
						if (!core && task == null)
							return null;
					}
					if (task == null)
						continue;
					return task;
				} catch (InterruptedException e) {
					return null;
				} finally {
					lock.unlock();
				}
			}
		}

		public void run() {
			try {
				Runnable task = firstTask;
				firstTask = null;
				for (;;) {
					if (task == null)
						task = getTask();
					if (task == null) {
						break;
					} else {
						// 业务任务可能抛异常；必须在 finally 中解锁并唤醒，
						// 否则该连接的有序锁永不释放，后续包永久无法处理，且 active 计数泄漏导致整个线程池停摆。
						try {
							task.run();
						} catch (Throwable t) {
							log.error("execute ordered task error", t);
						} finally {
							lock.lock();
							((OrderedRunnable) task).unlock();
							cond.signal();
							lock.unlock();
							task = null;
						}
					}
				}
			} finally {
				lock.lock();
				active--;
				cond.signalAll();
				lock.unlock();
			}
		}
	}
}
