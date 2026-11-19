package com.whaleeye.tide;

/** epoch/seq 游标单调规则：同代次只前进，新代次允许重置，旧代次丢弃。 */
final class CursorPolicy {
    private CursorPolicy() {
    }

    static long[] next(long currentEpoch, long currentSeq, long epoch, long seq) {
        if (epoch > currentEpoch) {
            return new long[]{epoch, Math.max(0L, seq)};
        }
        if (epoch == currentEpoch && seq > currentSeq) {
            return new long[]{epoch, seq};
        }
        return null;
    }
}
