package dev.nulli0n.eev.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class SettingsCodec {
    private static final Gson GSON = new Gson();

    private SettingsCodec() {
    }

    public static boolean paymentsEnabled(String json, String currencyId) {
        JsonObject root = parse(json);
        JsonElement currency = root.get(currencyId);
        if (currency == null || !currency.isJsonObject()) {
            return true;
        }
        JsonElement enabled = currency.getAsJsonObject().get("paymentsEnabled");
        return enabled == null || enabled.getAsBoolean();
    }

    public static String setPaymentsEnabled(String json, String currencyId, boolean enabled) {
        JsonObject root = parse(json);
        JsonObject currency;
        JsonElement current = root.get(currencyId);
        if (current != null && current.isJsonObject()) {
            currency = current.getAsJsonObject();
        }
        else {
            currency = new JsonObject();
            root.add(currencyId, currency);
        }
        currency.addProperty("paymentsEnabled", enabled);
        return GSON.toJson(root);
    }

    private static JsonObject parse(String json) {
        if (json == null || json.isBlank() || json.equalsIgnoreCase("null")) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }
        catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }
}
