package net.somewhatcity.boiler.api.display;

import com.google.gson.JsonObject;

/**
 * Compile-time Display-Interface-Stub für Boiler.
 */
public interface IBoilerDisplay {
    int id();
    int width();
    int height();
    void source(String name, JsonObject data);
    void save();
    void saveSourceData(JsonObject data);
}
