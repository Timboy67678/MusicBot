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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.Arrays;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class RepeatCmd extends DJCommand
{
    public RepeatCmd(Bot bot)
    {
        super(bot);
        this.name = "repeat";
        this.help = "re-adds music to the queue when finished";
        this.arguments = "[off|all|single]";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.options = Arrays.asList(
            new OptionData(OptionType.STRING, "mode", "Repeat mode", false)
                .addChoice("off", "off")
                .addChoice("all", "all")
                .addChoice("single", "single")
        );
    }
    
    // override musiccommand's execute because we don't actually care where this is used
    @Override
    protected void execute(CommandEvent event) 
    {
        String args = event.getArgs();
        RepeatMode value;
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        if(args.isEmpty())
        {
            if(settings.getRepeatMode() == RepeatMode.OFF)
                value = RepeatMode.ALL;
            else
                value = RepeatMode.OFF;
        }
        else if(args.equalsIgnoreCase("false") || args.equalsIgnoreCase("off"))
        {
            value = RepeatMode.OFF;
        }
        else if(args.equalsIgnoreCase("true") || args.equalsIgnoreCase("on") || args.equalsIgnoreCase("all"))
        {
            value = RepeatMode.ALL;
        }
        else if(args.equalsIgnoreCase("one") || args.equalsIgnoreCase("single"))
        {
            value = RepeatMode.SINGLE;
        }
        else
        {
            event.replyError("Valid options are `off`, `all` or `single` (or leave empty to toggle between `off` and `all`)");
            return;
        }
        settings.setRepeatMode(value);
        event.replySuccess("Repeat mode is now `"+value.getUserFriendlyName()+"`");
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally Empty */ }

    @Override
    public void doCommand(SlashCommandEvent event)
    {
        if(!DJCommand.checkDJPermission(event))
        {
            event.reply(event.getClient().getError() + " Only DJs can change the repeat mode!").setEphemeral(true).queue();
            return;
        }
        String modeArg = event.optString("mode", "");
        RepeatMode value;
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        if(modeArg.isEmpty())
        {
            value = (settings.getRepeatMode() == RepeatMode.OFF) ? RepeatMode.ALL : RepeatMode.OFF;
        }
        else if(modeArg.equalsIgnoreCase("off") || modeArg.equalsIgnoreCase("false"))
        {
            value = RepeatMode.OFF;
        }
        else if(modeArg.equalsIgnoreCase("all") || modeArg.equalsIgnoreCase("on"))
        {
            value = RepeatMode.ALL;
        }
        else
        {
            value = RepeatMode.SINGLE;
        }
        settings.setRepeatMode(value);
        event.reply(event.getClient().getSuccess() + " Repeat mode is now `" + value.getUserFriendlyName() + "`").setEphemeral(true).queue();
    }
}
