package net.runelite.client.plugins.microbot.thieving;

public class ActionTimer {
    private volatile long time;

    public ActionTimer() {
        time = Long.MAX_VALUE;
    }

    public boolean isSet() {
        return time != Long.MAX_VALUE;
    }

    public boolean isTime() {
        return System.currentTimeMillis() > time;
    }

    public int getRemainingTime() {
        return isSet() ? Math.max(0, (int) (time-System.currentTimeMillis())) : -1;
    }

    public void unset() {
        time = Long.MAX_VALUE;
    }

    public void set(long time) {
        this.time = time;
    }

    public void set(int minDelay, int maxDelay) {
        this.time = System.currentTimeMillis();
    }
}
