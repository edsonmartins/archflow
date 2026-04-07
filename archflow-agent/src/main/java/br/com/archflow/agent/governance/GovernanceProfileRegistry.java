package br.com.archflow.agent.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GovernanceProfileRegistry {
    private static final Logger log = LoggerFactory.getLogger(GovernanceProfileRegistry.class);
    private final Map<String, GovernanceProfile> profiles;
    private final GovernanceProfile defaultProfile;

    public GovernanceProfileRegistry(GovernanceProfile defaultProfile) {
        this.profiles = new ConcurrentHashMap<>();
        this.defaultProfile = Objects.requireNonNull(defaultProfile);
    }

    public GovernanceProfileRegistry() {
        this(GovernanceProfile.builder().id("default").name("Default Profile")
                .systemPrompt("You are a helpful assistant.").escalationThreshold(0.4).build());
    }

    public void register(GovernanceProfile profile) {
        profiles.put(profile.id(), profile);
        log.info("Registered governance profile: {} ({})", profile.id(), profile.name());
    }

    public GovernanceProfile resolve(String profileId) {
        if (profileId == null) return defaultProfile;
        return profiles.getOrDefault(profileId, defaultProfile);
    }

    public Optional<GovernanceProfile> get(String profileId) { return Optional.ofNullable(profiles.get(profileId)); }
    public GovernanceProfile getDefault() { return defaultProfile; }
    public Collection<GovernanceProfile> listAll() { return Collections.unmodifiableCollection(profiles.values()); }
    public boolean remove(String profileId) { return profiles.remove(profileId) != null; }
    public int size() { return profiles.size(); }
}
