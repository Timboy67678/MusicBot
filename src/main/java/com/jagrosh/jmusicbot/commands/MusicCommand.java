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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.exceptions.PermissionException;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public abstract class MusicCommand extends SlashCommand
{
    protected final Bot bot;
    protected boolean bePlaying;
    protected boolean beListening;

    public MusicCommand(Bot bot)
    {
        this.bot = bot;
        this.category = new Category("Music");
    }

    // ── Prefix command entry point (unchanged) ────────────────────────────────
    @Override
    protected void execute(CommandEvent event)
    {
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        TextChannel tchannel = settings.getTextChannel(event.getGuild());
        if(tchannel != null && !event.getTextChannel().equals(tchannel))
        {
            try { event.getMessage().delete().queue(); } catch(PermissionException ignore) {}
            event.replyInDm(event.getClient().getError() + " You can only use that command in " + tchannel.getAsMention() + "!");
            return;
        }
        bot.getPlayerManager().setUpHandler(event.getGuild());
        if(bePlaying && !((AudioHandler)event.getGuild().getAudioManager().getSendingHandler()).isMusicPlaying(event.getJDA()))
        {
            event.reply(event.getClient().getError() + " There must be music playing to use that!");
            return;
        }
        if(beListening)
        {
            AudioChannel current = event.getGuild().getSelfMember().getVoiceState().getChannel();
            if(current == null) current = settings.getVoiceChannel(event.getGuild());
            GuildVoiceState userState = event.getMember().getVoiceState();
            if(!userState.inAudioChannel() || userState.isDeafened() || (current != null && !userState.getChannel().equals(current)))
            {
                event.replyError("You must be listening in " + (current == null ? "a voice channel" : current.getAsMention()) + " to use that!");
                return;
            }
            VoiceChannel afkChannel = userState.getGuild().getAfkChannel();
            if(afkChannel != null && afkChannel.equals(userState.getChannel()))
            {
                event.replyError("You cannot use that command in an AFK channel!");
                return;
            }
            if(!event.getGuild().getSelfMember().getVoiceState().inAudioChannel())
            {
                try { event.getGuild().getAudioManager().openAudioConnection(userState.getChannel()); }
                catch(PermissionException ex)
                {
                    event.reply(event.getClient().getError() + " I am unable to connect to " + userState.getChannel().getAsMention() + "!");
                    return;
                }
            }
        }
        doCommand(event);
    }

    // ── Slash command entry point ─────────────────────────────────────────────
    @Override
    protected void execute(SlashCommandEvent event)
    {
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        TextChannel tchannel = settings.getTextChannel(event.getGuild());
        if(tchannel != null && !event.getChannel().equals(tchannel))
        {
            event.reply(event.getClient().getError() + " You can only use that command in " + tchannel.getAsMention() + "!").setEphemeral(true).queue();
            return;
        }
        bot.getPlayerManager().setUpHandler(event.getGuild());
        if(bePlaying && !((AudioHandler)event.getGuild().getAudioManager().getSendingHandler()).isMusicPlaying(event.getJDA()))
        {
            event.reply(event.getClient().getError() + " There must be music playing to use that!").setEphemeral(true).queue();
            return;
        }
        if(beListening)
        {
            AudioChannel current = event.getGuild().getSelfMember().getVoiceState().getChannel();
            if(current == null) current = settings.getVoiceChannel(event.getGuild());
            GuildVoiceState userState = event.getMember().getVoiceState();
            if(!userState.inAudioChannel() || userState.isDeafened() || (current != null && !userState.getChannel().equals(current)))
            {
                event.reply(event.getClient().getError() + " You must be listening in " + (current == null ? "a voice channel" : current.getAsMention()) + " to use that!").setEphemeral(true).queue();
                return;
            }
            VoiceChannel afkChannel = userState.getGuild().getAfkChannel();
            if(afkChannel != null && afkChannel.equals(userState.getChannel()))
            {
                event.reply(event.getClient().getError() + " You cannot use that command in an AFK channel!").setEphemeral(true).queue();
                return;
            }
            if(!event.getGuild().getSelfMember().getVoiceState().inAudioChannel())
            {
                try { event.getGuild().getAudioManager().openAudioConnection(userState.getChannel()); }
                catch(PermissionException ex)
                {
                    event.reply(event.getClient().getError() + " I am unable to connect to " + userState.getChannel().getAsMention() + "!").setEphemeral(true).queue();
                    return;
                }
            }
        }
        if(!checkSlashPrivilege(event))
        {
            event.reply(event.getClient().getError() + " You do not have permission to use that command!").setEphemeral(true).queue();
            return;
        }
        doCommand(event);
    }

    /**
     * Override in subclasses to restrict slash command access beyond the default gating.
     * DJCommand overrides this to enforce the DJ-role check.
     */
    protected boolean checkSlashPrivilege(SlashCommandEvent event)
    {
        return true;
    }

    public abstract void doCommand(CommandEvent event);
    public abstract void doCommand(SlashCommandEvent event);
}
