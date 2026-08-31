/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.aengine.util;

import java.util.HashMap;
import java.util.Map;

/**
 */
public class HttpResponserTest implements HttpResponser {

    @Override
    public void processHttpResp(String param, String result, String sendIdenty) {
        //
        System.err.println("结果出来啦:" + result + ",identy :" + sendIdenty + ",param : " + param);

    }

    public static void main(String[] agrs) {
//        HttpResponserTest test = new HttpResponserTest();

        String req = "http://192.168.20.98:33215/payResultNotify";
        String json = "{%22code%22:200,%22message%22:%22%E6%93%8D%E4%BD%9C%E6%88%90%E5%8A%9F%22,%22data%22:{%22orderType%22:2,%22orderId%22:%221692054178447331328%22,%22payAmount%22:100,%22gameAmount%22:1000,%22gameGiftAmount%22:10,%22channelId%22:%228%22,%22userId%22:%221689927193902174208%22,%22installationPackId%22:%2257%22,%22packVersion%22:%22esse%20dolor%20labore%20in%22,%22vestPackId%22:%2284%22,%22payType%22:110,%22rechargeTime%22:%222019-07-15%2023:33:09%22,%22rechargeIp%22:%22216.73.8.63%22}}";
        Map<String, String> param = new HashMap<>();
        HttpUtil.sendPost(req, "result=" + json);
    }

}
