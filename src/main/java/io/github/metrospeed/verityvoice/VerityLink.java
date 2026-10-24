package io.github.metrospeed.verityvoice;

import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Reads and sets Verity's own config values (varmite.verity.VerityConfig) by name.
 *
 * Verity is closed source, so the add-on never calls into its code; it only changes the same
 * settings a player could change in Verity's config screen. Verity writes these to its config
 * file, so callers put the player's own values back when they're done.
 */
public final class VerityLink {
    private static final String CONFIG_CLASS = "varmite.verity.VerityConfig";
    private static final String TYPES = "varmite.verity.types.";
    private static boolean warned;

    public static String getString(String field) {
        Object v = get(field);
        return v == null ? null : v.toString();
    }

    public static Object get(String field) {
        ForgeConfigSpec.ConfigValue<?> value = value(field);
        try {
            return value == null ? null : value.get();
        } catch (IllegalStateException e) {
            return null; // config not loaded yet
        }
    }

    /** Sets a Verity config value if it differs. Returns false if Verity doesn't have that setting. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean set(String field, Object newValue) {
        ForgeConfigSpec.ConfigValue value = value(field);
        if (value == null) return false;
        try {
            if (!Objects.equals(value.get(), newValue)) value.set(newValue);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Sets one of Verity's enum settings (provider choices) by constant name. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean setEnum(String field, String enumClass, String constant) {
        try {
            Class<? extends Enum> cls = (Class<? extends Enum>) Class.forName(TYPES + enumClass, true, VerityLink.class.getClassLoader());
            return set(field, Enum.valueOf(cls, constant));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            warn(enumClass + "." + constant, e);
            return false;
        }
    }

    private static ForgeConfigSpec.ConfigValue<?> value(String field) {
        try {
            Class<?> cls = Class.forName(CONFIG_CLASS, true, VerityLink.class.getClassLoader());
            Field f = cls.getField(field);
            return (ForgeConfigSpec.ConfigValue<?>) f.get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            warn(field, e);
            return null;
        }
    }

    private static void warn(String what, Exception e) {
        if (warned) return;
        warned = true;
        VerityVoice.LOG.warn("[VerityVoice] Couldn't find Verity setting {} ({}). This Verity version may not be supported.", what, e.toString());
    }

    private VerityLink() {
    }
}
