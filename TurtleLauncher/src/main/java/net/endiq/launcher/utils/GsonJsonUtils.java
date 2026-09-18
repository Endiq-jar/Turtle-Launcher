package net.endiq.launcher.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class GsonJsonUtils {
    public static JsonObject getJsonObjectSafe(JsonElement element) {
        if(element == null) return null;
        if(element.isJsonNull() || !element.isJsonObject()) return null;
        return element.getAsJsonObject();
    }

    public static JsonElement getElementSafe(JsonObject jsonObject, String memberName) {
        if(jsonObject == null) return null;
        if(!jsonObject.has(memberName)) return null;
        JsonElement element = jsonObject.get(memberName);
        if(element.isJsonNull()) return null;
        return element;
    }

    public static JsonObject getJsonObjectSafe(JsonObject jsonObject, String memberName) {
        return getJsonObjectSafe(getElementSafe(jsonObject, memberName));
    }

    public static JsonArray getJsonArraySafe(JsonObject jsonObject, String memberName) {
        JsonElement jsonElement = getElementSafe(jsonObject, memberName);
        if(jsonElement == null || !jsonElement.isJsonArray()) return null;
        return jsonElement.getAsJsonArray();
    }

    public static int getIntSafe(JsonObject jsonObject, String memberName, int onNullValue) {
        JsonElement jsonElement = getElementSafe(jsonObject, memberName);
        if(jsonElement == null || !jsonElement.isJsonPrimitive()) return onNullValue;
        try {
            return jsonElement.getAsInt();
        }catch (ClassCastException e) {
            return onNullValue;
        }
    }

    public static String getStringSafe(JsonObject jsonObject, String memberName) {
        JsonElement jsonElement = getElementSafe(jsonObject, memberName);
        if(jsonElement == null || !jsonElement.isJsonPrimitive()) return null;
        try {
            return jsonElement.getAsString();
        }catch (ClassCastException e) {
            return null;
        }
    }
}
