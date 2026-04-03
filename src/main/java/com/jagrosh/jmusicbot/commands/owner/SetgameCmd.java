/*
 * Copyright 2017 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.commands.owner;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.OwnerCommand;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.Arrays;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SetgameCmd extends OwnerCommand
{
    public SetgameCmd(Bot bot)
    {
        this.name = "setgame";
        this.help = "sets the bot activity";
        this.arguments = "[playing|listening|watching|streaming|clear] [title]";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = false;
        this.options = Arrays.asList(
            new OptionData(OptionType.STRING, "type", "Activity type", false)
                .addChoice("Playing", "playing")
                .addChoice("Listening", "listening")
                .addChoice("Watching", "watching")
                .addChoice("Clear", "clear"),
            new OptionData(OptionType.STRING, "title", "Activity title", false)
        );
    }

    @Override
    protected void execute(CommandEvent event)
    {
        String args = event.getArgs().trim();
        String[] parts = args.split("\\s+", 2);
        String type = parts.length > 0 ? parts[0].toLowerCase() : "";
        String title = parts.length > 1 ? parts[1] : "";
        try
        {
            Activity activity;
            switch(type)
            {
                case "listening": case "listen":
                    activity = title.isEmpty() ? null : Activity.listening(title); break;
                case "watching": case "watch":
                    activity = title.isEmpty() ? null : Activity.watching(title); break;
                case "streaming": case "stream": case "twitch":
                    String[] streamParts = title.split("\\s+", 2);
                    activity = streamParts.length >= 2 ? Activity.streaming(streamParts[1], "https://twitch.tv/" + streamParts[0]) : null;
                    break;
                case "clear": case "none":
                    activity = null; break;
                default:
                    activity = args.isEmpty() ? null : Activity.playing(args); break;
            }
            event.getJDA().getPresence().setActivity(activity);
            String msg = activity == null ? "no longer playing anything." : "now " + (activity.getType().name().toLowerCase()) + " `" + activity.getName() + "`";
            event.reply(event.getClient().getSuccess() + " **" + event.getSelfUser().getName() + "** is " + msg);
        }
        catch(Exception e)
        {
            event.reply(event.getClient().getError() + " The game could not be set!");
        }
    }

    @Override
    protected void execute(SlashCommandEvent event)
    {
        if(!checkOwnerPermission(event)) { event.reply(event.getClient().getError() + " Only the bot owner can use this command!").setEphemeral(true).queue(); return; }
        String type = event.optString("type", "playing");
        String title = event.optString("title", "");
        try
        {
            Activity activity = null;
            if(!type.equals("clear") && !title.isEmpty())
            {
                switch(type)
                {
                    case "listening": activity = Activity.listening(title); break;
                    case "watching":  activity = Activity.watching(title);  break;
                    default:          activity = Activity.playing(title);   break;
                }
            }
            event.getJDA().getPresence().setActivity(activity);
            String name = event.getJDA().getSelfUser().getName();
            if(activity == null)
                event.reply(event.getClient().getSuccess() + " **" + name + "** is no longer playing anything.").queue();
            else
                event.reply(event.getClient().getSuccess() + " **" + name + "** is now " + type + " `" + title + "`").queue();
        }
        catch(Exception e)
        {
            event.reply(event.getClient().getError() + " The game could not be set!").setEphemeral(true).queue();
        }
    }
}
