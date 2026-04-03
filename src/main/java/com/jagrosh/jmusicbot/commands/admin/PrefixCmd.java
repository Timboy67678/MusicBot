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
package com.jagrosh.jmusicbot.commands.admin;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.AdminCommand;
import com.jagrosh.jmusicbot.settings.Settings;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.Arrays;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PrefixCmd extends AdminCommand
{
    public PrefixCmd(Bot bot)
    {
        this.name = "prefix";
        this.help = "sets a server-specific prefix";
        this.arguments = "<prefix|NONE>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.options = Arrays.asList(new OptionData(OptionType.STRING, "prefix", "New prefix, or NONE to clear", false));
    }
    
    @Override
    protected void execute(CommandEvent event) 
    {
        if(event.getArgs().isEmpty())
        {
            event.replyError("Please include a prefix or NONE");
            return;
        }
        
        Settings s = event.getClient().getSettingsFor(event.getGuild());
        if(event.getArgs().equalsIgnoreCase("none"))
        {
            s.setPrefix(null);
            event.replySuccess("Prefix cleared.");
        }
        else
        {
            s.setPrefix(event.getArgs());
            event.replySuccess("Custom prefix set to `" + event.getArgs() + "` on *" + event.getGuild().getName() + "*");
        }
    }

    @Override
    protected void execute(SlashCommandEvent event)
    {
        if(!checkAdminPermission(event)) { event.reply(event.getClient().getError() + " You must have Manage Server permission to use this command!").setEphemeral(true).queue(); return; }
        Settings s = event.getClient().getSettingsFor(event.getGuild());
        String prefix = event.optString("prefix", "");
        if(prefix.isEmpty())
        {
            String current = s.getPrefix();
            event.reply(event.getClient().getSuccess() + " Current prefix: " + (current == null ? "None (using default)" : "`" + current + "`")).queue();
        }
        else if(prefix.equalsIgnoreCase("none"))
        {
            s.setPrefix(null);
            event.reply(event.getClient().getSuccess() + " Prefix cleared.").queue();
        }
        else
        {
            s.setPrefix(prefix);
            event.reply(event.getClient().getSuccess() + " Custom prefix set to `" + prefix + "` on *" + event.getGuild().getName() + "*").queue();
        }
    }
}
