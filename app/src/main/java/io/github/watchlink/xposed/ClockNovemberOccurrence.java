package io.github.watchlink.xposed;

/** Values copied from one native alarm instance. Used only inside the Clock process. */
final class ClockNovemberOccurrence {
    final long schedule, parent, time;
    final int snoozes;
    final String uuid;

    ClockNovemberOccurrence(long schedule, long parent, long time, int snoozes, String uuid) {
        this.schedule = schedule;
        this.parent = parent;
        this.time = time;
        this.snoozes = snoozes;
        this.uuid = uuid;
    }

    boolean matches(long scheduleId, long parentId, String nativeUuid) {
        return schedule > 0 && parent > 0 && scheduleId == schedule && parentId == parent
                && uuid != null && uuid.equals(nativeUuid);
    }

    String key() throws Exception {
        return io.github.watchlink.validation.PhoneRingWire.occurrence(uuid, parent, schedule, time, snoozes);
    }

    boolean sameFiring(ClockNovemberOccurrence other) {
        return other != null && matches(other.schedule, other.parent, other.uuid)
                && time == other.time && snoozes == other.snoozes;
    }

    boolean ringing(ClockInventory.Schedule row) {
        return row != null && row.id == schedule && row.parent == parent && time > 0 && row.time == time
                && row.snoozeCount == snoozes && snoozes >= 0;
    }

    boolean actionableRow(ClockInventory.Schedule row) {
        return ringing(row) && row.state == 0;
    }

    boolean terminal(boolean dismissed, ClockInventory.Schedule row, long actionTime) {
        if (dismissed) return row == null;
        return row != null && row.id == schedule && row.parent == parent && row.state == 1
                && snoozes >= 0 && snoozes < Integer.MAX_VALUE && row.snoozeCount == snoozes + 1
                && row.time > actionTime && row.time > time;
    }
}
