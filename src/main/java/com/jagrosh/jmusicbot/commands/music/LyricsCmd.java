/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jlyrics.LyricsClient;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.Arrays;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class LyricsCmd extends MusicCommand
{
    private final LyricsClient client = new LyricsClient();
    
    public LyricsCmd(Bot bot)
    {
        super(bot);
        this.name = "lyrics";
        this.arguments = "[song name]";
        this.help = "shows the lyrics of a song";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
        this.options = Arrays.asList(new OptionData(OptionType.STRING, "title", "Song name (defaults to currently playing track)", false));
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        String title;
        if(event.getArgs().isEmpty())
        {
            AudioHandler sendingHandler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if (sendingHandler.isMusicPlaying(event.getJDA()))
                title = sendingHandler.getPlayer().getPlayingTrack().getInfo().title;
            else
            {
                event.replyError("There must be music playing to use that!");
                return;
            }
        }
        else
            title = event.getArgs();
        event.getChannel().sendTyping().queue();
        client.getLyrics(title).thenAccept(lyrics -> 
        {
            if(lyrics == null)
            {
                event.replyError("Lyrics for `" + title + "` could not be found!" + (event.getArgs().isEmpty() ? " Try entering the song name manually (`lyrics [song name]`)" : ""));
                return;
            }

            EmbedBuilder eb = new EmbedBuilder()
                    .setAuthor(lyrics.getAuthor())
                    .setColor(event.getSelfMember().getColor())
                    .setTitle(lyrics.getTitle(), lyrics.getURL());
            if(lyrics.getContent().length()>15000)
            {
                event.replyWarning("Lyrics for `" + title + "` found but likely not correct: " + lyrics.getURL());
            }
            else if(lyrics.getContent().length()>2000)
            {
                String content = lyrics.getContent().trim();
                while(content.length() > 2000)
                {
                    int index = content.lastIndexOf("\n\n", 2000);
                    if(index == -1)
                        index = content.lastIndexOf("\n", 2000);
                    if(index == -1)
                        index = content.lastIndexOf(" ", 2000);
                    if(index == -1)
                        index = 2000;
                    event.reply(eb.setDescription(content.substring(0, index).trim()).build());
                    content = content.substring(index).trim();
                    eb.setAuthor(null).setTitle(null, null);
                }
                event.reply(eb.setDescription(content).build());
            }
            else
                event.reply(eb.setDescription(lyrics.getContent()).build());
        });
    }

    @Override
    public void doCommand(SlashCommandEvent event)
    {
        String title;
        String titleArg = event.optString("title", "");
        if(titleArg.isEmpty())
        {
            AudioHandler sendingHandler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if(sendingHandler.isMusicPlaying(event.getJDA()))
                title = sendingHandler.getPlayer().getPlayingTrack().getInfo().title;
            else
            {
                event.reply(event.getClient().getError() + " There must be music playing to use that!").setEphemeral(true).queue();
                return;
            }
        }
        else
            title = titleArg;

        // Defer since lyrics fetching is async
        event.deferReply().queue();
        client.getLyrics(title).thenAccept(lyrics ->
        {
            if(lyrics == null)
            {
                event.getHook().editOriginal(event.getClient().getError() + " Lyrics for `" + title + "` could not be found!"
                        + (titleArg.isEmpty() ? " Try specifying the title with `/lyrics title:<song name>`" : "")).queue();
                return;
            }
            EmbedBuilder eb = new EmbedBuilder()
                    .setAuthor(lyrics.getAuthor())
                    .setColor(event.getMember().getColor())
                    .setTitle(lyrics.getTitle(), lyrics.getURL());
            if(lyrics.getContent().length() > 15000)
            {
                event.getHook().editOriginal(event.getClient().getWarning() + " Lyrics for `" + title + "` found but likely not correct: " + lyrics.getURL()).queue();
            }
            else if(lyrics.getContent().length() > 4096)
            {
                // Discord embed description limit is 4096 chars — truncate gracefully
                String truncated = lyrics.getContent().substring(0, 4093) + "...";
                event.getHook().editOriginalEmbeds(eb.setDescription(truncated).build()).queue();
            }
            else
            {
                event.getHook().editOriginalEmbeds(eb.setDescription(lyrics.getContent()).build()).queue();
            }
        });
    }
}
