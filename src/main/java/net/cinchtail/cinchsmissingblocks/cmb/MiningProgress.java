package net.cinchtail.cinchsmissingblocks.cmb;

/** Time credited by entity attacks; duplicate packets cannot accelerate mining. */
final class MiningProgress {
    private MiningProgress() {}

    static double advance(double previous, double perTick, int elapsed) {
        // Furniture has no release packet. A click cannot claim the entire idle gap
        // since the preceding click, or the first click on a fresh piece.
        int ticks = previous == 0 ? 1 : Math.clamp(elapsed, 0, 6);
        return previous + Math.max(0, perTick) * ticks;
    }

    static int stage(double progress) { return Math.clamp((int) (progress * 10), 0, 9); }
}
