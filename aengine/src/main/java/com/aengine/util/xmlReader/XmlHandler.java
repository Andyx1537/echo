/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.xmlReader;

import com.aengine.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.lang.reflect.Field;

/**
 */
public class XmlHandler extends DefaultHandler {

    private static final String ELEMENT_NET = "netConfig";
    private static final String ELEMENT_REDIS = "redisConfig";
    private static final String ELEMENT_SERVER = "serverConfig";
    private static final String ELEMENT_CUSTOM = "customAttributes";
    private static Logger log = LoggerFactory.getLogger(XmlHandler.class);

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        super.endElement(uri, localName, qName); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        super.startElement(uri, localName, qName, attributes); //To change body of generated methods, choose Tools | Templates.
        String currentTag = qName;
        String contentName = attributes.getValue("name");

        Object obj = null;
        switch (currentTag) {
            case ELEMENT_NET:
                NetConfig net = new NetConfig();
                XmlConfigUtil.netConfigMap.put(contentName, net);
                obj = net;
                break;
            case ELEMENT_REDIS:
                RedisConfig redis = new RedisConfig();
//                    XmlConfigUtil.redisConfigMap.put(contentName, redis);
                XmlConfigUtil.redisConfigs.add(redis);
                obj = redis;
                break;
            case ELEMENT_SERVER:
                XmlConfigUtil.serverConfig = new ServerConfig();
                obj = XmlConfigUtil.serverConfig;
                break;
            case ELEMENT_CUSTOM:
                CustomAttributes attribute = new CustomAttributes();
                XmlConfigUtil.attributes.put(contentName, attribute);
                obj = attribute;
                break;
            default:
                return;
        }

        for (int i = 0; i < attributes.getLength(); i++) {
            String attributeName = attributes.getQName(i);
            String value = attributes.getValue(attributeName);
            try {
                Field field = obj.getClass().getDeclaredField(attributeName);
                field.setAccessible(true);
                Class<?> type = field.getType();
                if (type == long.class || type == Long.class) {
                    Number num = Long.valueOf(value);
                    field.setLong(obj, num.longValue());
                } else if (type == int.class || type == Integer.class) {
                    Number num = Integer.parseInt(value);
                    field.setInt(obj, num.intValue());
                } else if (type == short.class || type == Short.class) {
                    Number num = Short.valueOf(value);
                    field.setShort(obj, num.shortValue());
                } else if (type == byte.class || type == Byte.class) {
                    Number num = Byte.valueOf(value);
                    field.setByte(obj, num.byteValue());
                } else if (type == boolean.class || type == Boolean.class) {

                    field.setBoolean(obj, Boolean.valueOf(value));
                } else if (type == double.class || type == Double.class) {
                    Number num = Double.valueOf(value);
                    field.setDouble(obj, num.doubleValue());
                } else if (type == float.class || type == Float.class) {
                    Number num = Float.valueOf(value);
                    field.setFloat(obj, num.floatValue());
                } else if (type == String.class) {
                    field.set(obj, value);
                } else {
                    Object target = GsonUtil.jsonToBean(value, field.getGenericType());
                    field.set(obj, target);
                }
//                field.set(obj, attributes.getValue(attributeName));
                field.setAccessible(false);
            } catch (NoSuchFieldException ex) {
                log.warn("field no exist ,fieldName : " + attributeName + ",value : " + value);
            } catch (IllegalAccessException | IllegalArgumentException | SecurityException ex) {
                throw new SAXException(ex);
            }
        }

    }

}
