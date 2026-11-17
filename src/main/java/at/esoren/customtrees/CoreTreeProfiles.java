package at.esoren.customtrees;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeGenerator;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeGrowthProfile;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import java.util.Random;

/** Adapter from the configured biome profile to Core's tree growth profile. */
final class CoreTreeProfiles {
    private CoreTreeProfiles() { }

    static TreeGenerator tree(API api, StructureAccess access, Random random, TreeSpecies species,
            BiomeGrowthProfiles.Profile profile) {
        TreeGrowthProfile coreProfile = new TreeGrowthProfile(profile.heightScale(), profile.crownScale(),
                TreeGrowthProfile.CrownForm.valueOf(profile.crownForm().name()));
        return api.structureGenerator().tree(access, random, species, coreProfile);
    }
}
