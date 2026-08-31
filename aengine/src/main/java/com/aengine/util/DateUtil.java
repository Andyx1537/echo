package com.aengine.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import static java.util.Calendar.*;

/**
 * 日期工具类
 *
 */
public class DateUtil {

    private static final Logger LOG = LoggerFactory.getLogger(DateUtil.class);

    private static final String NORM_DATE_PATTERN = "yyyy-MM-dd";

    private static final String NORM_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private static final String PURE_DATE_PATTERN = "yyyyMMdd";

    private static final String PURE_DATETIME_PATTERN = "yyyyMMddHHmmss";

    /**
     * 一秒钟的毫秒数
     */
    public static final int ONE_SECOND_MILLISECOND = 1000;

    /**
     * 一分钟的毫秒数
     */
    public static final int ONE_MINUTE_MILLISECOND = 60 * ONE_SECOND_MILLISECOND;

    /**
     * 一小时的毫秒数
     */
    public static final int ONE_HOUR_MILLISECOND = 60 * ONE_MINUTE_MILLISECOND;

    /**
     * 一天的毫秒数
     */
    public static final int ONE_DAY_MILLISECOND = 24 * ONE_HOUR_MILLISECOND;

    /**
     * 一分钟的秒数
     */
    public static final int ONE_MINUTE_SECOND = ONE_MINUTE_MILLISECOND / ONE_SECOND_MILLISECOND;

    /**
     * 1小时的秒数
     */
    public static final long ONE_HOUR_SECOND = ONE_HOUR_MILLISECOND / ONE_SECOND_MILLISECOND;

    /**
     * 1天的秒数
     */
    public static final long ONE_DAY_SECOND = ONE_DAY_MILLISECOND / ONE_SECOND_MILLISECOND;

    /**
     * 判断两个时间是不是同一天
     *
     * @param time1
     * @param time2
     * @return
     */
    public static boolean isSameDate(Date time1, Date time2) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(time1);
        int year1 = cal.get(Calendar.YEAR);
        int month1 = cal.get(Calendar.MONTH);
        int date1 = cal.get(Calendar.DAY_OF_YEAR);

        cal.setTime(time2);
        int year2 = cal.get(Calendar.YEAR);
        int month2 = cal.get(Calendar.MONTH);
        int date2 = cal.get(Calendar.DAY_OF_YEAR);

        return year1 == year2 && month1 == month2 && date1 == date2;
    }

    public static boolean isSameWeek(Date d1, Date d2) {
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.setTime(d1);
        int y1 = cal.get(Calendar.YEAR);
        int w1 = cal.get(Calendar.WEEK_OF_YEAR);

        cal.setTime(d2);
        int y2 = cal.get(Calendar.YEAR);
        int w2 = cal.get(Calendar.WEEK_OF_YEAR);

        return y1 == y2 && w1 == w2;
    }

    public static Date add(Date theDate, int addHours, int addMinutes, int addSecond) {
        if (theDate == null) {
            return null;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(theDate);

        cal.add(HOUR_OF_DAY, addHours);
        cal.add(MINUTE, addMinutes);
        cal.add(SECOND, addSecond);

        return cal.getTime();
    }

    public static Date add(Date theDate, int days, int addHours, int addMinutes, int addSecond) {
        if (theDate == null) {
            return null;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(theDate);

        cal.add(Calendar.DATE, days);
        cal.add(HOUR_OF_DAY, addHours);
        cal.add(MINUTE, addMinutes);
        cal.add(SECOND, addSecond);

        return cal.getTime();
    }

    /**
     * 获得某一时间的0点
     *
     * @param theDate 需要计算的时间
     */
    public static Date getDate0AM(Date theDate) {
        if (theDate == null) {
            return null;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(theDate);
        return new GregorianCalendar(cal.get(YEAR), cal.get(MONTH), cal.get(DAY_OF_MONTH)).getTime();
    }

    /**
     * 计算2个时间相差的天数,这个方法算的是2个零点时间的绝对时间(天数)
     *
     * @param startDate 起始时间
     * @param endDate   结束时间
     */
    public static int calcIntervalDay(Date startDate, Date endDate) {
        if (startDate == null || endDate == null) {
            return 0;
        }
        Date startDate0AM = getDate0AM(startDate);
        Date endDate0AM = getDate0AM(endDate);
        long v1 = startDate0AM.getTime() - endDate0AM.getTime();

        BigDecimal bd1 = new BigDecimal(Math.abs(v1));
        BigDecimal bd2 = new BigDecimal(ONE_DAY_MILLISECOND);

        int days = (int) bd1.divide(bd2, 0, BigDecimal.ROUND_UP).doubleValue();
        return days;
    }

    /**
     * 计算两个日期相差的月份
     *
     * @param d1
     * @param d2
     * @return
     */
    public static int calcIntervalMonth(Date d1, Date d2) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(d1);
        int year1 = cal.get(Calendar.YEAR);
        int month1 = cal.get(Calendar.MONTH);

        cal.setTime(d2);
        int year2 = cal.get(Calendar.YEAR);
        int month2 = cal.get(Calendar.MONTH);

        return Math.abs((year1 - year2) * 12 + month1 - month2);
    }

    public static long nextZeroTime(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR, 24);
        return cal.getTimeInMillis();
    }

    public static Date now() {
        return new Date();
    }

    public static int getYear(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.YEAR);
    }

    public static int getMonth(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.MONTH);
    }

    public static int getDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.DATE);
    }

    public static String toDateStr() {
        return toDateStr(now());
    }

    public static String toDateStr(Date date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(NORM_DATE_PATTERN);
        return dateFormat.format(date);
    }

    public static String toDateTimeStr() {
        return toDateTimeStr(now());
    }

    public static String toDateTimeStr(Date date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(NORM_DATETIME_PATTERN);
        return dateFormat.format(date);
    }

    public static int toDateInt() {
        return toDateInt(now());
    }

    public static int toDateInt(Date date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(PURE_DATE_PATTERN);
        return Integer.parseInt(dateFormat.format(date));
    }

    public static long toDateTimeLong() {
        return toDateTimeLong(now());
    }

    public static long toDateTimeLong(Date date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(PURE_DATETIME_PATTERN);
        return Long.parseLong(dateFormat.format(date));
    }

    public static Date parseStr(String str, String pattern) {
        if (StringUtils.isEmpty(str)) {
            return null;
        }
        Date date = null;
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat(pattern);
            date = dateFormat.parse(str);
        } catch (ParseException e) {
            LOG.error(e.getMessage(), e);
        }
        return date;
    }

    public static Date fromDateStr(String str) {
        return parseStr(str, NORM_DATE_PATTERN);
    }

    public static Date fromDateTimeStr(String str) {
        return parseStr(str, NORM_DATETIME_PATTERN);
    }

    public static Date fromDateInt(int dateInt) {
        return parseStr(String.valueOf(dateInt), PURE_DATE_PATTERN);
    }

    public static Date fromDateTimeLong(long dateTimeLong) {
        return parseStr(String.valueOf(dateTimeLong), PURE_DATETIME_PATTERN);
    }

    /**
     * 判断某个日期是否在某个时间段内
     *
     * @param begin 开始时间
     * @param end   结束时间
     * @param date  要判断的时间
     * @return true 在时间段内 false 不在时间段内
     */
    public static boolean isBetween(Date begin, Date end, Date date) {
        return begin.before(date) && end.after(date);
    }

    /**
     * 获取两个时间之间的天数差
     *
     * @param begin 开始时间
     * @param end   结束时间
     * @return 天数差
     */
    public static long getDaysBetween(Date begin, Date end) {
        return (end.getTime() - begin.getTime()) / (1000 * 60 * 60 * 24);
    }

    /**
     * 获取两个时间之间的小时差
     *
     * @param begin 开始时间
     * @param end   结束时间
     * @return 小时差
     */
    public static long getHoursBetween(Date begin, Date end) {
        return (end.getTime() - begin.getTime()) / (1000 * 60 * 60);
    }

    /**
     * 获取两个时间之间的分钟差
     *
     * @param begin 开始时间
     * @param end   结束时间
     * @return 分钟差
     */
    public static long getMinutesBetween(Date begin, Date end) {
        return (end.getTime() - begin.getTime()) / (1000 * 60);
    }

    /**
     * 获取两个时间之间的秒数差
     *
     * @param begin 开始时间
     * @param end   结束时间
     * @return 秒数差
     */
    public static long getSecondsBetween(Date begin, Date end) {
        return (end.getTime() - begin.getTime()) / 1000;
    }

    /**
     * 获取某个日期的当日开始时间
     *
     * @param date 日期
     * @return 当日开始时间
     */
    public static Date getDateBegin(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = cal.get(YEAR);
        int month = cal.get(MONTH);
        int day = cal.get(DATE);
        cal.set(year, month, day, 0, 0, 0);
        return cal.getTime();
    }

    /**
     * 获取某个日期的当日结束时间
     *
     * @param date 日期
     * @return 当日结束时间
     */
    public static Date getDateEnd(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = cal.get(YEAR);
        int month = cal.get(MONTH);
        int day = cal.get(DATE);
        cal.set(year, month, day, 23, 59, 59);
        return cal.getTime();
    }

    public static Date getDate(Date date, int addDay, int hour, int minute, int second) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(DATE, addDay);
        cal.set(HOUR_OF_DAY, hour);
        cal.set(MINUTE, minute);
        cal.set(SECOND, second);
        return cal.getTime();
    }

    /**
     * 获取当前周
     *
     * @return
     */
    public static int getWeekOfYear() {
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setTime(new Date());
        return calendar.get(Calendar.WEEK_OF_YEAR);
    }

    /**
     * 获取上一周的周数
     *
     * @return
     */
    public static int getLastWeekOfYear() {
        Date dateBefore = getDateBefore(new Date(), 7);
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setTime(dateBefore);
        return calendar.get(Calendar.WEEK_OF_YEAR);
    }

    public static Date getLastWeekDate() {
        Date dateBefore = getDateBefore(new Date(), 7);
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setTime(dateBefore);
        return calendar.getTime();
    }

    public static void main(String[] args) {

        System.out.println(getCurrentDayStr(new Date()));
    }

    /**
     * 获取本周的最后一天
     *
     * @param date
     * @return
     */
    public static Date getLastDayOfWeek(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setTime(date);
        calendar.set(Calendar.DAY_OF_WEEK,
                calendar.getFirstDayOfWeek() + 6); // Saturday
        int year = calendar.get(YEAR);
        int month = calendar.get(MONTH);
        int day = calendar.get(DATE);
        calendar.set(year, month, day, 23, 59, 59);
        return calendar.getTime();
    }

    /**
     * 获取几天前的日期
     *
     * @param date
     * @param days
     * @return
     */
    public static Date getDateBefore(Date date, int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -days);
        return cal.getTime();
    }

    /**
     * 是否是周几
     *
     * @param day
     * @return
     */
    public static boolean isDay(int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        return calendar.get(DAY_OF_WEEK) == day;
    }

    public static String getCurrentDayStr(Date date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(PURE_DATE_PATTERN);
        return dateFormat.format(date);
    }
}
