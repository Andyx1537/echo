/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util;

import lombok.Getter;
import lombok.Setter;

/**
 * config_dir=E:/doc/策划文档/配置表（匹配）
 * behavior_dir=E:/doc/策划文档/配置表（匹配）/designBehavior/behaviors
 * mapjson_dir=E:/doc/策划文档/配置表（匹配）/mapJson
 * #E:/git-server2/logic-config/designBehavior/behaviors
 *
 * matching_server_id=2 md5_key=tfarc_erirpme
 *
 * server_id=9988
 *
 * social_server_id=4 pressConfig=true
 *
 */
@Getter
@Setter
public class ServerConfig {

    private String config_dir;
    private String behavior_dir;
    private String mapjson_dir;
    private String tfarc_erirpme;
    private int server_id;
    private int social_server_id;
    private int serverType;
    private String pressConfig;
    private String platform_url;
    private String name;
    private String md5_key;
    private String mapjson_style_dir;

}
