package space.bbkr.dataoverhaul.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Mixin(RecipeManager.class)
public class MixinRecipeManager {
	@Shadow @Final private static Gson GSON;
	private static final List<Identifier> DEPRECATED_RECIPES = new ArrayList<>();

	@Inject(method = "apply", at = @At("HEAD"))
	private void applyTemplates(Map<Identifier, JsonElement> recipes, ResourceManager manager, Profiler profiler, CallbackInfo info) {
		for (Identifier id : DEPRECATED_RECIPES) {
			recipes.remove(id);
		}
		Map<Identifier, Map<String, Identifier>> materials = new HashMap<>();
		for (Identifier path : manager.findResources("materials", path -> path.endsWith(".json"))) {
			try {
				Identifier id = new Identifier(path.getNamespace(), path.getPath().substring(10, path.getPath().length() - 5));
				InputStream res = manager.getResource(path).getInputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(res, StandardCharsets.UTF_8));
				JsonElement el = JsonHelper.deserialize(GSON, reader, JsonElement.class);
				if (el != null && el.isJsonObject()) {
					JsonObject json = el.getAsJsonObject();
					Map<String, Identifier> map = new HashMap<>();
					for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
						if (JsonHelper.isString(entry.getValue())) {
							map.put(entry.getKey(), new Identifier(entry.getValue().getAsString()));
						}
					}
					materials.put(id, map);
				}
			} catch (IOException e) {
				//TODO: print here
			}
		}
		for (Identifier path : manager.findResources("templates", path -> path.endsWith(".json"))) {
			try {
				Identifier id = new Identifier(path.getNamespace(), path.getPath().substring(10, path.getPath().length() - 5));
				Resource res = manager.getResource(path);
				BufferedReader reader = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8));
				for (Identifier matId : materials.keySet()) {
					Map<String, Identifier> material = materials.get(matId);
					JsonElement el = JsonHelper.deserialize(GSON, reader, JsonElement.class);
					if (el != null && el.isJsonObject()) {
						JsonObject json = el.getAsJsonObject();
						JsonArray array = JsonHelper.getArray(json, "$variants", new JsonArray());
						boolean canUse = true;
						for (JsonElement entry : array) {
							//TODO: throw if invalid?
							if (!material.containsKey(entry.getAsString())) {
								canUse = false;
							}
						}
						if (!canUse) continue;
						json.remove("%variants");
						//help me, this is the only easy way to deal with comod
						JsonObject copy = JsonHelper.deserialize(GSON, new BufferedReader(new InputStreamReader(manager.getResource(path).getInputStream(), StandardCharsets.UTF_8)), JsonElement.class).getAsJsonObject();
						dataoverhaul$visitObject(copy, json, material);
						Identifier recipeId = new Identifier(matId.getNamespace(), matId.getPath() + "_" + id.getPath());
						recipes.put(recipeId, json);
					} //TODO: throw here?
				}
			} catch (IOException e) {
				//TODO: print here
			}
		}
	}

	private void dataoverhaul$visitObject(JsonObject copy, JsonObject json, Map<String, Identifier> material) {
		for (Map.Entry<String, JsonElement> entry : copy.entrySet()) {
			if (entry.getKey().endsWith("$variant") && JsonHelper.isString(entry.getValue())) {
				String variant = entry.getValue().getAsString();
				json.addProperty(entry.getKey().substring(0, entry.getKey().length() - 8), material.get(variant).toString());
				json.remove(entry.getKey());
			} else if (entry.getValue().isJsonArray()) {
				dataoverhaul$visitArray(entry.getValue().getAsJsonArray(), json.get(entry.getKey()).getAsJsonArray(), material);
			} else if (entry.getValue().isJsonObject()) {
				dataoverhaul$visitObject(entry.getValue().getAsJsonObject(), json.get(entry.getKey()).getAsJsonObject(), material);
			}
		}
	}

	private void dataoverhaul$visitArray(JsonArray copy, JsonArray json, Map<String, Identifier> material) {
		for (int i = 0; i < copy.size(); i++) {
			JsonElement el = copy.get(i);
			if (el.isJsonObject()) {
				dataoverhaul$visitObject(el.getAsJsonObject(), json.get(i).getAsJsonObject(), material);
			}
		}
	}
}
