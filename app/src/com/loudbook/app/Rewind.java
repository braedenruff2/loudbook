package com.loudbook.app;

/**
 * How far back an audiobook goes when play is pressed after a pause, so the listener catches the thread again: nothing
 * after a short pause, a few seconds after a minute or more, more after a long break (asleep, a meal). Pure: UnitTest.
 */
final class Rewind {
    private Rewind() { }

    /** ms to go back after a pause of awayMs, never past the chapter's start (posMs). */
    static int backMs(long awayMs, int posMs) {
        int back = awayMs >= 30 * 60_000L ? 10_000 : awayMs >= 5 * 60_000L ? 5_000 : awayMs >= 60_000L ? 3_000 : 0;
        return Math.max(0, Math.min(back, posMs));
    }

    /** The reader, after a pause of awayMs: -1 = go on mid-sentence; 0 = the sentence paused in again from its start;
     *  1 = from the sentence before it too. */
    static int sentencesBack(long awayMs) {
        return awayMs >= 30 * 60_000L ? 1 : awayMs >= 60_000L ? 0 : -1;
    }
}
