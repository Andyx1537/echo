package com.aengine.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Properties文件加载辅助类
 *
 */
public class PropertiesLoader {

	private static final Logger log = LoggerFactory.getLogger(PropertiesLoader.class);

    /**
     * 加载指定相对路径的Properties文件
     *
     * @param path      相对路径
     * @return      Properties对象
     */
	public static Properties load(String path) {
		InputStream fis = PropertiesLoader.class.getClassLoader().getResourceAsStream(path);
		try {
			Properties properties = new Properties();
			properties.load(fis);
			return properties;
		} catch (IOException e) {
			log.error("", e);
			return null;
		} finally {
			try {
				fis.close();
			} catch (IOException e) {
				log.error("", e);
			}
		}
	}

    /**
     * 加载指定绝对路径的Properties文件
     *
     * @param path      绝对路径
     * @return      Properties对象
     */
	public static Properties loadAbsolute(String path) {
        InputStream fis = null;
        try {
            fis = new FileInputStream(new File(path));
            Properties properties = new Properties();
            properties.load(fis);
            return properties;
        } catch (IOException e) {
            log.error("", e);
            return null;
        } finally {
            try {
                if (fis != null)
                    fis.close();
            } catch (IOException e) {
                log.error("", e);
            }
        }
    }
}
