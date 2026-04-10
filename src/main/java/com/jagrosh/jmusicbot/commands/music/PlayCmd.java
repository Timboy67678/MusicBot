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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.audio.SpotifyHandler;
import com.jagrosh.jmusicbot.audio.SpotifyHandler.SpotifyResult;
import com.jagrosh.jmusicbot.audio.SpotifyHandler.SpotifyTrackInfo;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jdautilities.menu.ButtonMenu;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlayCmd extends MusicCommand {
    private final static String LOAD = "\uD83D\uDCE5"; // 📥
    private final static String CANCEL = "\uD83D\uDEAB"; // 🚫
    private static final Logger LOG = LoggerFactory.getLogger(PlayCmd.class);

    private final String loadingEmoji;

    public PlayCmd(Bot bot) {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.name = "play";
        this.arguments = "<title|URL|subcommand>";
        this.help = "plays the provided song";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
        this.options = Arrays.asList(
                new OptionData(OptionType.STRING, "query",
                        "Song title, URL, or 'playlist:<name>' to load a saved playlist", false)
                        .setAutoComplete(true));
    }

    @Override
    public void doCommand(CommandEvent event) {
        if (event.getArgs().isEmpty() && event.getMessage().getAttachments().isEmpty()) {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if (handler.getPlayer().getPlayingTrack() != null && handler.getPlayer().isPaused()) {
                if (DJCommand.checkDJPermission(event)) {
                    handler.getPlayer().setPaused(false);
                    event.replySuccess("Resumed **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**.");
                } else
                    event.replyError("Only DJs can unpause the player!");
                return;
            }
            StringBuilder builder = new StringBuilder(event.getClient().getWarning() + " Play Commands:\n");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name)
                    .append(" <song title>` - plays the first result from Youtube");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name)
                    .append(" <URL>` - plays the provided song, playlist, or stream");
            for (Command cmd : children)
                builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" ")
                        .append(cmd.getName()).append(" ").append(cmd.getArguments()).append("` - ")
                        .append(cmd.getHelp());
            event.reply(builder.toString());
            return;
        }
        String args = event.getArgs().startsWith("<") && event.getArgs().endsWith(">")
                ? event.getArgs().substring(1, event.getArgs().length() - 1)
                : event.getArgs().isEmpty() ? event.getMessage().getAttachments().get(0).getUrl() : event.getArgs();
        // ── Spotify interception ──────────────────────────────────────────────
        if (bot.getSpotifyHandler() != null && bot.getSpotifyHandler().isSpotifyUrl(args)) {
            event.reply(loadingEmoji + " Loading Spotify link... `[" + args + "]`",
                    m -> bot.getThreadpool().submit(() -> handleSpotifyPrefix(args, m, event)));
            return;
        }
        // ── Normal loading ────────────────────────────────────────────────────
        event.reply(loadingEmoji + " Loading... `[" + args + "]`", m -> bot.getPlayerManager()
                .loadItemOrdered(event.getGuild(), args, new ResultHandler(m, event, false)));
    }

    private class ResultHandler implements AudioLoadResultHandler {
        private final Message m;
        private final CommandEvent event;
        private final boolean ytsearch;

        private ResultHandler(Message m, CommandEvent event, boolean ytsearch) {
            this.m = m;
            this.event = event;
            this.ytsearch = ytsearch;
        }

        private void loadSingle(AudioTrack track, AudioPlaylist playlist) {
            if (bot.getConfig().isTooLong(track)) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning() + " This track (**"
                        + track.getInfo().title + "**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration()) + "` > `"
                        + TimeUtil.formatTime(bot.getConfig().getMaxSeconds() * 1000) + "`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event))) + 1;
            String addMsg = FormatUtil.filter(event.getClient().getSuccess() + " Added **" + track.getInfo().title
                    + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) "
                    + (pos == 0 ? "to begin playing" : " to the queue at position " + pos));
            if (playlist == null
                    || !event.getSelfMember().hasPermission(event.getTextChannel(), Permission.MESSAGE_ADD_REACTION))
                m.editMessage(addMsg).queue();
            else {
                new ButtonMenu.Builder()
                        .setText(addMsg + "\n" + event.getClient().getWarning() + " This track has a playlist of **"
                                + playlist.getTracks().size() + "** tracks attached. Select " + LOAD
                                + " to load playlist.")
                        .setChoices(LOAD, CANCEL)
                        .setEventWaiter(bot.getWaiter())
                        .setTimeout(30, TimeUnit.SECONDS)
                        .setAction(re -> {
                            if (re.getName().equals(LOAD))
                                m.editMessage(addMsg + "\n" + event.getClient().getSuccess() + " Loaded **"
                                        + loadPlaylist(playlist, track) + "** additional tracks!").queue();
                            else
                                m.editMessage(addMsg).queue();
                        }).setFinalAction(m -> {
                            try {
                                m.clearReactions().queue();
                            } catch (PermissionException ignore) {
                            }
                        }).build().display(m);
            }
        }

        private int loadPlaylist(AudioPlaylist playlist, AudioTrack exclude) {
            int[] count = { 0 };
            playlist.getTracks().stream().forEach((track) -> {
                if (!bot.getConfig().isTooLong(track) && !track.equals(exclude)) {
                    AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                    count[0]++;
                }
            });
            return count[0];
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            loadSingle(track, null);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            if (playlist.getTracks().size() == 1 || playlist.isSearchResult()) {
                AudioTrack single = playlist.getSelectedTrack() == null ? playlist.getTracks().get(0)
                        : playlist.getSelectedTrack();
                loadSingle(single, null);
            } else if (playlist.getSelectedTrack() != null) {
                AudioTrack single = playlist.getSelectedTrack();
                loadSingle(single, playlist);
            } else {
                int count = loadPlaylist(playlist, null);
                if (playlist.getTracks().size() == 0) {
                    m.editMessage(
                            FormatUtil
                                    .filter(event.getClient().getWarning() + " The playlist "
                                            + (playlist.getName() == null ? ""
                                                    : "(**" + playlist.getName()
                                                            + "**) ")
                                            + " could not be loaded or contained 0 entries"))
                            .queue();
                } else if (count == 0) {
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning() + " All entries in this playlist "
                            + (playlist.getName() == null ? ""
                                    : "(**" + playlist.getName()
                                            + "**) ")
                            + "were longer than the allowed maximum (`" + bot.getConfig().getMaxTime() + "`)")).queue();
                } else {
                    m.editMessage(FormatUtil.filter(event.getClient().getSuccess() + " Found "
                            + (playlist.getName() == null ? "a playlist" : "playlist **" + playlist.getName() + "**")
                            + " with `"
                            + playlist.getTracks().size() + "` entries; added to the queue!"
                            + (count < playlist.getTracks().size() ? "\n" + event.getClient().getWarning()
                                    + " Tracks longer than the allowed maximum (`"
                                    + bot.getConfig().getMaxTime() + "`) have been omitted." : "")))
                            .queue();
                }
            }
        }

        @Override
        public void noMatches() {
            if (ytsearch)
                m.editMessage(FormatUtil
                        .filter(event.getClient().getWarning() + " No results found for `" + event.getArgs() + "`."))
                        .queue();
            else
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + event.getArgs(),
                        new ResultHandler(m, event, true));
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            if (throwable.severity == Severity.COMMON)
                m.editMessage(event.getClient().getError() + " Error loading: " + throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError() + " Error loading track.").queue();
        }
    }

    public class PlaylistCmd extends MusicCommand {
        public PlaylistCmd(Bot bot) {
            super(bot);
            this.name = "playlist";
            this.aliases = new String[] { "pl" };
            this.arguments = "<name>";
            this.help = "plays the provided playlist";
            this.beListening = true;
            this.bePlaying = false;
        }

        @Override
        public void doCommand(CommandEvent event) {
            if (event.getArgs().isEmpty()) {
                event.reply(event.getClient().getError() + " Please include a playlist name.");
                return;
            }
            Playlist playlist = bot.getPlaylistLoader().getPlaylist(event.getArgs());
            if (playlist == null) {
                event.replyError("I could not find `" + event.getArgs() + ".txt` in the Playlists folder.");
                return;
            }
            event.getChannel().sendMessage(loadingEmoji + " Loading playlist **" + event.getArgs() + "**... ("
                    + playlist.getItems().size() + " items)").queue(m -> {
                        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
                        playlist.loadTracks(bot.getPlayerManager(),
                                (at) -> handler
                                        .addTrack(new QueuedTrack(at, RequestMetadata.fromResultHandler(at, event))),
                                () -> {
                                    StringBuilder builder = new StringBuilder(playlist.getTracks().isEmpty()
                                            ? event.getClient().getWarning() + " No tracks were loaded!"
                                            : event.getClient().getSuccess() + " Loaded **"
                                                    + playlist.getTracks().size() + "** tracks!");
                                    if (!playlist.getErrors().isEmpty())
                                        builder.append("\nThe following tracks failed to load:");
                                    playlist.getErrors()
                                            .forEach(err -> builder.append("\n`[").append(err.getIndex() + 1)
                                                    .append("]` **").append(err.getItem()).append("**: ")
                                                    .append(err.getReason()));
                                    String str = builder.toString();
                                    if (str.length() > 2000)
                                        str = str.substring(0, 1994) + " (...)";
                                    m.editMessage(FormatUtil.filter(str)).queue();
                                });
                    });
        }

        @Override
        public void doCommand(SlashCommandEvent event) {
            // Handled inline by the parent /play command via 'playlist:<name>' query prefix
            event.reply(event.getClient().getWarning() + " Use `/play query:playlist:<name>` to play a saved playlist.")
                    .setEphemeral(true).queue();
        }
    }

    // ── Slash command implementation ──────────────────────────────────────────

    @Override
    public void doCommand(SlashCommandEvent event) {
        String query = event.optString("query", "");

        // No query — resume if paused, otherwise show help
        if (query.isEmpty()) {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if (handler.getPlayer().getPlayingTrack() != null && handler.getPlayer().isPaused()) {
                if (DJCommand.checkDJPermission(event)) {
                    handler.getPlayer().setPaused(false);
                    event.reply(event.getClient().getSuccess() + " Resumed **"
                            + handler.getPlayer().getPlayingTrack().getInfo().title + "**.").queue();
                } else
                    event.reply(event.getClient().getError() + " Only DJs can unpause the player!").setEphemeral(true)
                            .queue();
                return;
            }
            event.reply(event.getClient().getWarning() + " Play Commands:\n"
                    + "`/play query:<song title>` — plays the first YouTube result\n"
                    + "`/play query:<URL>` — plays the provided song, playlist, or stream\n"
                    + "`/play query:playlist:<name>` — plays a saved bot playlist").queue();
            return;
        }

        // Handle built-in playlist shorthand: "playlist:<name>"
        if (query.toLowerCase().startsWith("playlist:")) {
            String playlistName = query.substring(9).trim();
            Playlist playlist = bot.getPlaylistLoader().getPlaylist(playlistName);
            if (playlist == null) {
                event.reply(event.getClient().getError() + " I could not find `" + playlistName
                        + ".txt` in the Playlists folder.").setEphemeral(true).queue();
                return;
            }
            event.deferReply().queue();
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            playlist.loadTracks(bot.getPlayerManager(),
                    (at) -> handler.addTrack(new QueuedTrack(at, RequestMetadata.fromResultHandler(at, event))), () -> {
                        StringBuilder builder = new StringBuilder(playlist.getTracks().isEmpty()
                                ? event.getClient().getWarning() + " No tracks were loaded!"
                                : event.getClient().getSuccess() + " Loaded **" + playlist.getTracks().size()
                                        + "** tracks from **" + playlistName + "**!");
                        if (!playlist.getErrors().isEmpty())
                            builder.append("\nThe following tracks failed to load:");
                        playlist.getErrors().forEach(err -> builder.append("\n`[").append(err.getIndex() + 1)
                                .append("]` **").append(err.getItem()).append("**: ").append(err.getReason()));
                        String str = builder.toString();
                        if (str.length() > 2000)
                            str = str.substring(0, 1994) + " (...)";
                        event.getHook().editOriginal(FormatUtil.filter(str)).queue();
                    });
            return;
        }

        // Strip angle brackets from URLs
        String args = query.startsWith("<") && query.endsWith(">")
                ? query.substring(1, query.length() - 1)
                : query;

        // ── Spotify interception ──────────────────────────────────────────────
        if (bot.getSpotifyHandler() != null && bot.getSpotifyHandler().isSpotifyUrl(args)) {
            event.deferReply().queue();
            bot.getThreadpool().submit(() -> handleSpotifySlash(args, event));
            return;
        }

        // Defer reply since audio loading is asynchronous
        event.deferReply().queue();
        bot.getPlayerManager().loadItemOrdered(event.getGuild(), args, new SlashResultHandler(event, args, false));
    }

    /**
     * Result handler for slash /play invocations — uses InteractionHook instead of
     * Message.
     */
    private class SlashResultHandler implements AudioLoadResultHandler {
        private final SlashCommandEvent event;
        private final String query;
        private final boolean ytsearch;

        private SlashResultHandler(SlashCommandEvent event, String query, boolean ytsearch) {
            this.event = event;
            this.query = query;
            this.ytsearch = ytsearch;
        }

        private void loadSingle(AudioTrack track, AudioPlaylist playlist) {
            if (bot.getConfig().isTooLong(track)) {
                event.getHook()
                        .editOriginal(FormatUtil.filter(event.getClient().getWarning() + " This track (**"
                                + track.getInfo().title + "**) is longer than the allowed maximum: `"
                                + TimeUtil.formatTime(track.getDuration()) + "` > `"
                                + TimeUtil.formatTime(bot.getConfig().getMaxSeconds() * 1000) + "`"))
                        .queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event))) + 1;
            String addMsg = FormatUtil.filter(event.getClient().getSuccess() + " Added **" + track.getInfo().title
                    + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) "
                    + (pos == 0 ? "to begin playing" : " to the queue at position " + pos));

            if (playlist == null) {
                event.getHook().editOriginal(addMsg).queue();
            } else {
                // Offer to load the full playlist using native JDA 6 buttons
                String userId = event.getUser().getId();
                Button loadBtn = Button.success("play:load:" + userId, LOAD + " Load Full Playlist");
                Button cancelBtn = Button.danger("play:cancel:" + userId, CANCEL + " Just This Track");
                event.getHook().editOriginal(addMsg + "\n" + event.getClient().getWarning()
                        + " This track has a playlist of **" + playlist.getTracks().size() + "** tracks attached.")
                        .setComponents(ActionRow.of(loadBtn, cancelBtn))
                        .queue(m -> bot.getWaiter().waitForEvent(
                                ButtonInteractionEvent.class,
                                e -> e.getComponentId().startsWith("play:") && e.getUser().getId().equals(userId),
                                e -> {
                                    if (e.getComponentId().equals("play:load:" + userId)) {
                                        int loaded = loadPlaylistTracks(playlist, track);
                                        e.editMessage(addMsg + "\n" + event.getClient().getSuccess() + " Loaded **"
                                                + loaded + "** additional tracks!").setComponents().queue();
                                    } else {
                                        e.editMessage(addMsg).setComponents().queue();
                                    }
                                },
                                30, TimeUnit.SECONDS,
                                () -> m.editMessageComponents().queue()));
            }
        }

        private int loadPlaylistTracks(AudioPlaylist playlist, AudioTrack exclude) {
            int[] count = { 0 };
            playlist.getTracks().stream().filter(t -> !bot.getConfig().isTooLong(t) && !t.equals(exclude))
                    .forEach(t -> {
                        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
                        handler.addTrack(new QueuedTrack(t, RequestMetadata.fromResultHandler(t, event)));
                        count[0]++;
                    });
            return count[0];
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            loadSingle(track, null);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            if (playlist.getTracks().size() == 1 || playlist.isSearchResult()) {
                AudioTrack single = playlist.getSelectedTrack() == null ? playlist.getTracks().get(0)
                        : playlist.getSelectedTrack();
                loadSingle(single, null);
            } else if (playlist.getSelectedTrack() != null) {
                loadSingle(playlist.getSelectedTrack(), playlist);
            } else {
                int count = loadPlaylistTracks(playlist, null);
                if (playlist.getTracks().isEmpty())
                    event.getHook()
                            .editOriginal(FormatUtil.filter(event.getClient().getWarning() + " The playlist "
                                    + (playlist.getName() == null ? "" : "(**" + playlist.getName() + "**) ")
                                    + " could not be loaded or contained 0 entries"))
                            .queue();
                else if (count == 0)
                    event.getHook()
                            .editOriginal(FormatUtil.filter(event.getClient().getWarning()
                                    + " All entries in this playlist "
                                    + (playlist.getName() == null ? "" : "(**" + playlist.getName() + "**) ")
                                    + "were longer than the allowed maximum (`" + bot.getConfig().getMaxTime() + "`)"))
                            .queue();
                else
                    event.getHook().editOriginal(FormatUtil.filter(event.getClient().getSuccess() + " Found "
                            + (playlist.getName() == null ? "a playlist" : "playlist **" + playlist.getName() + "**")
                            + " with `"
                            + playlist.getTracks().size() + "` entries; added to the queue!"
                            + (count < playlist.getTracks().size() ? "\n" + event.getClient().getWarning()
                                    + " Tracks longer than the allowed maximum (`" + bot.getConfig().getMaxTime()
                                    + "`) have been omitted." : "")))
                            .queue();
            }
        }

        @Override
        public void noMatches() {
            if (ytsearch)
                event.getHook()
                        .editOriginal(FormatUtil
                                .filter(event.getClient().getWarning() + " No results found for `" + query + "`."))
                        .queue();
            else
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + query,
                        new SlashResultHandler(event, query, true));
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            if (throwable.severity == Severity.COMMON)
                event.getHook().editOriginal(event.getClient().getError() + " Error loading: " + throwable.getMessage())
                        .queue();
            else
                event.getHook().editOriginal(event.getClient().getError() + " Error loading track.").queue();
        }
    }

    // ── Spotify helpers ───────────────────────────────────────────────────────

    /**
     * Called off the JDA thread after sending a loading message.
     * Resolves the Spotify URL and queues searches for prefix-command users.
     */
    private void handleSpotifyPrefix(String url, Message m, CommandEvent event) {
        try {
            SpotifyResult result = bot.getSpotifyHandler().resolve(url);
            List<SpotifyTrackInfo> tracks = result.tracks;
            if (tracks.isEmpty()) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " No tracks found for that Spotify link.")).queue();
                return;
            }
            if (tracks.size() == 1) {
                // Single track — hand off to the existing ResultHandler (ytsearch)
                bot.getPlayerManager().loadItemOrdered(event.getGuild(),
                        "ytsearch:" + tracks.get(0).toSearchQuery(),
                        new ResultHandler(m, event, true));
            } else {
                // Playlist / album — ordered concurrent loading
                String displayName = result.name != null ? result.name : "Spotify playlist";
                m.editMessage(loadingEmoji + " Loading **" + displayName + "**... ("
                        + tracks.size() + " tracks)").queue();
                AtomicReferenceArray<AudioTrack> orderedResults = new AtomicReferenceArray<>(tracks.size());
                AtomicInteger done = new AtomicInteger(0);
                for (int i = 0; i < tracks.size(); i++) {
                    bot.getPlayerManager().loadItemOrdered(event.getGuild(),
                            "ytsearch:" + tracks.get(i).toSearchQuery(),
                            new SpotifyPrefixResultHandler(m, event, orderedResults, i, done, tracks.size(), result.name));
                }
            }
        } catch (Exception ex) {
            LOG.error("Failed to resolve Spotify URL: {}", url, ex);
            m.editMessage(FormatUtil.filter(event.getClient().getError()
                    + " Could not load Spotify link: " + ex.getMessage())).queue();
        }
    }

    /**
     * Called off the JDA thread after deferring the slash-command reply.
     * Resolves the Spotify URL and queues searches for slash-command users.
     */
    private void handleSpotifySlash(String url, SlashCommandEvent event) {
        try {
            SpotifyResult result = bot.getSpotifyHandler().resolve(url);
            List<SpotifyTrackInfo> tracks = result.tracks;
            if (tracks.isEmpty()) {
                event.getHook().editOriginal(FormatUtil.filter(event.getClient().getWarning()
                        + " No tracks found for that Spotify link.")).queue();
                return;
            }
            if (tracks.size() == 1) {
                bot.getPlayerManager().loadItemOrdered(event.getGuild(),
                        "ytsearch:" + tracks.get(0).toSearchQuery(),
                        new SlashResultHandler(event, tracks.get(0).toSearchQuery(), true));
            } else {
                String displayName = result.name != null ? result.name : "Spotify playlist";
                event.getHook().editOriginal(loadingEmoji + " Loading **" + displayName + "**... ("
                        + tracks.size() + " tracks)").queue();
                AtomicReferenceArray<AudioTrack> orderedResults = new AtomicReferenceArray<>(tracks.size());
                AtomicInteger done = new AtomicInteger(0);
                for (int i = 0; i < tracks.size(); i++) {
                    bot.getPlayerManager().loadItemOrdered(event.getGuild(),
                            "ytsearch:" + tracks.get(i).toSearchQuery(),
                            new SpotifySlashResultHandler(event, orderedResults, i, done, tracks.size(), result.name));
                }
            }
        } catch (Exception ex) {
            LOG.error("Failed to resolve Spotify URL: {}", url, ex);
            event.getHook().editOriginal(FormatUtil.filter(event.getClient().getError()
                    + " Could not load Spotify link: " + ex.getMessage())).queue();
        }
    }

    // ── Spotify playlist result handlers ──────────────────────────────────────

    /**
     * Handles each YouTube search result for a Spotify playlist (prefix commands).
     * Stores resolved tracks by their original index so the queue order matches
     * the Spotify playlist order; adds all tracks when every search has completed.
     */
    private class SpotifyPrefixResultHandler implements AudioLoadResultHandler {
        private final Message m;
        private final CommandEvent event;
        private final AtomicReferenceArray<AudioTrack> orderedResults;
        private final int index;
        private final AtomicInteger done;
        private final int total;
        private final String playlistName;

        SpotifyPrefixResultHandler(Message m, CommandEvent event,
                AtomicReferenceArray<AudioTrack> orderedResults, int index,
                AtomicInteger done, int total, String playlistName) {
            this.m = m;
            this.event = event;
            this.orderedResults = orderedResults;
            this.index = index;
            this.done = done;
            this.total = total;
            this.playlistName = playlistName;
        }

        private void setResult(AudioTrack track) {
            if (track != null && !bot.getConfig().isTooLong(track))
                orderedResults.set(index, track);
        }

        private void finish() {
            if (done.incrementAndGet() != total)
                return;
            // All searches done — add tracks in playlist order
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int loaded = 0;
            for (int i = 0; i < total; i++) {
                AudioTrack track = orderedResults.get(i);
                if (track != null) {
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                    loaded++;
                }
            }
            int missed = total - loaded;
            String name = playlistName != null ? "playlist **" + playlistName + "**" : "a Spotify playlist";
            if (loaded == 0) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " Could not find any tracks from " + name + " on YouTube.")).queue();
            } else {
                String msg = event.getClient().getSuccess() + " Found " + name + " with `"
                        + loaded + "` entries; added to the queue!"
                        + (missed > 0 ? "\n" + event.getClient().getWarning() + " " + missed
                                + " track(s) could not be found on YouTube." : "");
                m.editMessage(FormatUtil.filter(msg)).queue();
            }
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            setResult(track);
            finish();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            if (playlist.isSearchResult() && !playlist.getTracks().isEmpty())
                setResult(playlist.getTracks().get(0));
            finish();
        }

        @Override
        public void noMatches() {
            finish();
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            finish();
        }
    }

    /**
     * Handles each YouTube search result for a Spotify playlist (slash commands).
     * Stores resolved tracks by their original index so the queue order matches
     * the Spotify playlist order; adds all tracks when every search has completed.
     */
    private class SpotifySlashResultHandler implements AudioLoadResultHandler {
        private final SlashCommandEvent event;
        private final AtomicReferenceArray<AudioTrack> orderedResults;
        private final int index;
        private final AtomicInteger done;
        private final int total;
        private final String playlistName;

        SpotifySlashResultHandler(SlashCommandEvent event,
                AtomicReferenceArray<AudioTrack> orderedResults, int index,
                AtomicInteger done, int total, String playlistName) {
            this.event = event;
            this.orderedResults = orderedResults;
            this.index = index;
            this.done = done;
            this.total = total;
            this.playlistName = playlistName;
        }

        private void setResult(AudioTrack track) {
            if (track != null && !bot.getConfig().isTooLong(track))
                orderedResults.set(index, track);
        }

        private void finish() {
            if (done.incrementAndGet() != total)
                return;
            // All searches done — add tracks in playlist order
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int loaded = 0;
            for (int i = 0; i < total; i++) {
                AudioTrack track = orderedResults.get(i);
                if (track != null) {
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                    loaded++;
                }
            }
            int missed = total - loaded;
            String name = playlistName != null ? "playlist **" + playlistName + "**" : "a Spotify playlist";
            if (loaded == 0) {
                event.getHook().editOriginal(FormatUtil.filter(event.getClient().getWarning()
                        + " Could not find any tracks from " + name + " on YouTube.")).queue();
            } else {
                String msg = event.getClient().getSuccess() + " Found " + name + " with `"
                        + loaded + "` entries; added to the queue!"
                        + (missed > 0 ? "\n" + event.getClient().getWarning() + " " + missed
                                + " track(s) could not be found on YouTube." : "");
                event.getHook().editOriginal(FormatUtil.filter(msg)).queue();
            }
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            setResult(track);
            finish();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            if (playlist.isSearchResult() && !playlist.getTracks().isEmpty())
                setResult(playlist.getTracks().get(0));
            finish();
        }

        @Override
        public void noMatches() {
            finish();
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            finish();
        }
    }
}
