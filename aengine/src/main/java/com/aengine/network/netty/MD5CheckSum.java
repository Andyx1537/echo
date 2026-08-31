package com.aengine.network.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;

/**
 */
public class MD5CheckSum implements ICheckSum{

	private static final Logger log = LoggerFactory.getLogger(MD5CheckSum.class);

	@Override
	public byte[] checksum(byte[] bytes) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("MD5");
			messageDigest.update(bytes);
			return messageDigest.digest();
		} catch (Exception e) {
			log.error("unsupported digest: md5", e);
			return new byte[0];
		}
	}

	@Override
	public int length() {
		return 8;
	}
}
