package space.bbkr.dataoverhaul;

import net.minecraft.util.Identifier;

import java.util.Map;

public record RecipeMaterial(Identifier id, Map<String, Identifier> variants, String type) {
	public String getShortName() {
		return id.getPath();
	}

	public boolean hasVariant(String variant) {
		return variants.containsKey(variant);
	}

	public Identifier getVariant(String variant) {
		return variants.get(variant);
	}
}
