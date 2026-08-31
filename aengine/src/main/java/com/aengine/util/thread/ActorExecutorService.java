package com.aengine.util.thread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 */
public class ActorExecutorService extends AbstractExecutorService {

	private final ExecutorService[] threads;

	public ActorExecutorService(int threads, ThreadFactory threadFactory) {
		this.threads = new ExecutorService[threads];
		for (int i=0; i<threads; i++) {
			this.threads[i] = Executors.newSingleThreadExecutor(threadFactory);
		}
	}

	@Override
	public void shutdown() {
		for (ExecutorService e : threads) {
			e.shutdown();
		}
	}

	@Override
	public List<Runnable> shutdownNow() {
		List<Runnable> list = new ArrayList<>();
		for (ExecutorService e : threads) {
			list.addAll(e.shutdownNow());
		}
		return list;
	}

	@Override
	public boolean isShutdown() {
		for (ExecutorService e : threads) {
			if (!e.isShutdown())
				return false;
		}
		return true;
	}

	@Override
	public boolean isTerminated() {
		for (ExecutorService e : threads) {
			if (!e.isTerminated())
				return false;
		}
		return true;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		for (ExecutorService e : threads) {
			if (!e.awaitTermination(timeout, unit))
				return false;
		}
		return true;
	}

	@Override
	public void execute(Runnable command) {
		int id = ((OrderedRunnable) command).getIdentity();
		ExecutorService worker = threads[id % threads.length];
		worker.execute(command);
	}
}
