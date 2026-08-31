package com.aengine.network.netty;

import java.util.zip.CRC32;

/**
 */
public class CRC32CheckSum implements ICheckSum{
	@Override
	public byte[] checksum(byte[] bytes) {
		CRC32 crc32 = new CRC32();
		crc32.update(bytes, 0, bytes.length);
		long value = crc32.getValue();
		byte[] rs = new byte[4];
		rs[0] = (byte)((value >> 24) & 0xff);
		rs[1] = (byte)((value >> 16) & 0xff);
		rs[2] = (byte)((value >> 8) & 0xff);
		rs[3] = (byte)(value & 0xff);
		return rs;
	}

	@Override
	public int length() {
		return 4;
	}
}
