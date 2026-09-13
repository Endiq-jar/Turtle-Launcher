package com.endiq.turtlelauncher.feature.terracotta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;

/**
 * Terracotta connection state hierarchy - adapted from FoldCraftLauncher's
 * TerracottaState.java (net.burningtnt.terracotta / FCL-Team/FoldCraftLauncher,
 * GPLv3), simplified to use a plain Gson JsonDeserializer instead of FCL's own
 * JsonType/JsonSubtype polymorphic-deserialization framework, which this project
 * doesn't have. Field names and state semantics are unchanged from the original -
 * only the deserialization mechanism differs.
 */
public abstract class TerracottaState {
    protected TerracottaState() {
    }

    public static abstract class Ready extends TerracottaState {
        @SerializedName("index")
        final int index;

        @SerializedName("state")
        private final String state;

        Ready(int index, String state) {
            this.index = index;
            this.state = state;
        }

        public int getIndex() {
            return index;
        }

        @Override
        public String toString() {
            String simple = getClass().getSimpleName();
            String withUnderscore = simple.replaceAll("([a-z])([A-Z])", "$1_$2");
            return withUnderscore.toLowerCase(Locale.ROOT);
        }
    }

    public static final class Waiting extends Ready {
        Waiting(int index, String state) { super(index, state); }
    }

    public static final class HostScanning extends Ready {
        HostScanning(int index, String state) { super(index, state); }
    }

    public static final class HostStarting extends Ready {
        HostStarting(int index, String state) { super(index, state); }
    }

    public static final class HostOK extends Ready {
        @SerializedName("room")
        private final String code;
        @SerializedName("profile_index")
        private final int profileIndex;
        @SerializedName("profiles")
        private final List<TerracottaProfile> profiles;

        HostOK(int index, String state, String code, int profileIndex, List<TerracottaProfile> profiles) {
            super(index, state);
            this.code = code;
            this.profileIndex = profileIndex;
            this.profiles = profiles;
        }

        public String getCode() { return code; }
        public List<TerracottaProfile> getProfiles() { return profiles; }
    }

    public static final class GuestConnecting extends Ready {
        GuestConnecting(int index, String state) { super(index, state); }
    }

    public static final class GuestStarting extends Ready {
        public enum Difficulty { UNKNOWN, EASIEST, SIMPLE, MEDIUM, TOUGH }

        @SerializedName("difficulty")
        private final Difficulty difficulty;

        GuestStarting(int index, String state, Difficulty difficulty) {
            super(index, state);
            this.difficulty = difficulty;
        }

        public Difficulty getDifficulty() { return difficulty; }
    }

    public static final class GuestOK extends Ready {
        @SerializedName("url")
        private final String url;
        @SerializedName("profile_index")
        private final int profileIndex;
        @SerializedName("profiles")
        private final List<TerracottaProfile> profiles;

        GuestOK(int index, String state, String url, int profileIndex, List<TerracottaProfile> profiles) {
            super(index, state);
            this.url = url;
            this.profileIndex = profileIndex;
            this.profiles = profiles;
        }

        public String getUrl() { return url; }
        public List<TerracottaProfile> getProfiles() { return profiles; }
    }

    public static final class ExceptionState extends Ready {
        public enum Type {
            PING_HOST_FAIL, PING_HOST_RST, GUEST_ET_CRASH, HOST_ET_CRASH,
            PING_SERVER_RST, SCAFFOLDING_INVALID_RESPONSE
        }
        private static final Type[] LOOKUP = Type.values();

        @SerializedName("type")
        private final int type;

        ExceptionState(int index, String state, int type) {
            super(index, state);
            this.type = type;
        }

        public Type getType() {
            return (type >= 0 && type < LOOKUP.length) ? LOOKUP[type] : Type.PING_HOST_FAIL;
        }
    }

    /** Minimal profile info Terracotta reports for connected players - room/host metadata. */
    public static final class TerracottaProfile {
        @SerializedName("name")
        private final String name;
        @SerializedName("kind")
        private final String kind;

        TerracottaProfile(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        public String getName() { return name; }
        public String getKind() { return kind; }
    }

    /**
     * Manual polymorphic deserializer: peeks at the "state" field to decide which
     * Ready subclass to build, since we don't have FCL's JsonType/JsonSubtype
     * annotation framework available here.
     */
    private static final class ReadyDeserializer implements JsonDeserializer<Ready> {
        @Override
        public Ready deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String state = obj.get("state").getAsString();
            int index = obj.get("index").getAsInt();

            switch (state) {
                case "waiting":
                    return new Waiting(index, state);
                case "host-scanning":
                    return new HostScanning(index, state);
                case "host-starting":
                    return new HostStarting(index, state);
                case "host-ok": {
                    String code = obj.get("room").getAsString();
                    int profileIndex = obj.get("profile_index").getAsInt();
                    List<TerracottaProfile> profiles = context.deserialize(
                        obj.get("profiles"), new com.google.gson.reflect.TypeToken<List<TerracottaProfile>>() {}.getType());
                    return new HostOK(index, state, code, profileIndex, profiles);
                }
                case "guest-connecting":
                    return new GuestConnecting(index, state);
                case "guest-starting": {
                    GuestStarting.Difficulty difficulty = obj.has("difficulty")
                        ? context.deserialize(obj.get("difficulty"), GuestStarting.Difficulty.class)
                        : GuestStarting.Difficulty.UNKNOWN;
                    return new GuestStarting(index, state, difficulty);
                }
                case "guest-ok": {
                    String url = obj.has("url") ? obj.get("url").getAsString() : null;
                    int profileIndex = obj.get("profile_index").getAsInt();
                    List<TerracottaProfile> profiles = context.deserialize(
                        obj.get("profiles"), new com.google.gson.reflect.TypeToken<List<TerracottaProfile>>() {}.getType());
                    return new GuestOK(index, state, url, profileIndex, profiles);
                }
                case "exception": {
                    int type = obj.get("type").getAsInt();
                    return new ExceptionState(index, state, type);
                }
                default:
                    throw new JsonParseException("Unknown Terracotta state: " + state);
            }
        }
    }

    public static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Ready.class, new ReadyDeserializer())
        .create();

    public static Ready parse(String json) {
        return GSON.fromJson(json, Ready.class);
    }
}
