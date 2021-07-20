package space.bbkr.dataoverhaul.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.profiler.Profiler;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.bbkr.dataoverhaul.RecipeMaterial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Mixin(RecipeManager.class)
public class MixinRecipeManager {
	@Shadow @Final private static Gson GSON;
	@Shadow @Final private static Logger LOGGER;

	@Inject(method = "apply", at = @At("HEAD"))
	private void applyTemplates(Map<Identifier, JsonElement> recipes, ResourceManager manager, Profiler profiler, CallbackInfo info) {
		Map<Identifier, RecipeMaterial> materials = new HashMap<>();
		for (Identifier path : manager.findResources("materials", path -> path.endsWith(".json"))) {
			Identifier id = new Identifier(path.getNamespace(), path.getPath().substring(10, path.getPath().length() - 5));
			try {
				Collection<Resource> resources = manager.getAllResources(path);
				Map<String, Identifier> map = new HashMap<>();
				Set<Identifier> ignore = new HashSet<>();
				String type = "";
				for (Resource res : resources) {
					String contents = IOUtils.toString(res.getInputStream(), StandardCharsets.UTF_8);
					JsonElement el = JsonHelper.deserialize(GSON, contents, JsonElement.class);
					if (el != null && el.isJsonObject()) {
						JsonObject json = el.getAsJsonObject();
						String resType = JsonHelper.getString(json, "type", "");
						if (!type.equals("") && !type.equals(resType)) {
							LOGGER.error("Parsing error loading material {}: already has type {}, cannot be assigned {}", id, type, resType);
							continue;
						}
						type = resType;
						if (JsonHelper.hasJsonObject(json, "variants")) {
							JsonObject variants = JsonHelper.getObject(json, "variants");
							for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
								if (JsonHelper.isString(entry.getValue())) {
									map.put(entry.getKey(), new Identifier(entry.getValue().getAsString()));
								}
							}
						}
						if (JsonHelper.hasArray(json, "ignore")) {
							JsonArray ignored = JsonHelper.getArray(json, "ignore");
							boolean canAdd = true;
							for (JsonElement entry : ignored) {
								if (JsonHelper.isString(entry)) {
									ignore.add(new Identifier(entry.getAsString()));
								} else {
									LOGGER.error("Parsing error loading material {}: ignore list must only be strings", id);
									canAdd = false;
								}
							}
							if (!canAdd) continue;
						}
					}
					materials.put(id, new RecipeMaterial(id, map, type, ignore));
				}
			} catch (IOException e) {
				LOGGER.error("Parsing error loading material {}", id, e);
			}
		}
		for (Identifier path : manager.findResources("templates", path -> path.endsWith(".json"))) {
			Identifier id = new Identifier(path.getNamespace(), path.getPath().substring(10, path.getPath().length() - 5));
			try {
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
							if (material.ignore().contains(id)) continue;
							if (!type.equals("") && !type.equals(material.type())) continue;
							boolean canUse = true;
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
						LOGGER.error("Parsing error loading template recipe {}: must have requirements object", id);
					}
				}
			} catch (IOException e) {
				LOGGER.error("Parsing error loading template recipe {}", id, e);
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
