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
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.Arrays;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SetstatusCmd extends OwnerCommand
{
    public SetstatusCmd(Bot bot)
    {
        this.name = "setstatus";
        this.help = "sets the status the bot displays";
        this.arguments = "<status>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.options = Arrays.asList(
            new OptionData(OptionType.STRING, "status", "Bot online status", true)
                .addChoice("Online", "online")
                .addChoice("Idle", "idle")
                .addChoice("Do Not Disturb", "dnd")
                .addChoice("Invisible", "invisible")
        );
    }
    
    @Override
    protected void execute(CommandEvent event) 
    {
        try {
            OnlineStatus status = OnlineStatus.fromKey(event.getArgs());
            if(status==OnlineStatus.UNKNOWN)
            {
                event.replyError("Please include one of the following statuses: `ONLINE`, `IDLE`, `DND`, `INVISIBLE`");
            }
            else
            {
                event.getJDA().getPresence().setStatus(status);
                event.replySuccess("Set the status to `"+status.getKey().toUpperCase()+"`");
            }
        } catch(Exception e) {
            event.reply(event.getClient().getError()+" The status could not be set!");
        }
    }

    @Override
    protected void execute(SlashCommandEvent event)
    {
        if(!checkOwnerPermission(event)) { event.reply(event.getClient().getError() + " Only the bot owner can use this command!").setEphemeral(true).queue(); return; }
        String key = event.optString("status", "online");
        try
        {
            OnlineStatus status = OnlineStatus.fromKey(key);
            if(status == OnlineStatus.UNKNOWN) { event.reply(event.getClient().getError() + " Unknown status!").setEphemeral(true).queue(); return; }
            event.getJDA().getPresence().setStatus(status);
            event.reply(event.getClient().getSuccess() + " Set the status to `" + status.getKey().toUpperCase() + "`").queue();
        }
        catch(Exception e)
        {
            event.reply(event.getClient().getError() + " The status could not be set!").setEphemeral(true).queue();
        }
    }
}
