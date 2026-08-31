package com.aengine.util.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 线程执行的任务统计
 */
public final class MethodCalledStatistic {

	public static int threshold = 100;

    private static final Logger log = LoggerFactory.getLogger(MethodCalledStatistic.class);

    private static final ConcurrentMap<Class<?>, ClassStats> map = new ConcurrentHashMap<>();

    /**
     * 类的统计信息
     */
    private static final class ClassStats {
        private final String className;

        private final ConcurrentMap<String, MethodStats> methodStats = new ConcurrentHashMap<>();

        private ClassStats(String className) {
            this.className = className;
        }

        private MethodStats getMethodStats(String methodName) {
            MethodStats m = methodStats.get(methodName);
            if (m != null)
                return m;
            m = new MethodStats(className, methodName);
            MethodStats old = methodStats.putIfAbsent(methodName, m);
            if (old != null)
                return old;
            else
                return m;
        }
    }

    /**
     * 方法的统计信息
     */
    private static final class MethodStats {
        private final String className;
        private final String methodName;

        private long count = 0;
        private long total = 0;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;

        private MethodStats(String className, String methodName) {
            this.methodName = methodName;
            this.className = className;
        }

        private synchronized void stats(long time) {
            count++;
            total += time;
            min = Math.min(min, time);
            max = Math.max(max, time);
        }

        @Override
        public synchronized String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("class:").append(className)
                    .append("#").append(methodName).append(":")
                    .append(count).append(" called,")
                    .append("avg:").append(count==0?0:total/count).append("ms,")
                    .append("min:").append(min==Long.MAX_VALUE?0:min).append("ms,")
                    .append("max:").append(max==Long.MIN_VALUE?0:max).append("ms.");
            return sb.toString();
        }
    }

    /**
     * 获取指定类的统计信息，不存在时自动创建
     *
     * @param clazz     类
     * @return      类的统计信息
     */
    private static ClassStats getClassStats(Class<?> clazz) {
        ClassStats classStats = map.get(clazz);
        if (classStats != null)
            return classStats;
        classStats = new ClassStats(clazz.getName());
        ClassStats old = map.putIfAbsent(clazz, classStats);
        if (old != null)
            return old;
        else
            return classStats;
    }

    /**
     * 填入方法调用的统计信息
     *
     * @param clazz                 类
     * @param methodName            方法名
     * @param runTime               执行时间
     */
    public static void handleStats(Class<?> clazz, String methodName, long runTime) {
        getClassStats(clazz).getMethodStats(methodName).stats(runTime);
        if (runTime > threshold && log.isWarnEnabled())
        	log.warn(clazz.getName() + "#" + methodName + ":" + runTime + "ms");
    }

    /**
     * 将统计信息写入文件
     *
     * @param file      指定的文件
     */
    public static void dump(File file) {
        List<MethodStats> list = new LinkedList<>();
        for (ClassStats classStats : map.values()) {
            list.addAll(classStats.methodStats.values());
        }
        list.sort(new Comparator<MethodStats>() {
            @Override
            public int compare(MethodStats o1, MethodStats o2) {
                long avg1 = o1.count == 0 ? 0 : o1.total / o1.count;
                long avg2 = o2.count == 0 ? 0 : o2.total / o2.count;

                if (avg1 > avg2)
                    return 1;
                else if (avg1 < avg2)
                    return -1;
                else {
                    if (o1.max > o2.max)
                        return 1;
                    else if (o1.max < o2.max)
                        return -1;
                    else {
                        if (o1.min > o2.min)
                            return 1;
                        else if (o1.min < o2.min)
                            return -1;
                        else {
                            if (o1.count > o2.count)
                                return 1;
                            else if (o1.count < o2.count)
                                return -1;
                            else
                                return o1.className.hashCode() - o2.className.hashCode();
                        }
                    }
                }
            }
        });
        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(file);
            fileWriter.append("====statistic of method called begin(")
                    .append(new Date().toString())
                    .append(")====\n");
            for (MethodStats methodStats : list)
                fileWriter.append(methodStats.toString()).append("\n");
            fileWriter.append("====statistic of method called end(")
                    .append(new Date().toString())
                    .append(")=====\n");
            fileWriter.flush();
        } catch (IOException e) {
            log.error("write file failed", e);
        } finally {
            if (fileWriter != null) {
                try {
                    fileWriter.close();
                } catch (IOException e) {
                    log.error("close file writer failed", e);
                }
            }
        }
    }
}
