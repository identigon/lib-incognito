package org.identigon.incognito.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.identigon.incognito.policy.AnonymisationPolicy;

/**
 * Package-private builder for {@link IncognitoPipeline}. Collects configuration and validates it;
 * the returned pipeline's stage execution is not yet implemented (Phases 2–6). Kept in the api
 * package so {@link IncognitoPipeline#builder()} does not create an api -&gt; core cycle.
 */
final class IncognitoPipelineBuilder implements IncognitoPipeline.Builder {

    private enum SaltMode { EPHEMERAL, PERSISTENT, REPRODUCIBLE }

    private DataSource source;
    private DataSource target;
    private Locale locale = Locale.UK;
    private KeyTranslationStore keyStore;
    private AnonymisationPolicy policy;
    private final List<PipelineStage> stages = new ArrayList<>();

    private SaltMode saltMode;
    private byte[] salt;
    private long seed;

    @Override
    public IncognitoPipeline.Builder source(DataSource source) {
        this.source = source;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder target(DataSource target) {
        this.target = target;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder locale(Locale locale) {
        this.locale = locale;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder keyStore(KeyTranslationStore store) {
        this.keyStore = store;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder ephemeralSalt() {
        setSaltMode(SaltMode.EPHEMERAL);
        return this;
    }

    @Override
    public IncognitoPipeline.Builder persistentSalt(byte[] salt) {
        setSaltMode(SaltMode.PERSISTENT);
        this.salt = salt == null ? null : salt.clone();
        return this;
    }

    @Override
    public IncognitoPipeline.Builder reproducible(byte[] salt, long seed) {
        setSaltMode(SaltMode.REPRODUCIBLE);
        this.salt = salt == null ? null : salt.clone();
        this.seed = seed;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder policy(AnonymisationPolicy policy) {
        this.policy = policy;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder stage(PipelineStage stage) {
        this.stages.add(stage);
        return this;
    }

    @Override
    public IncognitoPipeline build() {
        if (source == null || target == null) {
            throw new IncognitoException.ConfigException("source and target DataSources are required");
        }
        if (policy == null) {
            throw new IncognitoException.ConfigException("an AnonymisationPolicy is required");
        }
        SaltMode resolvedSaltMode = saltMode != null ? saltMode : SaltMode.EPHEMERAL; // default

        // Computed fresh into a LOCAL array on every build() call — never mutates `this.salt`/
        // `this.saltMode` — so calling build() more than once on the same builder (PERSISTENT or
        // REPRODUCIBLE mode; a builder is not documented as single-use) hands each resulting
        // pipeline its own independent salt array. Without this, both pipelines would share one
        // mutable byte[]; whichever finishes first zeroes it (SPEC §5.1) out from under the other.
        byte[] effectiveSalt = switch (resolvedSaltMode) {
            case EPHEMERAL -> {
                byte[] ephemeral = new byte[32];
                new java.security.SecureRandom().nextBytes(ephemeral);
                yield ephemeral;
            }
            case PERSISTENT -> this.salt == null ? null : this.salt.clone();
            // The seed is mixed into the salt actually handed to AlterEgo: two `reproducible` calls
            // with the same salt but different seeds must (per SPEC §5.2) produce different, but
            // each individually reproducible, output — AlterEgo itself has no separate seed concept.
            case REPRODUCIBLE -> this.salt == null ? null : deriveReproducibleSalt(this.salt, this.seed);
        };

        org.identigon.alterego.store.MappingStore alterEgoStore =
            new org.identigon.alterego.store.InMemoryMappingStore();

        org.identigon.alterego.AlterEgo alterEgo = org.identigon.alterego.AlterEgo.builder()
            .salt(effectiveSalt)
            .locale(this.locale)
            .rawMappingKeys(false)
            .mappingStore(alterEgoStore)
            .build();

        KeyTranslationStore resolvedKeyStore = this.keyStore != null
            ? this.keyStore
            : new org.identigon.incognito.core.InMemoryKeyTranslationStore();

        PipelineContext context = new org.identigon.incognito.core.DefaultPipelineContext(
            source, target, resolvedKeyStore,
            new org.identigon.incognito.core.InMemoryAttributeCascadeStore(),
            alterEgo, policy,
            new java.util.concurrent.ConcurrentHashMap<>()
        );

        // If the caller supplied no stages, assemble the standard v1.0 pipeline so the
        // documented `builder()...build().execute()` form actually anonymises.
        List<PipelineStage> resolvedStages = stages.isEmpty()
            ? List.of(new org.identigon.incognito.core.SchemaDiscoveryStage(),
                      new org.identigon.incognito.core.TableTransformLoadStage(),
                      new org.identigon.incognito.core.VerificationStage())
            : stages;

        return new org.identigon.incognito.core.DefaultIncognitoPipeline(context, resolvedStages, effectiveSalt);
    }

    /**
     * Derives the salt actually used for a {@code reproducible(salt, seed)} run: {@code
     * SHA-256(salt || seed)}. Deterministic in {@code (salt, seed)} — same inputs always yield the
     * same output — so a fixed salt and seed remain byte-for-byte reproducible across runs, while
     * varying the seed alone (holding salt fixed) varies the fabricated output, matching SPEC §5.2.
     * Package-private (not private) so a unit test can exercise it directly, without a live database.
     */
    static byte[] deriveReproducibleSalt(byte[] salt, long seed) {
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            sha256.update(salt);
            sha256.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(seed).array());
            return sha256.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // The three salt modes are mutually exclusive (SPEC §5.1).
    private void setSaltMode(SaltMode mode) {
        if (saltMode != null && saltMode != mode) {
            throw new IncognitoException.ConfigException(
                "salt modes are mutually exclusive: " + saltMode + " already set, cannot also set " + mode);
        }
        saltMode = mode;
    }
}
