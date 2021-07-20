package space.bbkr.dataoverhaul;

import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Set;

public record RecipeMaterial(Identifier id, Map<String, Identifier> variants, String type, Set<Identifier> ignore) {
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
