package com.aengine.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @version 1.0
 */
public class HttpUtil {

    private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);

    private static ExecutorService pool = Executors.newFixedThreadPool(4);

    private static final String METHOD_POST = "post";
    private static final String METHOD_GET = "get";

    private static ConcurrentHashMap<Future<String>, HttpSendTask> futureMap = new ConcurrentHashMap<>();

    static {

        new Thread() {

            public void run() {
                try {

                    while (true) {
                        for (Entry<Future<String>, HttpSendTask> entry : futureMap.entrySet()) {
                            Future<String> future = entry.getKey();
                            if (!future.isDone()) {
                                continue;
                            }
                            futureMap.remove(future);
                            String futureResult = future.get();
                            HttpSendTask responserTask = entry.getValue();

                            if (responserTask.resp == null) {
                                continue;
                            }
                            responserTask.resp.processHttpResp(responserTask.param, futureResult, responserTask.identy);
                        }

                        Thread.currentThread().sleep(100);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.start();

    }

    /**
     * 发送get http 请求 且不需要结果调用
     */
    public static void sendHttpGet(String url, String param) {
        sendHttpReq(url, param, METHOD_GET, null, null);
    }

    public static void sendHttpPost(String url, String param) {
        sendHttpReq(url, param, METHOD_POST, null, null);
    }

    public static void sendHttpReq(String url, String param, String method) {
        sendHttpReq(url, param, method, null, null);
    }

    /**
     * url
     * param
     * identyStr
     **/
    public static void sendHttpReqGet(String url, String param, String identyStr, HttpResponser respProcess) {
        sendHttpReq(url, param, METHOD_GET, identyStr, respProcess);
    }

    public static void sendHttpReqPost(String url, String param, String identyStr, HttpResponser respProcess) {
        sendHttpReq(url, param, METHOD_POST, identyStr, respProcess);
    }

    public static void sendHttpReq(String url, String param, String method, String identyStr, HttpResponser respProcess) {
        HttpSendTask task = new HttpSendTask(url, param, method, identyStr, respProcess);
        futureMap.put(pool.submit(task), task);
    }

    private static class HttpSendTask implements Callable<String> {

        private String url;
        private String param;
        private String method;
        private String identy;
        private HttpResponser resp;

        public HttpSendTask(String url, String param, String method, String identyStr, HttpResponser resp) {
            this.url = url;
            this.param = param;
            this.method = method;
            this.identy = identyStr;
            this.resp = resp;
        }

        public String call() {
            PrintWriter out = null;
            BufferedReader in = null;
            String result = "";
            try {
                switch (method) {
                    case METHOD_GET: {
                        String urlNameString = url + "?" + param;
                        URL realUrl = new URL(urlNameString);
                        // 打开和URL之间的连接
                        URLConnection connection = realUrl.openConnection();
                        // 设置通用的请求属性
                        connection.setRequestProperty("accept", "*/*");
                        connection.setRequestProperty("connection", "Keep-Alive");
                        connection.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
                        // 建立实际的连接
                        connection.connect();
                        // 获取所有响应头字段
                        // Map<String, List<String>> map = connection.getHeaderFields();
                        // 定义 BufferedReader输入流来读取URL的响应
                        in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        String line;
                        while ((line = in.readLine()) != null) {
                            result += line;
                        }
                    }
                    break;
                    case METHOD_POST: {
                        URL realUrl = new URL(url);
                        // 打开和URL之间的连接
                        URLConnection conn = realUrl.openConnection();
                        // 设置通用的请求属性
                        conn.setRequestProperty("accept", "*/*");
                        conn.setRequestProperty("connection", "Keep-Alive");
                        conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
                        // 发送POST请求必须设置如下两行
                        conn.setDoOutput(true);
                        conn.setDoInput(true);
                        // 获取URLConnection对象对应的输出流
                        out = new PrintWriter(conn.getOutputStream());
                        // 发送请求参数
                        out.print(param);
                        // flush输出流的缓冲
                        out.flush();
                        // 定义BufferedReader输入流来读取URL的响应
                        in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        String line;
                        while ((line = in.readLine()) != null) {
                            result += line;
                        }
                    }
                }

            } catch (Exception ex) {
                log.error(ex.getMessage());
            } finally {

                try {
                    if (out != null) {
                        out.close();
                    }
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

            }
            return result;
        }

    }

    /**
     * 向指定URL发送GET方法的请求
     * 同步等待结果
     *
     * @param url   发送请求的URL
     * @param param 请求参数，请求参数应该是 name1=value1&name2=value2 的形式。
     * @return URL 所代表远程资源的响应结果
     */
    public static String sendGet(String url, String param) {
        String result = "";
        BufferedReader in = null;
        try {
            String urlNameString = url + "?" + param;
            URL realUrl = new URL(urlNameString);
            // 打开和URL之间的连接
            URLConnection connection = realUrl.openConnection();
            // 设置通用的请求属性
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("connection", "Keep-Alive");
            connection.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 建立实际的连接
            connection.connect();
            // 获取所有响应头字段
            // Map<String, List<String>> map = connection.getHeaderFields();
            // 定义 BufferedReader输入流来读取URL的响应
            in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result += line;
            }
        } catch (Exception e) {
            // System.out.println("发送GET请求出现异常！" + e);
            // e.printStackTrace();
            log.error(e.getMessage());
        } // 使用finally块来关闭输入流
        finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }
        return result;
    }


    public static String sendPostJson(String url, String param) {
        PrintWriter out = null;
        BufferedReader in = null;
        String result = "";
        try {
            URL realUrl = new URL(url);
            // 打开和URL之间的连接
            URLConnection conn = realUrl.openConnection();
            // 设置通用的请求属性
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            conn.setRequestProperty("Content-Type", "application/json");
            // 发送POST请求必须设置如下两行
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // flush输出流的缓冲
            out.flush();
            // 定义BufferedReader输入流来读取URL的响应
            in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result += line;
            }
        } catch (Exception e) {
            // System.out.println("发送 POST 请求出现异常！" + e);
            // e.printStackTrace();
            log.error(e.getMessage());
        } // 使用finally块来关闭输出流、输入流
        finally {
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return result;
    }

    /**
     * 向指定 URL 发送POST方法的请求
     * 同步等待结果
     *
     * @param url   发送请求的 URL
     * @param param 请求参数，请求参数应该是 name1=value1&name2=value2 的形式。
     * @return 所代表远程资源的响应结果
     */
    public static String sendPost(String url, String param) {
        PrintWriter out = null;
        BufferedReader in = null;
        String result = "";
        try {
            URL realUrl = new URL(url);
            // 打开和URL之间的连接
            URLConnection conn = realUrl.openConnection();
            // 设置通用的请求属性
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 发送POST请求必须设置如下两行
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // flush输出流的缓冲
            out.flush();
            // 定义BufferedReader输入流来读取URL的响应
            in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result += line;
            }
        } catch (Exception e) {
            // System.out.println("发送 POST 请求出现异常！" + e);
            // e.printStackTrace();
            log.error(e.getMessage());
        } // 使用finally块来关闭输出流、输入流
        finally {
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return result;
    }
}
