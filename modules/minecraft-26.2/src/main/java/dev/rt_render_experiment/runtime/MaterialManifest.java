package dev.rt_render_experiment.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;









final class MaterialManifest {
   private static final String RESOURCE = "/rt_render_experiment/material-manifest.map";
   private static final int MAX_PACKED_MATERIAL_COUNT = 256;

   private final Map<String, Integer> blockRules;
   private final Map<String, Integer> spriteRules;
   private final Map<String, Integer> fluidRules;
   private final int materialCount;
   private final int fallbackMaterialId;

   private MaterialManifest(
      final Map<String, Integer> blockRules,
      final Map<String, Integer> spriteRules,
      final Map<String, Integer> fluidRules,
      final int materialCount,
      final int fallbackMaterialId
   ) {
      this.blockRules = Map.copyOf(blockRules);
      this.spriteRules = Map.copyOf(spriteRules);
      this.fluidRules = Map.copyOf(fluidRules);
      this.materialCount = materialCount;
      this.fallbackMaterialId = fallbackMaterialId;
   }

   public static MaterialManifest load() {
      InputStream stream = MaterialManifest.class.getResourceAsStream(RESOURCE);
      if (stream == null) {
         throw new MaterialManifestException("RtRenderExperiment material manifest is missing: " + RESOURCE);
      }
      return parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
   }

   static MaterialManifest parse(final Reader source) {
      if (source == null) {
         throw new NullPointerException("source");
      }
      Map<String, Integer> blocks = new HashMap<>();
      Map<String, Integer> sprites = new HashMap<>();
      Map<String, Integer> fluids = new HashMap<>();
      Integer materialCount = null;
      Integer fallback = null;
      try (BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source)) {
         String line;
         int number = 0;
         while ((line = reader.readLine()) != null) {
            number++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
               continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 2 && parts[0].equals("material-count")) {
               if (materialCount != null) {
                  throw new MaterialManifestException("duplicate material-count declaration at manifest line " + number);
               }
               materialCount = materialCount(parts[1], number);
               continue;
            }
            if (parts.length == 2 && parts[0].equals("fallback")) {
               if (fallback != null) {
                  throw new MaterialManifestException("duplicate fallback declaration at manifest line " + number);
               }
               fallback = integer(parts[1], "MaterialId", number);
               continue;
            }
            if (parts.length != 3) {
               throw new MaterialManifestException("malformed material manifest line " + number + ": " + trimmed);
            }
            Map<String, Integer> target = switch (parts[0]) {
               case "block" -> blocks;
               case "sprite" -> sprites;
               case "fluid" -> fluids;
               default -> throw new MaterialManifestException("unknown manifest kind at line " + number + ": " + parts[0]);
            };
            if (target.put(parts[1], integer(parts[2], "MaterialId", number)) != null) {
               throw new MaterialManifestException("duplicate manifest rule at line " + number + ": " + parts[1]);
            }
         }
      } catch (IOException failure) {
         throw new MaterialManifestException("failed to read the RtRenderExperiment material manifest", failure);
      }
      if (materialCount == null) {
         throw new MaterialManifestException("the RtRenderExperiment material manifest must declare its material-count");
      }
      if (fallback == null) {
         throw new MaterialManifestException("the RtRenderExperiment material manifest must declare its fallback MaterialId");
      }
      int declaredCount = materialCount;
      requireInRange(fallback, declaredCount, "fallback");
      blocks.forEach((name, id) -> requireInRange(id, declaredCount, "block " + name));
      sprites.forEach((name, id) -> requireInRange(id, declaredCount, "sprite " + name));
      fluids.forEach((name, id) -> requireInRange(id, declaredCount, "fluid " + name));
      return new MaterialManifest(blocks, sprites, fluids, declaredCount, fallback);
   }

   private static int materialCount(final String token, final int line) {
      int count = integer(token, "material-count", line);
      if (count < 1 || count > MAX_PACKED_MATERIAL_COUNT) {
         throw new MaterialManifestException(
            "material-count out of the packed range [1," + MAX_PACKED_MATERIAL_COUNT + "] at manifest line " + line + ": " + count
         );
      }
      return count;
   }

   private static int integer(final String token, final String field, final int line) {
      final int id;
      try {
         id = Integer.parseInt(token);
      } catch (NumberFormatException failure) {
         throw new MaterialManifestException("non-numeric " + field + " at manifest line " + line + ": " + token, failure);
      }
      return id;
   }

   private static void requireInRange(final int id, final int count, final String rule) {
      if (id < 0 || id >= count) {
         throw new MaterialManifestException(
            "MaterialId out of the declared range [0," + (count - 1) + "] for " + rule + ": " + id
         );
      }
   }

   int materialCount() {
      return this.materialCount;
   }

   int fallbackMaterialId() {
      return this.fallbackMaterialId;
   }

   Integer blockRule(final String registryName) {
      return this.blockRules.get(registryName);
   }

   Integer spriteRule(final String spriteRef) {
      return this.spriteRules.get(spriteRef);
   }

   Integer fluidRule(final String registryName) {
      return this.fluidRules.get(registryName);
   }

   int ruleCount() {
      return this.blockRules.size() + this.spriteRules.size() + this.fluidRules.size();
   }
}
