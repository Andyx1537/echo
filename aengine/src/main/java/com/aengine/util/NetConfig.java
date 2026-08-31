/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util;

import lombok.Getter;
import lombok.Setter;

/**
 *
 *
 *
 * outer_ip=192.168.18.133 outer_port=33211 outer_io_threads=4
 * outer_logic_min_threads=4 outer_logic_max_threads=8 一个网络连接所需要配置的字段信息
 *
 */
@Getter
@Setter
public class NetConfig {

    public String ip; 
    public int port;
    public int min_threads;
    public int max_threads;
    public int io_threads = 4;
    public String name;
    public String type;

}
