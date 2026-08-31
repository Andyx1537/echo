/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.ymlReader;

import com.aengine.util.NetConfig;
import com.aengine.util.RedisConfig;
import com.aengine.util.ServerConfig;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
public class YmlConfig {

    public Map<String, NetConfig> netConfigMap = new HashMap<>();
    public Map<String, RedisConfig> redisConfigMap = new HashMap<>();

    public ServerConfig serverConfig = new ServerConfig();

    public List<NetConfig> netConfigs = new ArrayList<>();

    public static void main(String[] args) {
        YmlConfig config = null;
        try {

            Yaml yaml = new Yaml();
            config = yaml.loadAs(YmlConfig.class.getResourceAsStream("/serverConfig.yml"), YmlConfig.class);

//            YamlMapFactoryBean bean = new YamlMapFactoryBean();
//            bean.setResources(new ClassPathResource("serverConfig.yml"));
//            config = Yaml.loadType(YmlConfig.class.getResourceAsStream("serverConfig.yml"), YmlConfig.class);//如果是读入Map,这里不可以写Ma接口，必须写实现
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (config != null) {
            for (NetConfig nf : config.netConfigMap.values()) {

                System.err.println("nf :" + nf.getIp() + ",type:" + nf.getType());
            }

        }
    }
}
