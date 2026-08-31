package com.aengine.util.thread;

/**
 */
public interface OrderedRunnable extends Runnable{

	void unlock();

	boolean tryLock();

	int getIdentity();
}
