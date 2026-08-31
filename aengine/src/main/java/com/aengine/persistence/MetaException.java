package com.aengine.persistence;

/**
 * 数据异常
 *
 */
public class MetaException extends RuntimeException{

	private static final long serialVersionUID = -7804099887147092337L;

	public MetaException() {
		super();
	}

	public MetaException(String message) {
		super(message);
	}

	public MetaException(Throwable t) {
		super(t);
	}
}
