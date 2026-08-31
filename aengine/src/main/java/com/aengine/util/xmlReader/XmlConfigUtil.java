/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.xmlReader;

import com.aengine.util.CustomAttributes;
import com.aengine.util.NetConfig;
import com.aengine.util.RedisConfig;
import com.aengine.util.ServerConfig;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向对象的思想统筹整个配置信息
 *
 */
public class XmlConfigUtil {

    static Map<String, NetConfig> netConfigMap = new HashMap<>();
    static List<RedisConfig> redisConfigs = new ArrayList<>();
    static Map<String, CustomAttributes> attributes = new HashMap<>();

    static ServerConfig serverConfig = new ServerConfig();

    public static void main(String[] args) {
        try {

            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            URL url = loader.getResource("serverConfig.xml");
            File file = new File(url.getPath());
            file.isDirectory();
            SAXParserFactory spf = SAXParserFactory.newInstance();
            SAXParser parser = spf.newSAXParser();
            XmlHandler handler = new XmlHandler();
            parser.parse(new FileInputStream(file), handler);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    public static void initXmlContent(String urlstr) throws ParserConfigurationException, SAXException, FileNotFoundException, IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL url = loader.getResource(urlstr);
        File file = new File(url.getPath());
//        File file = new File(url);
        SAXParserFactory spf = SAXParserFactory.newInstance();
        SAXParser parser = spf.newSAXParser();
        XmlHandler handler = new XmlHandler();
        parser.parse(new FileInputStream(file), handler);
    }

    public static Map<String, NetConfig> getNetConfigMap() {
        return netConfigMap;
    }

    public static List<RedisConfig> getRedisConfigs() {
        return redisConfigs;
    }

    public static ServerConfig getServerConfig() {
        return serverConfig;
    }
    
    public static Map<String,CustomAttributes> getAttributes(){
    	return attributes;
    }
    
    public static String getByNameForAttributes(String name) {
    	return attributes.containsKey(name) ? attributes.get(name).getValue() : null;
    }

}
