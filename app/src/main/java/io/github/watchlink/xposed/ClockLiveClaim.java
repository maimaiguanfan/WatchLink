package io.github.watchlink.xposed;

import io.github.watchlink.validation.ClockActionWire.Command;

/** Arbitration for a single native ring. A phone action keeps its normal native behavior. */
final class ClockLiveClaim {
    private Command command;
    private boolean transitioning;
    private boolean remoteEntered;
    private boolean sameActionReentry;
    private boolean conflictingAction;
    private boolean reported;

    /** Consume before invoking the original branch. Its own nested D/I calls are native work. */
    static Command takeDispatch(ThreadLocal<Command> dispatching) {
        Command request = dispatching.get();
        if (request != null) dispatching.remove();
        return request;
    }

    synchronized boolean reserve(Command request) {
        if (transitioning || command != null) return false;
        command = request;
        return true;
    }

    synchronized boolean enter(Command remote, String action) {
        if (remote != null && (remote != command || !action.equals(remote.action()) || transitioning)) return false;
        if (remoteEntered) {
            // D/I entry alone does not prove a second change. For example I may return early
            // when the same g2 already carries snoozed state. The original schedule readback
            // independently requires exactly one increment or removal of this occurrence.
            if (action.equals(command.action())) sameActionReentry = true;
            else conflictingAction = true;
        }
        transitioning = true;
        if (remote != null) remoteEntered = true;
        return true;
    }

    synchronized boolean alreadyReceived(Command other) { return other.equals(command); }
    synchronized boolean enteredByWatch() { return remoteEntered; }

    synchronized Command takeResult() {
        if (reported || command == null) return null;
        reported = true;
        return command;
    }

    synchronized boolean proves(boolean dismissed) {
        return remoteEntered && !conflictingAction && dismissed == "DISMISS".equals(command.action());
    }

    synchronized String nativeOverlap() {
        if (conflictingAction) return "CONFLICT";
        return sameActionReentry ? "SAME_ACTION" : "NONE";
    }
}
