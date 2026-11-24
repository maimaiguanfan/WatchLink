package io.github.watchlink.xposed;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import io.github.watchlink.alarm.probe.NativeUuid;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Worker-only live Clock row/schedule snapshots for occurrence binding and terminal readback. */
final class ClockInventory {
    static final Uri ALARMS = Uri.parse("content://com.coloros.alarmclock.alarmclock/alarm");
    static final Uri SCHEDULES = Uri.parse("content://com.coloros.alarmclock.alarmclock/schedules");

    static final class Row {
        long id;
        int snooze;
        String token, full;
    }

    static final class Schedule {
        long id, parent, time;
        int state, snoozeCount;
        String hash;
    }

    final Map<Long, Row> rows = new HashMap<>();
    final Map<Long, Schedule> schedules = new HashMap<>();

    static ClockInventory readLive(Context context) throws Exception {
        ClockInventory result = new ClockInventory();
        String[] parents = {"_id", "uuid", "snoozeTime"};
        try (Cursor cursor = context.getContentResolver().query(ALARMS, parents, null, null, null)) {
            if (cursor == null || cursor.getCount() > 450) throw new IllegalStateException("PHONE_INVENTORY_LIMIT");
            int uuid = cursor.getColumnIndexOrThrow("uuid");
            while (cursor.moveToNext()) {
                Row row = new Row();
                row.id = liveInteger(cursor, "_id");
                row.token = cursor.isNull(uuid) ? null : NativeUuid.digest(cursor.getString(uuid));
                long minutes = liveInteger(cursor, "snoozeTime");
                row.snooze = minutes >= 5 && minutes <= 30 && minutes % 5 == 0 ? (int) minutes : 0;
                row.full = hash(cursor);
                if (row.id <= 0 || result.rows.put(row.id, row) != null)
                    throw new IllegalStateException("PHONE_DUPLICATE_ROW");
            }
        }
        String[] schedules = {"_id", "alarm_id", "alarmtime", "alarm_state", "snooze_time"};
        try (Cursor cursor = context.getContentResolver().query(SCHEDULES, schedules, null, null, null)) {
            if (cursor == null || cursor.getCount() > 1024) throw new IllegalStateException("PHONE_SCHEDULE_LIMIT");
            int snoozes = cursor.getColumnIndexOrThrow("snooze_time");
            while (cursor.moveToNext()) {
                Schedule row = new Schedule();
                row.id = liveInteger(cursor, "_id");
                row.parent = liveInteger(cursor, "alarm_id");
                row.time = liveInteger(cursor, "alarmtime");
                row.state = Math.toIntExact(liveInteger(cursor, "alarm_state"));
                row.snoozeCount = cursor.isNull(snoozes) ? -1 : Math.toIntExact(liveInteger(cursor, "snooze_time"));
                row.hash = hash(cursor);
                if (row.id <= 0 || !result.rows.containsKey(row.parent) || result.schedules.put(row.id, row) != null)
                    throw new IllegalStateException("PHONE_INVALID_SCHEDULE");
            }
        }
        return result;
    }

    private static long liveInteger(Cursor cursor, String name) {
        int index = cursor.getColumnIndexOrThrow(name);
        if (cursor.getType(index) != Cursor.FIELD_TYPE_INTEGER) throw new IllegalStateException("PHONE_COLUMN_TYPE");
        return cursor.getLong(index);
    }

    private static String hash(Cursor cursor) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String[] columns = cursor.getColumnNames().clone();
        Arrays.sort(columns);
        for (String name : columns) {
            int i = cursor.getColumnIndexOrThrow(name);
            int type = cursor.getType(i);
            feed(digest, name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) type);
            switch (type) {
                case Cursor.FIELD_TYPE_NULL -> {}
                case Cursor.FIELD_TYPE_INTEGER -> feed(digest, ByteBuffer.allocate(8).putLong(cursor.getLong(i)).array());
                case Cursor.FIELD_TYPE_FLOAT -> feed(digest, ByteBuffer.allocate(8).putDouble(cursor.getDouble(i)).array());
                case Cursor.FIELD_TYPE_STRING -> feed(digest, cursor.getString(i).getBytes(StandardCharsets.UTF_8));
                case Cursor.FIELD_TYPE_BLOB -> feed(digest, cursor.getBlob(i));
                default -> throw new IllegalStateException("PHONE_COLUMN_TYPE");
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    private static void feed(MessageDigest digest, byte[] bytes) {
        if (bytes.length > 1048576) throw new IllegalStateException("PHONE_COLUMN_LIMIT");
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    Set<Long> scheduleIds(long parent) {
        Set<Long> out = new HashSet<>();
        for (Schedule schedule : schedules.values()) {
            if (schedule.parent == parent) out.add(schedule.id);
        }
        return out;
    }

    boolean same(ClockInventory other) {
        if (rows.size() != other.rows.size() || !schedules.keySet().equals(other.schedules.keySet())) return false;
        for (Row row : rows.values()) {
            Row matching = other.rows.get(row.id);
            if (matching == null || !row.full.equals(matching.full)) return false;
        }
        for (Schedule schedule : schedules.values()) {
            Schedule matching = other.schedules.get(schedule.id);
            if (matching == null || !schedule.hash.equals(matching.hash)) return false;
        }
        return true;
    }
}
