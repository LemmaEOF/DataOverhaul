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
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.bbkr.dataoverhaul.RecipeMaterial;

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
		Map<Identifier, RecipeMaterial> materials = new HashMap<>();
		for (Identifier path : manager.findResources("materials", path -> path.endsWith(".json"))) {
			try {
				Identifier id = new Identifier(path.getNamespace(), path.getPath().substring(10, path.getPath().length() - 5));
				InputStream res = manager.getResource(path).getInputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(res, StandardCharsets.UTF_8));
				JsonElement el = JsonHelper.deserialize(GSON, reader, JsonElement.class);
				if (el != null && el.isJsonObject()) {
					JsonObject json = el.getAsJsonObject();
					Map<String, Identifier> map = new HashMap<>();
					String type = JsonHelper.getString(json, "type", "");
					if (JsonHelper.hasJsonObject(json, "variants")) {
						JsonObject variants = JsonHelper.getObject(json, "variants");
						for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
							if (JsonHelper.isString(entry.getValue())) {
								map.put(entry.getKey(), new Identifier(entry.getValue().getAsString()));
							}
						}
					}
					materials.put(id, new RecipeMaterial(id, map, type));
				}
			} catch (IOException e) {
				//TODO: print here
			}
		}
		for (Identifier path : manager.findResources("templates", path -> path.endsWith(".json"))) {
			try {
				Identifier id = new Identifier(path.getNamespace(), path.getPath().substring(10, path.getPath().length() - 5));
				Resource res = manager.getResource(path);
				String contents = IOUtils.toString(res.getInputStream(), StandardCharsets.UTF_8);
				JsonElement el = JsonHelper.deserialize(GSON, contents, JsonElement.class);
				if (el != null && el.isJsonObject()) {
					JsonObject json = el.getAsJsonObject();
					if (JsonHelper.hasJsonObject(json, "$requirements")) {
						JsonObject reqs = JsonHelper.getObject(json, "$requirements", new JsonObject());
						String type = JsonHelper.getString(reqs, "type", "");
						JsonArray variants = JsonHelper.getArray(reqs, "variants", new JsonArray());
						for (Identifier matId : materials.keySet()) {
							RecipeMaterial material = materials.get(matId);
							boolean canUse = true;
							if (!type.equals("") && !type.equals(material.type())) canUse = false;
							for (JsonElement entry : variants) {
								//TODO: throw if invalid?
								if (!material.hasVariant(entry.getAsString())) {
									canUse = false;
								}
							}
							if (!canUse) continue;
							//help me, this is the only easy way to deal with comod and `deepCopy` is package-private
							JsonObject newJson = JsonHelper.deserialize(GSON, contents, JsonElement.class).getAsJsonObject();
							newJson.remove("$requirements");
							dataoverhaul$visitObject(json, newJson, material);
							Identifier recipeId = new Identifier(matId.getNamespace(), matId.getPath() + "_" + id.getPath());
							recipes.put(recipeId, newJson);
						}
					} else {
						//TODO: throw here!!
					}
				}
			} catch (IOException e) {
				//TODO: print here
			}
		}
	}

	private void dataoverhaul$visitObject(JsonObject iterObj, JsonObject json, RecipeMaterial material) {
		for (Map.Entry<String, JsonElement> entry : iterObj.entrySet()) {
			if (entry.getKey().endsWith("$variant") && JsonHelper.isString(entry.getValue())) {
				String variant = entry.getValue().getAsString();
				json.addProperty(entry.getKey().substring(0, entry.getKey().length() - 8), material.getVariant(variant).toString());
				json.remove(entry.getKey());
			} else if (entry.getKey().endsWith("$type") && JsonHelper.isString(entry.getValue())) {
				String base = entry.getValue().getAsString();
				json.addProperty(entry.getKey().substring(0, entry.getKey().length() - 5), material.type() + "_" + base);
				json.remove(entry.getKey());
			} else if (entry.getKey().endsWith("$material") && JsonHelper.isString(entry.getValue())) {
				String base = entry.getValue().getAsString();
				json.addProperty(entry.getKey().substring(0, entry.getKey().length() - 9), material.getShortName() + "_" + base);
				json.remove(entry.getKey());
			} else if (entry.getValue().isJsonArray()) {
				dataoverhaul$visitArray(entry.getValue().getAsJsonArray(), json.get(entry.getKey()).getAsJsonArray(), material);
			} else if (entry.getValue().isJsonObject()) {
				dataoverhaul$visitObject(entry.getValue().getAsJsonObject(), json.get(entry.getKey()).getAsJsonObject(), material);
			}
		}
	}

	private void dataoverhaul$visitArray(JsonArray iterObj, JsonArray json, RecipeMaterial material) {
		for (int i = 0; i < iterObj.size(); i++) {
			JsonElement el = iterObj.get(i);
			if (el.isJsonObject()) {
				dataoverhaul$visitObject(el.getAsJsonObject(), json.get(i).getAsJsonObject(), material);
			}
		}
	}
}
