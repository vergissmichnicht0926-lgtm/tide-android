package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class MomentTest {
    private static long at(int daysAgo, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void recentMomentsUseRelativeLabels() {
        long now = at(0, 20, 0);
        assertEquals("刚刚", Moment.timeLabel(now - 20_000L, now));
        assertEquals("5 分钟前", Moment.timeLabel(now - 5 * 60_000L, now));
        assertEquals("2 小时前", Moment.timeLabel(now - 2 * 3_600_000L, now));
    }

    @Test
    public void olderMomentsUseCalendarLabels() {
        long now = at(0, 20, 0);
        assertEquals("昨天 10:05", Moment.timeLabel(at(1, 10, 5), now));
        String older = Moment.timeLabel(at(3, 9, 30), now);
        assertTrue(older.contains("月") && older.endsWith("09:30"));
    }

    @Test
    public void missingTimestampRendersNothing() {
        assertEquals("", Moment.timeLabel(0L, System.currentTimeMillis()));
    }
}
