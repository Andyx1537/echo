package com.aengine.util.id;

/**
 * UUID生成器，保证时间和空间具有唯一性
 */
public class UUIDGenerator {

    private static final String KEY_FOR_GEN = "key_gen_a";
    private static UUIDGenerateSupport supportInstance = null;

    public static void init(UUIDGenerateSupport support) {
        supportInstance = support;
    }

    /**
     * 以redis 支撑为前提的.uuid 生成器
     *
     * @return
     */
    public static final String getUUID() {
        return supportInstance.genLong(KEY_FOR_GEN) + "";

    }
    
	/**
	 * 以redis 支撑为前提的.uuid 生成器
	 *
	 * @return
	 */
	public static final long getLongUUID() {
		return supportInstance.genLong(KEY_FOR_GEN);
	}

//    /**
//     * 获取一个UUID
//     * @return
//     */
//    public static String getUUID() {
//        return UUID.randomUUID().toString();
//    }
//    
}
