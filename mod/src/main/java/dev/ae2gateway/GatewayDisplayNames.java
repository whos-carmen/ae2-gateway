package dev.ae2gateway;

import appeng.api.stacks.AEKey;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Resolves AE key display names on the dedicated server. Vanilla
 * {@link Component#getString()} often returns untranslated keys because KubeJS
 * (and similar) lang files live under {@code kubejs/assets} and are not merged
 * into the server {@link Language} map.
 */
final class GatewayDisplayNames {
    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {
    }.getType();
    private static final Object LOCK = new Object();
    private static Map<String, String> extraLang;

    private GatewayDisplayNames() {
    }

    static String of(AEKey key) {
        return resolve(key.getDisplayName(), key.getId());
    }

    static String resolve(Component component, ResourceLocation id) {
        String text = component == null ? "" : component.getString();
        if (!looksLikeTranslationKey(text)) {
            return text;
        }

        Language language = Language.getInstance();
        String fromLanguage = language.getOrDefault(text);
        if (!looksLikeTranslationKey(fromLanguage)) {
            return fromLanguage;
        }

        Map<String, String> extra = extraLang();
        for (String candidate : candidates(text, id)) {
            String value = extra.get(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
            String langValue = language.getOrDefault(candidate);
            if (!looksLikeTranslationKey(langValue)) {
                return langValue;
            }
        }

        return humanizeKey(text.isBlank() && id != null ? id.getPath() : text);
    }

    private static String[] candidates(String text, ResourceLocation id) {
        if (id == null) {
            return new String[] {text};
        }
        String ns = id.getNamespace();
        String path = id.getPath();
        return new String[] {
                text,
                "item." + ns + "." + path,
                "block." + ns + "." + path,
                "fluid." + ns + "." + path,
                "material." + ns + "." + path,
        };
    }

    static boolean looksLikeTranslationKey(String text) {
        if (text == null || text.isBlank() || text.indexOf(' ') >= 0 || text.indexOf('.') < 0) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!text.equals(lower)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '.' || c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    static String humanizeKey(String key) {
        String leaf = key;
        int dot = leaf.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < leaf.length()) {
            leaf = leaf.substring(dot + 1);
        }
        int colon = leaf.indexOf(':');
        if (colon >= 0 && colon + 1 < leaf.length()) {
            leaf = leaf.substring(colon + 1);
        }
        String[] parts = leaf.split("[_\\-]+");
        StringBuilder out = new StringBuilder(leaf.length() + 8);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.length() == 0 ? key : out.toString();
    }

    private static Map<String, String> extraLang() {
        synchronized (LOCK) {
            if (extraLang != null) {
                return extraLang;
            }
            Map<String, String> loaded = new HashMap<>();
            Path assets = FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assets");
            if (Files.isDirectory(assets)) {
                try (Stream<Path> walk = Files.walk(assets)) {
                    walk.filter(path -> path.getFileName().toString().equals("en_us.json"))
                            .filter(Files::isRegularFile)
                            .forEach(path -> mergeJson(path, loaded));
                } catch (IOException ignored) {
                    // Fall back to humanized keys when kubejs assets cannot be read.
                }
            }
            extraLang = Map.copyOf(loaded);
            return extraLang;
        }
    }

    private static void mergeJson(Path path, Map<String, String> into) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, String> parsed = GSON.fromJson(reader, STRING_MAP);
            if (parsed == null) {
                return;
            }
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                    into.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception ignored) {
            // Skip malformed lang files.
        }
    }
}
