/**
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ninja.leaping.permissionsex.bukkit;

import com.google.common.collect.ImmutableSet;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import ninja.leaping.permissionsex.ImplementationInterface;
import ninja.leaping.permissionsex.PermissionsEx;
import ninja.leaping.permissionsex.data.SubjectCache;
import ninja.leaping.permissionsex.exception.PEBKACException;
import ninja.leaping.permissionsex.config.ConfigTransformations;
import ninja.leaping.permissionsex.config.PermissionsExConfiguration;
import ninja.leaping.permissionsex.exception.PermissionsLoadingException;
import ninja.leaping.permissionsex.util.Translatable;
import ninja.leaping.permissionsex.util.Util;
import ninja.leaping.permissionsex.util.command.CommandException;
import ninja.leaping.permissionsex.util.command.CommandExecutor;
import ninja.leaping.permissionsex.util.command.CommandContext;
import ninja.leaping.permissionsex.util.command.Commander;
import ninja.leaping.permissionsex.util.command.CommandSpec;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;

import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;

import static ninja.leaping.permissionsex.bukkit.CraftBukkitInterface.getCBClassName;
import static ninja.leaping.permissionsex.bukkit.BukkitTranslations.t;

/**
 * PermissionsEx plugin
 */
public class PermissionsExPlugin extends JavaPlugin implements Listener {
    private static final PermissibleInjector[] injectors = new PermissibleInjector[] {
            new PermissibleInjector.ClassPresencePermissibleInjector("net.glowstone.entity.GlowHumanEntity", "permissions", true),
            new PermissibleInjector.ClassPresencePermissibleInjector("org.getspout.server.entity.SpoutHumanEntity", "permissions", true),
            new PermissibleInjector.ClassNameRegexPermissibleInjector("org.getspout.spout.player.SpoutCraftPlayer", "perm", false, "org\\.getspout\\.spout\\.player\\.SpoutCraftPlayer"),
            new PermissibleInjector.ClassPresencePermissibleInjector(getCBClassName("entity.CraftHumanEntity"), "perm", true),
    };
    public static final String SERVER_TAG_CONTEXT = "server-tag";

    @Nullable
    private volatile PermissionsEx manager;
    private PermissionsExConfiguration config;
    private ConfigurationNode rawConfig;

    private ConfigurationLoader<ConfigurationNode> configLoader;
    private final Map<String, Function<String, String>> nameTransformerMap = new ConcurrentHashMap<>();
    private Logger logger;

    // Injections into superperm
    private PermissionList permsList;
    // Permissions subscriptions handling
    private PEXPermissionSubscriptionMap subscriptionHandler;
    private volatile boolean enabled;

    private static String lf(Translatable trans) {
        return trans.translateFormatted(Locale.getDefault());
    }

    @Override
    public void onEnable() {
        logger = org.slf4j.LoggerFactory.getLogger("PermissionsEx");
        configLoader = YAMLConfigurationLoader.builder()
                .setFile(new File(getDataFolder(), "config.yml"))
                .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
                .build();

        try {
            getDataFolder().mkdirs();
            reloadSync();
        } catch (PEBKACException e) {
            logger.warn(lf(e.getTranslatableMessage()));

        } catch (Exception e) {
            throw new RuntimeException(lf(t("Error occurred while enabling %s", getDescription().getName())), e);
        }

        try {
            rawConfig.setValue(PermissionsExConfiguration.TYPE, config);
            configLoader.save(rawConfig);
        } catch (IOException | ObjectMappingException e) {
            throw new RuntimeException(e);
        }

        nameTransformerMap.put(PermissionsEx.SUBJECTS_USER, new Function<String, String>() {
            @Nullable
            @Override
            public String apply(@Nullable String input) {
                try {
                    UUID.fromString(input);
                    return input;
                } catch (IllegalArgumentException ex) {
                    Player player = getServer().getPlayer(input);
                    if (player != null) {
                        return player.getUniqueId().toString();
                    } else {
                        OfflinePlayer offline = getServer().getOfflinePlayer(input);
                        if (offline != null && offline.getUniqueId() != null) {
                            return offline.getUniqueId().toString();
                        }
                        /*Optional<GameProfileResolver> res = game.getServiceManager().provide(GameProfileResolver.class);
                        if (res.isPresent()) {
                            for (GameProfile profile : res.get().match(input)) {
                                if (profile.getName().equalsIgnoreCase(input)) {
                                    return profile.getUniqueId().toString();
                                }
                            }
                        }*/
                        return input; // TODO: Support offline players
                    }
                }
            }
        });
        getServer().getPluginManager().registerEvents(this, this);
        subscriptionHandler = PEXPermissionSubscriptionMap.inject(this, this.getServer().getPluginManager());
        permsList = PermissionList.inject(this);
        injectAllPermissibles();
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            final PEXVault vault = new PEXVault(this);
            getServer().getServicesManager().register(Permission.class, vault, this, ServicePriority.High); // Hook into vault
            getServer().getServicesManager().register(Chat.class, new PEXVaultChat(vault), this, ServicePriority.High);
            getLogger().info(lf(t("Hooked into Vault for Permission and Chat interfaces")));
        }
        enabled = true;
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        if (subscriptionHandler != null) {
            subscriptionHandler.uninject();
            subscriptionHandler = null;
        }
        if (permsList != null) {
            permsList.uninject();
        }
        uninjectAllPermissibles();
    }

    @EventHandler
    public void onPlayerPreLogin(final AsyncPlayerPreLoginEvent event) {
        try {
            getUserSubjects().load(event.getUniqueId().toString());
        } catch (ExecutionException e) {
            logger.warn(lf(t("Error while loading data for user %s/%s during prelogin: %s", event.getName(), event.getUniqueId().toString(), e.getMessage())), e);
        }
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final String identifier = event.getPlayer().getUniqueId().toString();
        if (getUserSubjects().isRegistered(identifier)) {
            getUserSubjects().update(identifier, input -> {
                if (!event.getPlayer().getName().equals(input.getOptions(PermissionsEx.GLOBAL_CONTEXT).get("name"))) {
                    return input.setOption(PermissionsEx.GLOBAL_CONTEXT, "name", event.getPlayer().getName());
                } else {
                    return input;
                }
            });
        }
        injectPermissible(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        uninjectPermissible(event.getPlayer());
        getManager().uncache(PermissionsEx.SUBJECTS_USER, event.getPlayer().getUniqueId().toString());
    }

    private void reloadSync() throws PEBKACException, ObjectMappingException, PermissionsLoadingException {
        try {
            rawConfig = configLoader.load();
            ConfigTransformations.versions().apply(rawConfig);
            ConfigurationNode fallbackConfig;
            try {
                fallbackConfig = PermissionsExConfiguration.loadDefaultConfiguration();
            } catch (IOException e) {
                throw new Error("PEX's default configuration could not be loaded!", e);
            }
            rawConfig.mergeValuesFrom(fallbackConfig);
            config = rawConfig.getValue(PermissionsExConfiguration.TYPE);
            config.validate();
            PermissionsEx oldManager = manager;
            manager = new PermissionsEx(config, new BukkitImplementationInterface());
            if (oldManager != null) {
                oldManager.close();
            }
        } catch (IOException e) {
            throw new PEBKACException(t("Error while loading configuration: %s", e.getLocalizedMessage()));
        }
    }

    public CompletableFuture<Void> reload() {
        return Util.asyncFailableFuture(() -> {
            reloadSync();
            return null;
        }, manager.getAsyncExecutor());
    }

    public PermissionList getPermissionList() {
        return permsList;
    }

    public PermissionsEx getManager() {
        return this.manager;
    }

    public SubjectCache getUserSubjects() {
        return getManager().getSubjects(PermissionsEx.SUBJECTS_USER);
    }

    public SubjectCache getGroupSubjects() {
        return getManager().getSubjects(PermissionsEx.SUBJECTS_GROUP);
    }

    public void injectPermissible(Player player) {
        try {
            PEXPermissible permissible = new PEXPermissible(player, this);

            boolean success = false, found = false;
            for (PermissibleInjector injector : injectors) {
                if (injector.isApplicable(player)) {
                    found = true;
                    Permissible oldPerm = injector.inject(player, permissible);
                    if (oldPerm != null) {
                        permissible.setPreviousPermissible(oldPerm);
                        success = true;
                        break;
                    }
                }
            }

            if (!found) {
                getLogger().warning(lf(t("No Permissible injector found for your server implementation!")));
            } else if (!success) {
                getLogger().warning(lf(t("Unable to inject PEX's permissible for %s", player.getName())));
            }

            permissible.recalculatePermissions();

            if (success && getManager().hasDebugMode()) {
                getLogger().info(lf(t("Permissions handler for %s successfully injected", player.getName())));
            }
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, lf(t("Unable to inject permissible for %s", player.getName())), e);
        }
    }

    private void injectAllPermissibles() {
        for (Player player : getServer().getOnlinePlayers()) {
            injectPermissible(player);
        }
    }

    private void uninjectPermissible(Player player) {
        try {
            boolean success = false;
            for (PermissibleInjector injector : injectors) {
                if (injector.isApplicable(player)) {
                    Permissible pexPerm = injector.getPermissible(player);
                    if (pexPerm instanceof PEXPermissible) {
                        if (injector.inject(player, ((PEXPermissible) pexPerm).getPreviousPermissible()) != null) {
                            success = true;
                            break;
                        }
                    } else {
                        success = true;
                        break;
                    }
                }
            }

            if (!success) {
                getLogger().warning(lf(t("No Permissible injector found for your server implementation (while uninjecting for %s)!", player.getName())));
            } else if (getManager() != null && getManager().hasDebugMode()) {
                getLogger().info(lf(t("Permissions handler for %s successfully uninjected", player.getName())));
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void uninjectAllPermissibles() {
        getServer().getOnlinePlayers().forEach(this::uninjectPermissible);
    }

    private class BukkitImplementationInterface implements ImplementationInterface {
        private final Executor bukkitExecutor = runnable -> {
            if (enabled) {
                getServer().getScheduler()
                        .runTaskAsynchronously(PermissionsExPlugin.this, runnable);
            } else {
                runnable.run();
            }
        };

        @Override
        public File getBaseDirectory() {
            return getDataFolder();
        }

        @Override
        public Logger getLogger() {
            return logger;
        }

        @Override
        public DataSource getDataSourceForURL(String url) {
            return null;
        }

        /**
         * Get an executor to run tasks asynchronously on.
         *
         * @return The async executor
         */
        @Override
        public Executor getAsyncExecutor() {
            return bukkitExecutor;
        }

        @Override
        public void registerCommand(CommandSpec command) {
            PluginCommand cmd = getCommand(command.getAliases().get(0));
            if (cmd != null) {
                PEXBukkitCommand bukkitCommand = new PEXBukkitCommand(command, PermissionsExPlugin.this);
                cmd.setExecutor(bukkitCommand);
                cmd.setTabCompleter(bukkitCommand);
            }
        }

        @Override
        public Set<CommandSpec> getImplementationCommands() {
            return ImmutableSet.of(CommandSpec.builder()
                    .setAliases("reload", "rel")
                    .setDescription(t("Reload the PermissionsEx configuration"))
                    .setPermission("permissionsex.reload")
                    .setExecutor(new CommandExecutor() {
                        @Override
                        public <TextType> void execute(final Commander<TextType> src, CommandContext args) throws CommandException {
                            src.msg(t("Reloading PermissionsEx"));
                            reload()
                                    .thenRun(() -> src.msg(t("The reload was successful")))
                                    .exceptionally(t -> {
                                        src.error(t("An error occurred while reloading PEX: %s\n " +
                                                "Please see the server console for details", t.getLocalizedMessage()));
                                        logger.error(lf(t("An error occurred while reloading PEX (triggered by %s's command): %s",
                                                src.getName(), t.getLocalizedMessage())), t);
                                        return null;
                                    });
                        }
                    })
                    .build());
        }

        @Override
        public String getVersion() {
            return getDescription().getVersion();
        }

        @Override
        public Function<String, String> getNameTransformer(String type) {
            Function<String, String> xform = nameTransformerMap.get(type);
            if (xform == null) {
                xform = Function.identity();
            }
            return xform;
        }
    }
}