package com.aengine.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络工具类
 *
 */
public class NetworkUtil {
    /**
     * 检查ip地址是否匹配模版
	 * 
	 * @param pattern           *.*.*.* , 192.168.1.0-255 , *
	 * @param address
	 *            - 192.168.1.1<BR>
	 *            <code>address = 10.2.88.12  pattern = *.*.*.*   result: true<BR>
	 *                address = 10.2.88.12  pattern = *   result: true<BR>
	 *                address = 10.2.88.12  pattern = 10.2.88.12-13   result: true<BR>
	 *                address = 10.2.88.12  pattern = 10.2.88.13-125   result: false<BR></code>
	 * @return 符合返回true，不符合返回false
	 */
	public static boolean checkIPMatching(String pattern, String address) {
		if(pattern.equals("*.*.*.*") || pattern.equals("*"))
			return true;

		String[] mask = pattern.split("\\.");
		String[] ip_address = address.split("\\.");
		for(int i = 0; i < mask.length; i++) {
			if(mask[i].equals("*") || mask[i].equals(ip_address[i])) {
                ;
            } else if(mask[i].contains("-")) {
				byte min = Byte.parseByte(mask[i].split("-")[0]);
				byte max = Byte.parseByte(mask[i].split("-")[1]);
				byte ip = Byte.parseByte(ip_address[i]);
				if(ip < min || ip > max)
					return false;
			} else
				return false;
		}
		return true;
	}

	/**
	 * 从类名中获取message_id
	 *
	 * @param clazz     类名
	 * @return
	 */
	public static int getMessageID(Class<?> clazz) {
        String name = clazz.getSimpleName();
        return Integer.parseInt(name.substring(name.lastIndexOf('_') + 1));
    }

	/**
	 * 判断ip地址格式是否合法
	 *
	 * @param ip        ip地址
	 * @return
	 */
	public static boolean validateIPFormat(String ip) {
		Pattern pattern = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
		Matcher matcher = pattern.matcher(ip);
		if (!matcher.find())
			return false;
		String[] temp = ip.split("\\.");
		if (temp.length != 4)
			return false;
		for (int i=0; i<temp.length; i++) {
			int mask = Integer.parseInt(temp[i]);
			if (mask < 0 || mask > 255)
				return false;
			if (i == 0 && mask == 0)
				return false;
			if (i == 3 && mask == 0)
				return false;
		}
		return true;
    }

	public static InetAddress getLocalHostLANAddress() throws Exception {
		try {
			InetAddress candidateAddress = null;
			// 遍历所有的网络接口
			for (Enumeration ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements(); ) {
				NetworkInterface iface = (NetworkInterface) ifaces.nextElement();
				// 在所有的接口下再遍历IP
				for (Enumeration inetAddrs = iface.getInetAddresses(); inetAddrs.hasMoreElements(); ) {
					InetAddress inetAddr = (InetAddress) inetAddrs.nextElement();
					if (!inetAddr.isLoopbackAddress()) {// 排除loopback类型地址
						if (inetAddr.isSiteLocalAddress()) {
							// 如果是site-local地址，就是它了
							return inetAddr;
						} else if (candidateAddress == null) {
							// site-local类型的地址未被发现，先记录候选地址
							candidateAddress = inetAddr;
						}
					}
				}
			}
			if (candidateAddress != null) {
				return candidateAddress;
			}
			// 如果没有发现 non-loopback地址.只能用最次选的方案
			InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
			return jdkSuppliedAddress;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}