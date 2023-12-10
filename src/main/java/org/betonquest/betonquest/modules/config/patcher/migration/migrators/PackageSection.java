package org.betonquest.betonquest.modules.config.patcher.migration.migrators;

import org.betonquest.betonquest.modules.config.patcher.migration.FileConfigurationProvider;
import org.betonquest.betonquest.modules.config.patcher.migration.Migration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Handles the PackageSection migration.
 */
public class PackageSection implements Migration {

    /**
     * The enabled string.
     */
    public static final String ENABLED = "enabled";

    /**
     * The config producer.
     */
    private final FileConfigurationProvider producer;

    /**
     * Creates a new PackageSection migrator.
     *
     * @param producer The config producer
     */
    public PackageSection(final FileConfigurationProvider producer) {
        this.producer = producer;
    }

    @Override
    public void migrate() throws IOException {
        final Map<File, YamlConfiguration> configs = producer.getAllQuestPackagesConfigs();
        for (final Map.Entry<File, YamlConfiguration> entry : configs.entrySet()) {
            final File file = entry.getKey();
            final YamlConfiguration config = entry.getValue();
            if (config.contains(ENABLED, true)) {
                final boolean section = config.getBoolean(ENABLED);
                config.set("package.enabled", section);
                config.set(ENABLED, null);
                config.save(file);
            }
        }
    }
}
