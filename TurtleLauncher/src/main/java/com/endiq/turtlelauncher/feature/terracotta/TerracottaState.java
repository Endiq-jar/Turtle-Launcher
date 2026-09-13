package com.endiq.turtlelauncher.feature.terracotta;

import androidx.annotation.Keep;
import androidx.annotation.StringRes;

import com.endiq.turtlelauncher.R;
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
@Keep
public abstract class TerracottaState {
    protected TerracottaState() {
    }

    @Keep
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

        /**
         * One-line description of this state for the VPN foreground notification - ported
         * from Zalith Launcher 2's TerracottaState.Ready.localStringRes(). 0 means "no
         * displayable text" (only Waiting, which the notification never shows).
         */
        @StringRes
        public abstract int localStringRes();

        /**
         * True when this state is just a player-profile update of the same live room,
         * rather than a genuine state transition - ported from Zalith Launcher 2. The
         * native backend re-emits host-ok/guest-ok with a bumped index whenever somebody
         * joins or leaves; UI layers use this to refresh the player list without re-running
         * "just connected" side effects (like copying the invite code again).
         */
        public boolean isForkOf(Ready state) {
            return false;
        }

        @Override
        public String toString() {
            String simple = getClass().getSimpleName();
            String withUnderscore = simple.replaceAll("([a-z])([A-Z])", "$1_$2");
            return withUnderscore.toLowerCase(Locale.ROOT);
        }
    }

    @Keep
    public static final class Waiting extends Ready {
        Waiting(int index, String state) { super(index, state); }

        @StringRes
        @Override
        public int localStringRes() {
            return 0; // never displayed - the VPN notification hides Waiting states
        }
    }

    @Keep
    public static final class HostScanning extends Ready {
        HostScanning(int index, String state) { super(index, state); }

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_host_scanning;
        }
    }

    @Keep
    public static final class HostStarting extends Ready {
        HostStarting(int index, String state) { super(index, state); }

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_host_starting;
        }
    }

    @Keep
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

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_host_ok;
        }

        @Override
        public boolean isForkOf(Ready state) {
            return state instanceof HostOK && (this.index - state.index) <= profileIndex;
        }
    }

    @Keep
    public static final class GuestConnecting extends Ready {
        GuestConnecting(int index, String state) { super(index, state); }

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_guest_connecting;
        }
    }

    @Keep
    public static final class GuestStarting extends Ready {
        @Keep
    public enum Difficulty {
            UNKNOWN(0),
            EASIEST(R.string.terracotta_difficulty_easiest),
            SIMPLE(R.string.terracotta_difficulty_simple),
            MEDIUM(R.string.terracotta_difficulty_medium),
            TOUGH(R.string.terracotta_difficulty_tough);

            @StringRes
            public final int textRes;

            Difficulty(@StringRes int textRes) {
                this.textRes = textRes;
            }
        }

        @SerializedName("difficulty")
        private final Difficulty difficulty;

        GuestStarting(int index, String state, Difficulty difficulty) {
            super(index, state);
            this.difficulty = difficulty;
        }

        public Difficulty getDifficulty() { return difficulty; }

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_guest_starting;
        }
    }

    @Keep
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

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_guest_ok;
        }

        @Override
        public boolean isForkOf(Ready state) {
            return state instanceof GuestOK && (this.index - state.index) <= profileIndex;
        }
    }

    @Keep
    public static final class ExceptionState extends Ready {
        @Keep
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

        @StringRes
        @Override
        public int localStringRes() {
            return R.string.terracotta_status_exception;
        }
    }

    /** Minimal profile info Terracotta reports for connected players - room/host metadata. */
    @Keep
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
            String state = requireMember(obj, "state", "root").getAsString();
            int index = requireMember(obj, "index", "root").getAsInt();

            switch (state) {
                case "waiting":
                    return new Waiting(index, state);
                case "host-scanning":
                    return new HostScanning(index, state);
                case "host-starting":
                    return new HostStarting(index, state);
                case "host-ok": {
                    // TurtleLauncher: these used to be bare obj.get("room").getAsString()
                    // calls - a native backend that emits host-ok before the room code is
                    // assigned (or an older/newer .so with shifted fields) NPE'd deep inside
                    // Gson. Zalith Launcher 2 validates the same invariants after parsing
                    // (TerracottaStateTypeAdapterFactory.validateResult); we enforce them
                    // during parsing, as a JsonParseException the poll daemon already
                    // catches, logs and backs off from.
                    String code = requireMember(obj, "room", state).getAsString();
                    int profileIndex = requireMember(obj, "profile_index", state).getAsInt();
                    List<TerracottaProfile> profiles = deserializeProfiles(obj, context, state);
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
                    JsonElement urlElement = obj.get("url");
                    String url = urlElement != null && !urlElement.isJsonNull() ? urlElement.getAsString() : null;
                    int profileIndex = requireMember(obj, "profile_index", state).getAsInt();
                    List<TerracottaProfile> profiles = deserializeProfiles(obj, context, state);
                    return new GuestOK(index, state, url, profileIndex, profiles);
                }
                case "exception": {
                    int type = requireMember(obj, "type", state).getAsInt();
                    return new ExceptionState(index, state, type);
                }
                default:
                    throw new JsonParseException("Unknown Terracotta state: " + state);
            }
        }

        private static JsonElement requireMember(JsonObject obj, String name, String state) throws JsonParseException {
            JsonElement element = obj.get(name);
            if (element == null || element.isJsonNull()) {
                throw new JsonParseException("Terracotta state '" + state + "' is missing required member '" + name + "'");
            }
            return element;
        }

        private static List<TerracottaProfile> deserializeProfiles(JsonObject obj, JsonDeserializationContext context, String state) {
            JsonElement profilesElement = requireMember(obj, "profiles", state);
            List<TerracottaProfile> profiles = context.deserialize(
                profilesElement, new com.google.gson.reflect.TypeToken<List<TerracottaProfile>>() {}.getType());
            if (profiles == null) {
                throw new JsonParseException("Terracotta state '" + state + "' has null profiles list");
            }
            return profiles;
        }
    }

    public static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Ready.class, new ReadyDeserializer())
        .create();

    public static Ready parse(String json) {
        return GSON.fromJson(json, Ready.class);
    }
}
