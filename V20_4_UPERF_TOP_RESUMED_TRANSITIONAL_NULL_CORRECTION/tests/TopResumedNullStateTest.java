package com.zui.server.control;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TopResumedNullStateTest {
    private static final class Harness {
        final TopResumedNullState state = new TopResumedNullState();
        final Map<String, String> rules = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        long generation;
        boolean interactive = true;
        String lastMode = "";

        Harness() {
            rules.put("game.performance", "performance");
            rules.put("game.fast", "fast");
        }

        long valid(String packageName) {
            long token = ++generation;
            check(state.acceptValid(token, packageName, 0), "valid callback rejected");
            apply();
            return token;
        }

        long nullCallback() {
            long token = ++generation;
            check(state.deferNull(token), "null callback rejected");
            return token;
        }

        int revalidate(long token, String packageName) {
            int result = state.revalidate(token, packageName, 0);
            if (result == TopResumedNullState.REVALIDATE_CHANGED
                    || result == TopResumedNullState.REVALIDATE_NULL_CONFIRMED) {
                apply();
            }
            return result;
        }

        void interactive(boolean value) {
            interactive = value;
            apply();
        }

        private void apply() {
            String mode = interactive
                    ? rules.containsKey(state.stablePackage())
                    ? rules.get(state.stablePackage()) : "balance"
                    : "powersave";
            if (!mode.equals(lastMode)) {
                writes.add(mode);
                lastMode = mode;
            }
        }
    }

    private static void packageNullPackageFixture() {
        Harness h = new Harness();
        h.valid("game.performance");
        long token = h.nullCallback(); // virtual t=5 ms; service revalidates at t=69 ms
        equal(TopResumedNullState.REVALIDATE_SAME,
                h.revalidate(token, "game.performance"), "game revalidation");
        equal(Arrays.asList("performance"), h.writes, "no transient balance write");
        equal("game.performance", h.state.stablePackage(), "stable game");
        pass("PACKAGE_NULL_PACKAGE_FIXTURE");
    }

    private static void threeActivityGameFixture() {
        Harness h = new Harness();
        for (String ignored : Arrays.asList("KrSdkSplash", "Ue4Splash", "GameActivity")) {
            h.valid("game.performance");
            long token = h.nullCallback();
            equal(TopResumedNullState.REVALIDATE_SAME,
                    h.revalidate(token, "game.performance"), "activity revalidation");
        }
        equal(Arrays.asList("performance"), h.writes, "three-activity writes");
        equal("game.performance", h.state.stablePackage(), "final game package");
        equal(3, h.state.revalidateCount(), "three revalidations");
        pass("THREE_ACTIVITY_GAME_FIXTURE");
    }

    private static void aToBFixture() {
        Harness h = new Harness();
        h.valid("game.fast");
        long oldToken = h.nullCallback();
        h.valid("normal.app");
        equal(TopResumedNullState.REVALIDATE_STALE,
                h.revalidate(oldToken, "game.fast"), "old token must be stale");
        equal(Arrays.asList("fast", "balance"), h.writes, "A to B writes");
        equal("normal.app", h.state.stablePackage(), "B must win");
        pass("A_TO_B_FIXTURE");
    }

    private static void trueNullFixture() {
        Harness h = new Harness();
        h.valid("game.performance");
        long token = h.nullCallback();
        equal(TopResumedNullState.REVALIDATE_NULL_CONFIRMED,
                h.revalidate(token, ""), "persistent null");
        equal(Arrays.asList("performance", "balance"), h.writes, "bounded global fallback");
        equal("", h.state.stablePackage(), "confirmed empty authority");
        check(!h.state.pendingNull(), "null remained pending");
        pass("TRUE_NULL_FIXTURE");
    }

    private static void staleGenerationFixture() {
        Harness h = new Harness();
        h.valid("game.performance");
        long oldToken = h.nullCallback();
        h.valid("game.fast");
        equal(TopResumedNullState.REVALIDATE_STALE,
                h.revalidate(oldToken, ""), "stale null revalidation");
        equal("game.fast", h.state.stablePackage(), "new valid package retained");
        equal(Arrays.asList("performance", "fast"), h.writes, "stale token wrote mode");
        pass("STALE_GENERATION_FIXTURE");
    }

    private static void screenOffFixture() {
        Harness h = new Harness();
        h.valid("game.performance");
        long token = h.nullCallback();
        h.interactive(false);
        equal(TopResumedNullState.REVALIDATE_SAME,
                h.revalidate(token, "game.performance"), "screen-off revalidation");
        equal("powersave", h.lastMode, "screen off must win");
        equal(Arrays.asList("performance", "powersave"), h.writes, "screen-off writes");
        pass("SCREEN_OFF_FIXTURE");
    }

    private static void screenOnFixture() {
        Harness h = new Harness();
        h.valid("game.performance");
        long token = h.nullCallback();
        h.interactive(false);
        h.revalidate(token, "");
        h.interactive(true);
        equal(Arrays.asList("performance", "powersave", "balance"),
                h.writes, "screen-on current authority");
        pass("SCREEN_ON_FIXTURE");
    }

    private static void dedupFixture() {
        Harness h = new Harness();
        h.valid("game.performance");
        h.valid("game.performance");
        long token = h.nullCallback();
        h.revalidate(token, "game.performance");
        equal(Arrays.asList("performance"), h.writes, "same-target dedup");
        pass("DEDUP_FIXTURE");
    }

    private static void pass(String name) {
        System.out.println(name + "=PASS");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        packageNullPackageFixture();
        threeActivityGameFixture();
        aToBFixture();
        trueNullFixture();
        staleGenerationFixture();
        screenOffFixture();
        screenOnFixture();
        dedupFixture();
        System.out.println("TOP_RESUMED_TRANSITIONAL_NULL_STATE_MACHINE=PASS");
    }
}
