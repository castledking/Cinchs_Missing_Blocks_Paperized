package net.cinchtail.cinchsmissingblocks.cmb.compat;

import java.lang.reflect.Field;
import org.bukkit.GameRule;

/**
 * The one Paper API that moved inside the supported range (1.21.1 - 26.3): where the game
 * rule constants live.
 *
 * <p>{@code GameRule<T>} and {@code World.getGameRuleValue(GameRule<T>)} exist across the
 * whole range; only the constants' home changed. 1.21.11 added {@code org.bukkit.GameRules}
 * and deprecated the constants on {@code GameRule} for removal. Naming either statically
 * breaks a build: {@code GameRules} does not exist in the 1.21.1 API the floor check
 * compiles against, and the {@code GameRule} constants will not exist in some future API
 * the newest check compiles against. So the field is looked up by name, once.
 */
public final class GameRuleCompat {

    /** randomTickSpeed, or {@code null} if neither home has it. */
    public static final GameRule<Integer> RANDOM_TICK_SPEED = lookup("RANDOM_TICK_SPEED");

    private GameRuleCompat() {}

    @SuppressWarnings("unchecked")
    private static <T> GameRule<T> lookup(String name) {
        // Current API (1.21.11+).
        Object rule = field("org.bukkit.GameRules", name);
        if (rule == null) {
            // LEGACY ONLY, for servers before 1.21.11. GameRule's constants are deprecated
            // for removal; do not use this path, or GameRule.<CONSTANT>, in new code.
            rule = field("org.bukkit.GameRule", name);
        }
        return rule instanceof GameRule<?> found ? (GameRule<T>) found : null;
    }

    private static Object field(String owner, String name) {
        try {
            Field field = Class.forName(owner).getField(name);
            return field.get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
