package at.esoren.customtrees;

/** Atomic immutable settings/profile snapshot used by tick and generation callbacks. */
record ActiveTreeSettings(TreeSettings settings, BiomeGrowthProfiles biomeProfiles) {
    static final ActiveTreeSettings EMPTY = new ActiveTreeSettings(null, BiomeGrowthProfiles.EMPTY);
}
