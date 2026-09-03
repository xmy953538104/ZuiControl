package com.zui.server.control;

final class TopResumedNullState {
    static final int REVALIDATE_STALE = 0;
    static final int REVALIDATE_SAME = 1;
    static final int REVALIDATE_CHANGED = 2;
    static final int REVALIDATE_NULL_CONFIRMED = 3;

    private long mGeneration;
    private long mPendingGeneration;
    private String mRawPackage = "";
    private String mStablePackage = "";
    private int mStableUserId;
    private boolean mPendingNull;
    private int mRevalidateCount;
    private String mLastRevalidateResult = "none";

    boolean acceptValid(long generation, String packageName, int userId) {
        if (generation < mGeneration) {
            return false;
        }
        boolean cancelledPending = mPendingNull;
        mGeneration = generation;
        mPendingGeneration = 0;
        mRawPackage = safe(packageName);
        mStablePackage = mRawPackage;
        mStableUserId = userId;
        mPendingNull = false;
        if (cancelledPending) {
            mLastRevalidateResult = "cancelledByValid:" + mStablePackage;
        }
        return true;
    }

    boolean deferNull(long generation) {
        if (generation < mGeneration) {
            return false;
        }
        mGeneration = generation;
        mPendingGeneration = generation;
        mRawPackage = "";
        mPendingNull = true;
        return true;
    }

    boolean isPending(long generation) {
        return mPendingNull && generation == mGeneration
                && generation == mPendingGeneration;
    }

    int revalidate(long generation, String packageName, int userId) {
        if (!isPending(generation)) {
            return REVALIDATE_STALE;
        }
        mPendingNull = false;
        mPendingGeneration = 0;
        mRevalidateCount++;
        String currentPackage = safe(packageName);
        if (currentPackage.isEmpty()) {
            mStablePackage = "";
            mStableUserId = 0;
            mLastRevalidateResult = "nullConfirmed";
            return REVALIDATE_NULL_CONFIRMED;
        }
        boolean changed = !currentPackage.equals(mStablePackage) || userId != mStableUserId;
        mStablePackage = currentPackage;
        mStableUserId = userId;
        mLastRevalidateResult = "package:" + currentPackage;
        return changed ? REVALIDATE_CHANGED : REVALIDATE_SAME;
    }

    void authorityError(long generation) {
        if (isPending(generation)) {
            mPendingNull = false;
            mPendingGeneration = 0;
            mLastRevalidateResult = "authorityError";
        }
    }

    long generation() {
        return mGeneration;
    }

    long pendingGeneration() {
        return mPendingGeneration;
    }

    String rawPackage() {
        return mRawPackage;
    }

    String stablePackage() {
        return mStablePackage;
    }

    int stableUserId() {
        return mStableUserId;
    }

    boolean pendingNull() {
        return mPendingNull;
    }

    int revalidateCount() {
        return mRevalidateCount;
    }

    String lastRevalidateResult() {
        return mLastRevalidateResult;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
