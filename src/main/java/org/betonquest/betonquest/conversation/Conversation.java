package org.betonquest.betonquest.conversation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.apache.commons.lang3.tuple.Pair;
import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.ConversationOptionEvent;
import org.betonquest.betonquest.api.PlayerConversationEndEvent;
import org.betonquest.betonquest.api.PlayerConversationStartEvent;
import org.betonquest.betonquest.api.config.quest.QuestPackage;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.config.Config;
import org.betonquest.betonquest.conversation.ConversationData.OptionType;
import org.betonquest.betonquest.database.Saver.Record;
import org.betonquest.betonquest.database.UpdateType;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.id.ConditionID;
import org.betonquest.betonquest.id.ConversationID;
import org.betonquest.betonquest.id.EventID;
import org.betonquest.betonquest.utils.PlayerConverter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Represents a conversation between player and NPC
 */
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyFields", "PMD.TooManyMethods", "PMD.CommentRequired", "PMD.CommentRequired"})
public class Conversation implements Listener {

    /**
     * The map of all active conversations.
     */
    private static final ConcurrentHashMap<Profile, Conversation> ACTIVE_CONVERSATIONS = new ConcurrentHashMap<>();

    /**
     * Custom {@link BetonQuestLogger} instance for this class.
     */
    private final BetonQuestLogger log;

    /**
     * Thread safety
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final OnlineProfile onlineProfile;

    private final Player player;

    private final QuestPackage pack;

    private final String language;

    private final Location location;

    private final ConversationID id;

    private final List<String> blacklist;

    private final Conversation conv;

    private final BetonQuest plugin;

    private final Map<Integer, String> current = new HashMap<>();

    private final boolean messagesDelaying;

    @SuppressWarnings("PMD.AvoidUsingVolatile")
    protected volatile ConversationState state = ConversationState.CREATED;

    private ConversationData data;

    private ConversationIO inOut;

    private String option;

    private Interceptor interceptor;

    /**
     * Starts a new conversation between player and npc at given location. It uses
     * starting options to determine where to start.
     *
     * @param log            the logger that will be used for logging
     * @param onlineProfile  the {@link OnlineProfile} of the player
     * @param conversationID ID of the conversation
     * @param location       location where the conversation has been started
     */
    public Conversation(final BetonQuestLogger log, final OnlineProfile onlineProfile, final ConversationID conversationID, final Location location) {
        this(log, onlineProfile, conversationID, location, null);
    }

    /**
     * Starts a new conversation between player and npc at given location,
     * starting with the given option. If the option is null, then it will start
     * from the beginning.
     *
     * @param log            the logger that will be used for logging
     * @param onlineProfile  the {@link OnlineProfile} of the player
     * @param conversationID ID of the conversation
     * @param location       location where the conversation has been started
     * @param option         ID of the option from where to start
     */
    public Conversation(final BetonQuestLogger log, final OnlineProfile onlineProfile, final ConversationID conversationID,
                        final Location location, final String option) {
        this.log = log;
        this.conv = this;
        this.plugin = BetonQuest.getInstance();
        this.onlineProfile = onlineProfile;
        this.player = onlineProfile.getPlayer();
        this.id = conversationID;
        this.pack = conversationID.getPackage();
        this.language = plugin.getPlayerData(onlineProfile).getLanguage();
        this.location = location;
        this.data = plugin.getConversation(conversationID);
        this.blacklist = plugin.getPluginConfig().getStringList("cmd_blacklist");
        this.messagesDelaying = "true".equalsIgnoreCase(plugin.getPluginConfig().getString("display_chat_after_conversation"));

        if (ACTIVE_CONVERSATIONS.containsKey(onlineProfile)) {
            this.log.debug(pack, onlineProfile + " is in conversation right now, returning.");
            return;
        }

        ACTIVE_CONVERSATIONS.put(onlineProfile, conv);

        String inputOption = option;
        final String[] options;
        if (inputOption == null) {
            options = null;
        } else {
            if (!inputOption.contains(".")) {
                inputOption = conversationID.getBaseID() + "." + inputOption;
            }
            options = new String[]{inputOption};
        }
        this.log.debug(pack, "Starting conversation '" + conversationID.getFullID() + "' for '" + onlineProfile + "'.");
        new Starter(options).runTaskAsynchronously(BetonQuest.getInstance());
    }

    /**
     * Checks if the player is in a conversation.
     *
     * @param profile the {@link Profile} of the player
     * @return if the player is the list of active conversations
     */
    public static boolean containsPlayer(final Profile profile) {
        return ACTIVE_CONVERSATIONS.containsKey(profile);
    }

    /**
     * Gets this player's active conversation.
     *
     * @param profile the {@link Profile} of the player
     * @return player's active conversation or null if there is no conversation
     */
    public static Conversation getConversation(final Profile profile) {
        return ACTIVE_CONVERSATIONS.get(profile);
    }

    /**
     * Chooses the first available option.
     *
     * @param options list of option pointers separated by commas
     * @param force   setting it to true will force the first option, even if
     *                conditions are not met
     */
    private void selectOption(final String[] options, final boolean force) {
        final String[] inputOptions = force ? new String[]{options[0]} : options;

        // get npc's text
        option = null;
        for (final String option : inputOptions) {
            final String convName;
            final String optionName;
            if (option.contains(".")) {
                final String[] parts = option.split("\\.");
                convName = parts[0];
                optionName = parts[1];
            } else {
                convName = data.getName();
                optionName = option;
            }
            final ConversationData currentData = plugin.getConversation(id);
            if (force || BetonQuest.conditions(this.onlineProfile, currentData.getConditionIDs(optionName, OptionType.NPC))) {
                this.option = optionName;
                data = currentData;
                break;
            }
        }
    }

    /**
     * Sends to the player the text said by NPC. It uses the selected option and
     * displays it. Note: this method now requires a prior call to
     * selectOption()
     */
    private void printNPCText() {
        // if there are no possible options, end conversation
        if (option == null) {
            new ConversationEnder().runTask(BetonQuest.getInstance());
            return;
        }

        String text = data.getText(onlineProfile, language, option, OptionType.NPC);
        // resolve variables
        for (final String variable : BetonQuest.resolveVariables(text)) {
            text = text.replace(variable, plugin.getVariableValue(data.getPackName(), variable, onlineProfile));
        }
        text = ChatColor.translateAlternateColorCodes('&', text);

        // print option to the player
        inOut.setNpcResponse(data.getQuester(language), text);

        new NPCEventRunner(option).runTask(BetonQuest.getInstance());
    }

    /**
     * Passes given string as answer from player in a conversation.
     *
     * @param number the message player has sent on chat
     */
    public void passPlayerAnswer(final int number) {

        inOut.clear();

        new PlayerEventRunner(current.get(number)).runTask(BetonQuest.getInstance());

        // clear hashmap
        current.clear();
    }

    /**
     * Prints answers the player can choose.
     *
     * @param options list of pointers to player options separated by commas
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    private void printOptions(final String... options) {
        final List<Pair<String, List<CompletableFuture<Boolean>>>> futuresOptions = new ArrayList<>();
        for (final String option : options) {
            final List<CompletableFuture<Boolean>> conditions = new ArrayList<>();
            for (final ConditionID conditionID : data.getConditionIDs(option, OptionType.PLAYER)) {
                final CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                        () -> BetonQuest.condition(onlineProfile, conditionID));
                conditions.add(future);
            }
            futuresOptions.add(Pair.of(option, conditions));
        }

        int optionsCount = 0;
        option:
        for (final Pair<String, List<CompletableFuture<Boolean>>> future : futuresOptions) {
            try {
                for (final CompletableFuture<Boolean> completableFuture : future.getValue()) {
                    if (!completableFuture.get(1, TimeUnit.SECONDS)) {
                        continue option;
                    }
                }
            } catch (final CancellationException | InterruptedException | ExecutionException | TimeoutException e) {
                log.reportException(pack, e);
                continue;
            }
            final String option = future.getKey();
            optionsCount++;
            // print reply and put it to the hashmap
            current.put(optionsCount, option);
            // replace variables with their values
            String text = data.getText(onlineProfile, language, option, OptionType.PLAYER);
            for (final String variable : BetonQuest.resolveVariables(text)) {
                text = text.replace(variable, plugin.getVariableValue(data.getPackName(), variable, onlineProfile));
            }
            text = ChatColor.translateAlternateColorCodes('&', text);

            inOut.addPlayerOption(text);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                inOut.display();
            }
        }.runTask(BetonQuest.getInstance());
        // end conversations if there are no possible options
        if (current.isEmpty()) {
            new ConversationEnder().runTask(BetonQuest.getInstance());
        }
    }

    /**
     * Ends conversation, firing final events and removing it from the list of
     * active conversations
     */
    public void endConversation() {
        if (state.isInactive()) {
            return;
        }
        if (plugin.getServer().isPrimaryThread()) {
            if (!lock.writeLock().tryLock()) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::endConversation);
                return;
            }
        } else {
            lock.writeLock().lock();
        }
        try {
            if (state.isInactive()) {
                return;
            }
            state = ConversationState.ENDED;

            log.debug(pack, "Ending conversation '" + conv.getID().getFullID() + "' for '" + onlineProfile + "'.");
            inOut.end();
            // fire final events
            for (final EventID event : data.getFinalEvents()) {
                BetonQuest.event(onlineProfile, event);
            }
            //only display status messages if conversationIO allows it
            if (conv.inOut.printMessages()) {
                // print message
                conv.inOut.print(Config.parseMessage(pack.getQuestPath(), onlineProfile, "conversation_end", data.getQuester(language)));
            }
            //play conversation end sound
            Config.playSound(onlineProfile, "end");

            // End interceptor after a second
            if (interceptor != null) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        interceptor.end();
                    }
                }.runTaskLaterAsynchronously(BetonQuest.getInstance(), 20);
            }

            // delete conversation
            ACTIVE_CONVERSATIONS.remove(onlineProfile);
            HandlerList.unregisterAll(this);

            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getServer().getPluginManager().callEvent(new PlayerConversationEndEvent(onlineProfile, Conversation.this));
                }
            }.runTask(BetonQuest.getInstance());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @return whenever this conversation has already ended
     */
    public boolean isEnded() {
        lock.readLock().lock();
        try {
            return state.isEnded();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Send message to player, bypassing any message delaying if needed
     *
     * @param message The message to send
     */
    public void sendMessage(final String message) {
        if (interceptor == null) {
            player.spigot().sendMessage(TextComponent.fromLegacyText(message));
        } else {
            interceptor.sendMessage(message);
        }
    }

    public void sendMessage(final BaseComponent... message) {
        if (interceptor == null) {
            player.spigot().sendMessage(message);
        } else {
            interceptor.sendMessage(message);
        }
    }

    /**
     * Checks if the movement of the player should be blocked.
     *
     * @return true if the movement should be blocked, false otherwise
     */
    public boolean isMovementBlock() {
        return data.isMovementBlocked();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (!event.getPlayer().equals(player)) {
            return;
        }
        final String cmdName = event.getMessage().split(" ")[0].substring(1);
        if (blacklist.contains(cmdName)) {
            event.setCancelled(true);
            try {
                Config.sendNotify(getPackage().getQuestPath(), PlayerConverter.getID(event.getPlayer()), "command_blocked", "command_blocked,error");
            } catch (final QuestRuntimeException e) {
                log.warn(pack, "The notify system was unable to play a sound for the 'command_blocked' category. Error was: '" + e.getMessage() + "'", e);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        // prevent damage to (or from) player while in conversation
        if (event.getEntity() instanceof Player && PlayerConverter.getID((Player) event.getEntity()).equals(onlineProfile)
                || event.getDamager() instanceof Player
                && PlayerConverter.getID((Player) event.getDamager()).equals(onlineProfile)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuit(final PlayerQuitEvent event) {
        // if player quits, end conversation (why keep listeners running?)
        if (event.getPlayer().equals(player)) {
            if (isMovementBlock()) {
                suspend();
            } else {
                endConversation();
            }
        }
    }

    /**
     * Instead of ending the conversation it saves it to the database, from
     * where it will be resumed after the player logs in again.
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void suspend() {
        if (state.isInactive()) {
            return;
        }

        lock.readLock().lock();
        try {
            if (state.isInactive()) {
                return;
            }
            if (inOut == null) {
                log.warn(pack, "Conversation IO is not loaded, conversation will end for player "
                        + onlineProfile.getProfileName());
                ACTIVE_CONVERSATIONS.remove(onlineProfile);
                HandlerList.unregisterAll(this);
                return;
            }
            inOut.end();

            // save the conversation to the database
            final PlayerConversationState state = new PlayerConversationState(id, option, location);
            plugin.getSaver().add(new Record(UpdateType.UPDATE_CONVERSATION, state.toString(), onlineProfile.getProfileUUID().toString()));

            // End interceptor
            if (interceptor != null) {
                interceptor.end();
            }

            // delete conversation
            ACTIVE_CONVERSATIONS.remove(onlineProfile);
            HandlerList.unregisterAll(this);

            new BukkitRunnable() {

                @Override
                public void run() {
                    Bukkit.getServer().getPluginManager().callEvent(new PlayerConversationEndEvent(onlineProfile, Conversation.this));
                }
            }.runTask(BetonQuest.getInstance());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @return the location where the conversation has been started
     */
    public Location getLocation() {
        return location;
    }

    /**
     * @return the ConversationIO object used by this conversation
     */
    public ConversationIO getIO() {
        return inOut;
    }

    /**
     * @return the data of the conversation
     */
    public ConversationData getData() {
        return data;
    }

    /**
     * @return the package containing this conversation
     */
    public QuestPackage getPackage() {
        return pack;
    }

    /**
     * @return the ID of the conversation
     */
    public ConversationID getID() {
        return id;
    }

    /**
     * @return the interceptor of the conversation
     */
    public Interceptor getInterceptor() {
        return interceptor;
    }

    /**
     * Starts the conversation, should be called asynchronously.
     */
    @SuppressWarnings({"PMD.NPathComplexity", "PMD.CyclomaticComplexity", "PMD.CognitiveComplexity"})
    private class Starter extends BukkitRunnable {

        private String[] options;

        public Starter(final String... options) {
            super();
            this.options = options == null ? null : Arrays.copyOf(options, options.length);
        }

        @Override
        public void run() {
            if (state.isStarted()) {
                return;
            }

            lock.writeLock().lock();
            try {
                if (state.isStarted()) {
                    return;
                }
                state = ConversationState.ACTIVE;
                // the conversation start event must be run on next tick
                final PlayerConversationStartEvent event = new PlayerConversationStartEvent(onlineProfile, conv);
                final Future<Void> eventDispatcherTask = Bukkit.getServer().getScheduler().callSyncMethod(BetonQuest.getInstance(), () -> {
                    Bukkit.getServer().getPluginManager().callEvent(event);
                    return null;
                });

                try {
                    eventDispatcherTask.get(1, TimeUnit.SECONDS);
                } catch (InterruptedException | TimeoutException exception) {
                    log.warn(pack, "Calling PlayerConversationStartEvent took too long.", exception);
                } catch (final ExecutionException exception) {
                    log.error(pack, "Error while calling PlayerConversationStartEvent.", exception);
                    ACTIVE_CONVERSATIONS.remove(onlineProfile);
                    return;
                }

                // stop the conversation if it's canceled
                if (event.isCancelled()) {
                    log.debug(pack, "Conversation '" + conv.getID().getFullID() + "' for '" + player.getPlayerProfile() + "' has been "
                            + "canceled because it's PlayerConversationStartEvent has been canceled.");
                    ACTIVE_CONVERSATIONS.remove(onlineProfile);
                    return;
                }

                // now the conversation should start no matter what;
                // the inOut can be safely instantiated; doing it before
                // would leave it active while the conversation is not
                // started, causing it to display "null" all the time
                try {
                    final String name = data.getConversationIO();
                    final Class<? extends ConversationIO> convIO = plugin.getConvIO(name);
                    conv.inOut = convIO.getConstructor(Conversation.class, OnlineProfile.class).newInstance(conv, onlineProfile);
                } catch (final InstantiationException | IllegalAccessException | IllegalArgumentException
                               | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                    log.warn(pack, "Error when loading conversation IO", e);
                    return;
                }

                // register listener for immunity and blocking commands
                Bukkit.getPluginManager().registerEvents(conv, BetonQuest.getInstance());

                // start interceptor if needed
                if (messagesDelaying) {
                    try {
                        final String name = data.getInterceptor();
                        final Class<? extends Interceptor> interceptor = plugin.getInterceptor(name);
                        conv.interceptor = interceptor.getConstructor(Conversation.class, OnlineProfile.class).newInstance(conv, onlineProfile);
                    } catch (final InstantiationException | IllegalAccessException | IllegalArgumentException
                                   | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                        log.warn(pack, "Error when loading interceptor", e);
                        return;
                    }
                }

                if (options == null) {
                    options = data.getStartingOptions();

                    // first select the option before sending message, so it
                    // knows which is used
                    selectOption(options, false);

                    // check whether to add a prefix
                    final String prefix = data.getPrefix(language, option);
                    String prefixName = null;
                    String[] prefixVariables = null;
                    if (prefix != null) {
                        prefixName = "conversation_prefix";
                        prefixVariables = new String[]{prefix};
                    }

                    //only display status messages if conversationIO allows it
                    if (conv.inOut.printMessages()) {
                        // print message about starting a conversation only if it
                        // is started, not resumed
                        conv.inOut.print(Config.parseMessage(pack.getQuestPath(), onlineProfile, "conversation_start", new String[]{data.getQuester(language)},
                                prefixName, prefixVariables));
                    }
                    //play the conversation start sound
                    Config.playSound(onlineProfile, "start");
                } else {
                    // don't forget to select the option prior to printing its text
                    selectOption(options, true);
                }

                // print NPC's text
                printNPCText();
                final ConversationOptionEvent optionEvent = new ConversationOptionEvent(PlayerConverter.getID(player), conv, option, conv.option);

                new BukkitRunnable() {

                    @Override
                    public void run() {
                        Bukkit.getPluginManager().callEvent(optionEvent);
                    }
                }.runTask(BetonQuest.getInstance());
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    /**
     * Fires events from the option. Should be called in the main thread.
     */
    private class NPCEventRunner extends BukkitRunnable {

        private final String option;

        public NPCEventRunner(final String option) {
            super();
            this.option = option;
        }

        @Override
        public void run() {
            // fire events
            for (final EventID event : data.getEventIDs(onlineProfile, option, OptionType.NPC)) {
                BetonQuest.event(onlineProfile, event);
            }
            new OptionPrinter(option).runTaskAsynchronously(BetonQuest.getInstance());
        }
    }

    /**
     * Fires events from the option. Should be called in the main thread.
     */
    private class PlayerEventRunner extends BukkitRunnable {

        private final String option;

        public PlayerEventRunner(final String option) {
            super();
            this.option = option;
        }

        @Override
        public void run() {
            // fire events
            for (final EventID event : data.getEventIDs(onlineProfile, option, OptionType.PLAYER)) {
                BetonQuest.event(onlineProfile, event);
            }
            new ResponsePrinter(option).runTaskAsynchronously(BetonQuest.getInstance());
        }
    }

    /**
     * Prints the NPC response to the player. Should be called asynchronously.
     */
    private class ResponsePrinter extends BukkitRunnable {

        private final String option;

        public ResponsePrinter(final String option) {
            super();
            this.option = option;
        }

        @Override
        public void run() {
            if (!state.isActive()) {
                return;
            }
            lock.readLock().lock();
            try {
                if (!state.isActive()) {
                    return;
                }
                // don't forget to select the option prior to printing its text
                selectOption(data.getPointers(onlineProfile, option, OptionType.PLAYER), false);
                // print to player npc's answer
                printNPCText();
                final ConversationOptionEvent event = new ConversationOptionEvent(PlayerConverter.getID(player), conv, option, conv.option);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Bukkit.getServer().getPluginManager().callEvent(event);
                    }
                }.runTask(BetonQuest.getInstance());
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    /**
     * Prints the options to the player. Should be called asynchronously.
     */
    private class OptionPrinter extends BukkitRunnable {

        private final String option;

        public OptionPrinter(final String option) {
            super();
            this.option = option;
        }

        @Override
        public void run() {
            if (!state.isActive()) {
                return;
            }
            lock.readLock().lock();
            try {
                if (!state.isActive()) {
                    return;
                }
                printOptions(data.getPointers(onlineProfile, option, OptionType.NPC));
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    /**
     * Ends the conversation. Should be called in the main thread.
     */
    private class ConversationEnder extends BukkitRunnable {
        public ConversationEnder() {
            super();
        }

        @Override
        public void run() {
            endConversation();
        }
    }
}
