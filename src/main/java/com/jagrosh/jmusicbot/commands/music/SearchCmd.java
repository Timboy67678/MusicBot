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
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jdautilities.menu.OrderedMenu;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SearchCmd extends MusicCommand 
{
    protected String searchPrefix = "ytsearch:";
    private final OrderedMenu.Builder builder;
    private final String searchingEmoji;
    
    public SearchCmd(Bot bot)
    {
        super(bot);
        this.searchingEmoji = bot.getConfig().getSearching();
        this.name = "search";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.arguments = "<query>";
        this.help = "searches Youtube for a provided query";
        this.beListening = true;
        this.bePlaying = false;
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
        this.options = Arrays.asList(new OptionData(OptionType.STRING, "query", "What to search for", true));
        builder = new OrderedMenu.Builder()
                .allowTextInput(true)
                .useNumbers()
                .useCancelButton(true)
                .setEventWaiter(bot.getWaiter())
                .setTimeout(1, TimeUnit.MINUTES);
    }
    @Override
    public void doCommand(CommandEvent event)
    {
        if(event.getArgs().isEmpty())
        {
            event.replyError("Please include a query.");
            return;
        }
        event.reply(searchingEmoji + " Searching... `[" + event.getArgs() + "]`",
                m -> bot.getPlayerManager().loadItemOrdered(event.getGuild(), searchPrefix + event.getArgs(), new ResultHandler(m, event)));
    }

    @Override
    public void doCommand(SlashCommandEvent event)
    {
        String query = event.optString("query", "");
        if(query.isEmpty())
        {
            event.reply(event.getClient().getError() + " Please include a query.").setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue();
        bot.getPlayerManager().loadItemOrdered(event.getGuild(), searchPrefix + query, new SlashResultHandler(event, query));
    }
    
    private class ResultHandler implements AudioLoadResultHandler 
    {
        private final Message m;
        private final CommandEvent event;
        
        private ResultHandler(Message m, CommandEvent event)
        {
            this.m = m;
            this.event = event;
        }
        
        @Override
        public void trackLoaded(AudioTrack track)
        {
            if(bot.getConfig().isTooLong(track))
            {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" This track (**"+track.getInfo().title+"**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+bot.getConfig().getMaxTime()+"`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            m.editMessage(FormatUtil.filter(event.getClient().getSuccess()+" Added **"+track.getInfo().title
                    +"** (`"+ TimeUtil.formatTime(track.getDuration())+"`) "+(pos==0 ? "to begin playing"
                        : " to the queue at position "+pos))).queue();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            builder.setColor(event.getSelfMember().getColor())
                    .setText(FormatUtil.filter(event.getClient().getSuccess()+" Search results for `"+event.getArgs()+"`:"))
                    .setChoices(new String[0])
                    .setSelection((msg,i) -> 
                    {
                        AudioTrack track = playlist.getTracks().get(i-1);
                        if(bot.getConfig().isTooLong(track))
                        {
                            event.replyWarning("This track (**"+track.getInfo().title+"**) is longer than the allowed maximum: `"
                                    + TimeUtil.formatTime(track.getDuration())+"` > `"+bot.getConfig().getMaxTime()+"`");
                            return;
                        }
                        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                        int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
                        event.replySuccess("Added **" + FormatUtil.filter(track.getInfo().title)
                                + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos==0 ? "to begin playing" 
                                    : " to the queue at position "+pos));
                    })
                    .setCancel((msg) -> {})
                    .setUsers(event.getAuthor())
                    ;
            for(int i=0; i<4 && i<playlist.getTracks().size(); i++)
            {
                AudioTrack track = playlist.getTracks().get(i);
                builder.addChoices("`["+ TimeUtil.formatTime(track.getDuration())+"]` [**"+track.getInfo().title+"**]("+track.getInfo().uri+")");
            }
            builder.build().display(m);
        }

        @Override
        public void noMatches() 
        {
            m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" No results found for `"+event.getArgs()+"`.")).queue();
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            if(throwable.severity == Severity.COMMON)
                m.editMessage(event.getClient().getError() + " Error loading: " + throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError() + " Error loading track.").queue();
        }
    }

    /** Slash-specific result handler using a StringSelectMenu for track selection. */
    private class SlashResultHandler implements AudioLoadResultHandler
    {
        private final SlashCommandEvent event;
        private final String query;

        private SlashResultHandler(SlashCommandEvent event, String query)
        {
            this.event = event;
            this.query = query;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            if(bot.getConfig().isTooLong(track))
            {
                event.getHook().editOriginal(FormatUtil.filter(event.getClient().getWarning() + " This track (**" + track.getInfo().title + "**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration()) + "` > `" + bot.getConfig().getMaxTime() + "`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event))) + 1;
            event.getHook().editOriginal(FormatUtil.filter(event.getClient().getSuccess() + " Added **" + track.getInfo().title
                    + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos == 0 ? "to begin playing" : " to the queue at position " + pos))).queue();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if(playlist.isSearchResult() && !playlist.getTracks().isEmpty())
            {
                // Build a select menu with up to 5 results
                List<SelectOption> options = new ArrayList<>();
                int count = Math.min(5, playlist.getTracks().size());
                for(int i = 0; i < count; i++)
                {
                    AudioTrack track = playlist.getTracks().get(i);
                    String label = track.getInfo().title.length() > 100 ? track.getInfo().title.substring(0, 97) + "..." : track.getInfo().title;
                    String description = "`" + TimeUtil.formatTime(track.getDuration()) + "` — " + track.getInfo().author;
                    if(description.length() > 100) description = description.substring(0, 97) + "...";
                    options.add(SelectOption.of(label, String.valueOf(i)).withDescription(description));
                }
                StringSelectMenu menu = StringSelectMenu.create("search:" + event.getUser().getId())
                        .setPlaceholder("Choose a track to add to the queue")
                        .addOptions(options)
                        .build();
                event.getHook().editOriginal(event.getClient().getSuccess() + " Search results for `" + query + "`:")
                        .setComponents(ActionRow.of(menu))
                        .queue(m -> bot.getWaiter().waitForEvent(
                            StringSelectInteractionEvent.class,
                            e -> e.getComponentId().equals("search:" + event.getUser().getId()) && e.getUser().equals(event.getUser()),
                            e ->
                            {
                                int index = Integer.parseInt(e.getValues().get(0));
                                AudioTrack selected = playlist.getTracks().get(index);
                                if(bot.getConfig().isTooLong(selected))
                                {
                                    e.editMessage(event.getClient().getWarning() + " This track (**" + selected.getInfo().title + "**) is longer than the allowed maximum: `"
                                            + TimeUtil.formatTime(selected.getDuration()) + "` > `" + bot.getConfig().getMaxTime() + "`").setComponents().queue();
                                    return;
                                }
                                AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                                int pos = handler.addTrack(new QueuedTrack(selected, RequestMetadata.fromResultHandler(selected, event))) + 1;
                                e.editMessage(FormatUtil.filter(event.getClient().getSuccess() + " Added **" + FormatUtil.filter(selected.getInfo().title)
                                        + "** (`" + TimeUtil.formatTime(selected.getDuration()) + "`) " + (pos == 0 ? "to begin playing" : " to the queue at position " + pos))).setComponents().queue();
                            },
                            1, TimeUnit.MINUTES,
                            () -> m.editMessageComponents().queue()
                        ));
            }
            else
            {
                event.getHook().editOriginal(event.getClient().getWarning() + " No results found for `" + query + "`.").queue();
            }
        }

        @Override
        public void noMatches()
        {
            event.getHook().editOriginal(event.getClient().getWarning() + " No results found for `" + query + "`.").queue();
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            if(throwable.severity == Severity.COMMON)
                event.getHook().editOriginal(event.getClient().getError() + " Error loading: " + throwable.getMessage()).queue();
            else
                event.getHook().editOriginal(event.getClient().getError() + " Error loading track.").queue();
        }
    }
}
