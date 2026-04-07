/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jdautilities.command.impl.CommandClientImpl;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Listener extends ListenerAdapter {
    private final Bot bot;

    public Listener(Bot bot) {
        this.bot = bot;
    }

    @Override
    public void onReady(ReadyEvent event) {
        if (event.getJDA().getGuildCache().isEmpty()) {
            Logger log = LoggerFactory.getLogger("MusicBot");
            log.warn("This bot is not on any guilds! Use the following link to add the bot to your guilds!");
            log.warn(event.getJDA().getInviteUrl(JMusicBot.RECOMMENDED_PERMS));
        }

        // Clear any existing global slash commands
        event.getJDA().updateCommands().queue();

        // Register slash commands to every guild the bot is currently in
        CommandClientImpl client = (CommandClientImpl) bot.getClient();
        event.getJDA().getGuilds().forEach(guild -> client.upsertInteractions(event.getJDA(), guild.getId()));

        event.getJDA().getGuilds().forEach((guild) -> {
            try {
                String defpl = bot.getSettingsManager().getSettings(guild).getDefaultPlaylist();
                VoiceChannel vc = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
                if (defpl != null && vc != null && bot.getPlayerManager().setUpHandler(guild).playFromDefault()) {
                    guild.getAudioManager().openAudioConnection(vc);
                }
            } catch (Exception ignore) {
            }
        });

        if (bot.getConfig().useUpdateAlerts()) {
            bot.getThreadpool().scheduleWithFixedDelay(() -> {
                try {
                    User owner = bot.getJDA().retrieveUserById(bot.getConfig().getOwnerId()).complete();
                    String currentVersion = OtherUtil.getCurrentVersion();
                    String latestVersion = OtherUtil.getLatestVersion();
                    if (latestVersion != null && !currentVersion.equalsIgnoreCase(latestVersion)) {
                        String msg = String.format(OtherUtil.NEW_VERSION_AVAILABLE, currentVersion, latestVersion);
                        owner.openPrivateChannel().queue(pc -> pc.sendMessage(msg).queue());
                    }
                } catch (Exception ignored) {
                } // ignored
            }, 0, 24, TimeUnit.HOURS);
        }
        bot.resetGame();
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        bot.getNowplayingHandler().onMessageDelete(event.getGuild(), event.getMessageIdLong());
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        bot.getAloneInVoiceHandler().onVoiceUpdate(event);
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        bot.shutdown();
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        // Register slash commands to the newly joined guild immediately
        ((CommandClientImpl) bot.getClient()).upsertInteractions(event.getJDA(), event.getGuild().getId());
    }

    // ── Player Panel — button interactions ────────────────────────────────────

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event)
    {
        String id = event.getComponentId();
        if (!id.startsWith("panel:")) return;

        String[] parts = id.split(":", 3);
        if (parts.length < 3) { event.deferEdit().queue(); return; }

        String action = parts[1];
        long guildId;
        try { guildId = Long.parseLong(parts[2]); }
        catch (NumberFormatException e) { event.deferEdit().queue(); return; }

        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) { event.deferEdit().queue(); return; }

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null) { event.deferEdit().queue(); return; }

        boolean isDJ = isPanelDJ(event);

        switch (action)
        {
            case "pauseresume":
                if (!isDJ) { event.reply(bot.getClient().getError() + " Only DJs can pause or resume the player!").setEphemeral(true).queue(); return; }
                if (handler.getPlayer().getPlayingTrack() == null) { event.deferEdit().queue(); return; }
                handler.getPlayer().setPaused(!handler.getPlayer().isPaused());
                refreshPanelMessage(event, handler);
                break;

            case "skip":
                if (handler.getPlayer().getPlayingTrack() == null) { event.deferEdit().queue(); return; }
                handlePanelSkip(event, handler, guild, isDJ);
                break;

            case "stop":
                if (!isDJ) { event.reply(bot.getClient().getError() + " Only DJs can stop the player!").setEphemeral(true).queue(); return; }
                handler.stopAndClear();
                guild.getAudioManager().closeAudioConnection();
                bot.getNowplayingHandler().clearLastNPMessage(guild);
                event.editMessage(MessageEditData.fromCreateData(handler.getNoMusicPlaying(event.getJDA()))).queue();
                break;

            case "shuffle":
                if (handler.getPlayer().getPlayingTrack() == null) { event.deferEdit().queue(); return; }
                int shuffled = handler.getQueue().shuffle(event.getUser().getIdLong());
                String shuffleMsg;
                if (shuffled == 0)
                    shuffleMsg = bot.getClient().getError() + " You don't have any music in the queue to shuffle!";
                else if (shuffled == 1)
                    shuffleMsg = bot.getClient().getWarning() + " You only have one song in the queue!";
                else
                    shuffleMsg = bot.getClient().getSuccess() + " Shuffled your **" + shuffled + "** entries.";
                event.reply(shuffleMsg).setEphemeral(true).queue();
                break;

            case "repeat":
                if (!isDJ) { event.reply(bot.getClient().getError() + " Only DJs can change the repeat mode!").setEphemeral(true).queue(); return; }
                Settings repeatSettings = bot.getSettingsManager().getSettings(guild);
                RepeatMode current = repeatSettings.getRepeatMode();
                RepeatMode next = current == RepeatMode.OFF ? RepeatMode.ALL
                        : current == RepeatMode.ALL ? RepeatMode.SINGLE : RepeatMode.OFF;
                repeatSettings.setRepeatMode(next);
                if (handler.getPlayer().getPlayingTrack() != null)
                    refreshPanelMessage(event, handler);
                else
                    event.reply(bot.getClient().getSuccess() + " Repeat mode is now `" + next.getUserFriendlyName() + "`").setEphemeral(true).queue();
                break;

            default:
                event.deferEdit().queue();
        }
    }

    private boolean isPanelDJ(ButtonInteractionEvent event)
    {
        if (event.getGuild() == null) return true;
        if (event.getUser().getId().equals(bot.getClient().getOwnerId())) return true;
        if (event.getMember().hasPermission(Permission.MANAGE_SERVER)) return true;
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        Role dj = s.getRole(event.getGuild());
        return dj != null && (event.getMember().getRoles().contains(dj) || dj.getIdLong() == event.getGuild().getIdLong());
    }

    private void handlePanelSkip(ButtonInteractionEvent event, AudioHandler handler, Guild guild, boolean isDJ)
    {
        RequestMetadata rm = handler.getRequestMetadata();
        double skipRatio = bot.getSettingsManager().getSettings(guild).getSkipRatio();
        if (skipRatio == -1) skipRatio = bot.getConfig().getSkipRatio();

        // Force-skip: DJ, track requester, or skip ratio disabled
        if (isDJ || event.getUser().getIdLong() == rm.getOwner() || skipRatio == 0)
        {
            String title = handler.getPlayer().getPlayingTrack().getInfo().title;
            event.deferEdit().queue();
            handler.getPlayer().stopTrack();
            event.getHook().sendMessage(bot.getClient().getSuccess() + " Skipped **" + FormatUtil.filter(title) + "**").setEphemeral(true).queue();
            return;
        }

        // Vote skip
        int listeners = (int) guild.getSelfMember().getVoiceState().getChannel().getMembers().stream()
                .filter(m -> !m.getUser().isBot() && !m.getVoiceState().isDeafened()).count();
        int required = (int) Math.ceil(listeners * skipRatio);

        if (handler.getVotes().contains(event.getUser().getId()))
        {
            int skippers = (int) guild.getSelfMember().getVoiceState().getChannel().getMembers().stream()
                    .filter(m -> handler.getVotes().contains(m.getUser().getId())).count();
            event.reply(bot.getClient().getWarning() + " You already voted to skip! `[" + skippers + "/" + required + " votes]`")
                    .setEphemeral(true).queue();
            return;
        }

        handler.getVotes().add(event.getUser().getId());
        int skippers = (int) guild.getSelfMember().getVoiceState().getChannel().getMembers().stream()
                .filter(m -> handler.getVotes().contains(m.getUser().getId())).count();

        if (skippers >= required)
        {
            String title = handler.getPlayer().getPlayingTrack().getInfo().title;
            event.deferEdit().queue();
            handler.getPlayer().stopTrack();
            event.getHook().sendMessage(bot.getClient().getSuccess() + " Skipped **" + FormatUtil.filter(title)
                    + "** (vote passed: " + skippers + "/" + required + ")").setEphemeral(true).queue();
        }
        else
        {
            event.reply(bot.getClient().getSuccess() + " Vote to skip recorded! `[" + skippers + "/" + required + " needed]`")
                    .setEphemeral(true).queue();
        }
    }

    private void refreshPanelMessage(ButtonInteractionEvent event, AudioHandler handler)
    {
        net.dv8tion.jda.api.utils.messages.MessageCreateData msg = handler.getNowPlaying(event.getJDA());
        if (msg != null)
            event.editMessage(MessageEditData.fromCreateData(msg)).queue();
        else
            event.editMessage(MessageEditData.fromCreateData(handler.getNoMusicPlaying(event.getJDA()))).queue();
    }

    // ── Autocomplete ──────────────────────────────────────────────────────────

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event)
    {
        String command = event.getName();
        String option  = event.getFocusedOption().getName();
        String value   = event.getFocusedOption().getValue().toLowerCase();

        List<Command.Choice> choices = new ArrayList<>();

        if ("play".equals(command) && "query".equals(option))
        {
            // Suggest saved playlist names with the "playlist:" prefix
            String filter = value.startsWith("playlist:") ? value.substring(9) : value;
            bot.getPlaylistLoader().getPlaylistNames().stream()
                    .filter(name -> filter.isEmpty() || name.toLowerCase().contains(filter))
                    .limit(25)
                    .forEach(name -> choices.add(new Command.Choice("playlist: " + name, "playlist:" + name)));
            event.replyChoices(choices).queue();
        }
        else if (event.getGuild() != null && isQueuePositionOption(command, option))
        {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if (handler != null)
            {
                List<QueuedTrack> queue = handler.getQueue().getList();
                boolean intOption = "skipto".equals(command) || "movetrack".equals(command);

                // Add "ALL" shortcut for the remove command
                if ("remove".equals(command) && (value.isEmpty() || "all".startsWith(value)))
                    choices.add(new Command.Choice("ALL \u2014 remove all your tracks", "ALL"));

                for (int i = 0; i < queue.size() && choices.size() < 25; i++)
                {
                    String title  = queue.get(i).getTrack().getInfo().title;
                    String numStr = String.valueOf(i + 1);
                    String label  = numStr + " \u2014 " + title;
                    if (label.length() > 100) label = label.substring(0, 97) + "...";
                    if (value.isEmpty() || numStr.startsWith(value) || title.toLowerCase().contains(value))
                    {
                        if (intOption)
                            choices.add(new Command.Choice(label, (long) (i + 1)));
                        else
                            choices.add(new Command.Choice(label, numStr));
                    }
                }
            }
            event.replyChoices(choices).queue();
        }
        else
        {
            event.replyChoices().queue();
        }
    }

    private static boolean isQueuePositionOption(String command, String option)
    {
        return ("remove".equals(command)    && "position".equals(option))
            || ("skipto".equals(command)    && "position".equals(option))
            || ("movetrack".equals(command) && ("from".equals(option) || "to".equals(option)));
    }
}
