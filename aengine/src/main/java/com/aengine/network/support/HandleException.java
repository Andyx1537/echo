package com.aengine.network.support;

/**
 */
public class HandleException extends Exception{

	private final int cmd;

	public HandleException(int cmd, Throwable t) {
		super("cmd="+cmd, t);
		this.cmd = cmd;
	}

	public int getCmd() {
		return cmd;
	}
}
