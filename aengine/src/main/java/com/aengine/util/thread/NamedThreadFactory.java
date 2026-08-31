package com.aengine.util.thread;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 命名的线程工厂
 *
 */
public class NamedThreadFactory implements ThreadFactory{
	private final ThreadGroup group;
	private final AtomicInteger counter = new AtomicInteger(1);
	private final boolean daemon;

	public NamedThreadFactory(String name) {
		this(name, false);
	}

	/**
	 * @param name   线程名前缀
	 * @param daemon 是否守护线程；纯基础设施线程（清理/监视等）应设为 true，使其跟随主线程退出
	 */
	public NamedThreadFactory(String name, boolean daemon) {
		this.group = new ThreadGroup(name);
		this.daemon = daemon;
	}

	@Override
	public Thread newThread(Runnable r) {
		Thread t = new Thread(group, r, group.getName() + counter.getAndIncrement(), 0);
		t.setDaemon(daemon);
		return t;
	}
}
