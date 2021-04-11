package com.mocyx.basic_client.config;

public class Config {
    //只代理本app的流量
    public static boolean testLocal = false;
    //配置dns
    public static String dns = "114.114.114.114";
    //io日志
    @Deprecated
    public static boolean logRW = false;
    //ack日志
    @Deprecated
    public static boolean logAck = false;
}





