package com.aengine.network.netty;

/**
 */
public interface ICheckSum {

	byte[] checksum(byte[] bytes);

	int length();
}
