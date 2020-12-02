package org.apache.rocketmq.common;

/**
 * @Author shisheng.wang
 * @Email 17770845990@163.com
 * @Date 2020/12/2 10:57
 * @Description des
 */
public class GetPathUtils {

    /**
     * 根据本地项目路径获取path
     * @return
     */
    public static String getRocketMQHomePath () {
        String dir = System.getProperty("user.dir");
        String path = dir.substring(0, dir.indexOf("rocketmq") + 8);
        return path;
    }
}
