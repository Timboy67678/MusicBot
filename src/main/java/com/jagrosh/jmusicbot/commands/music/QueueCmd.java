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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jdautilities.menu.Paginator;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class QueueCmd extends MusicCommand 
{
    private final Paginator.Builder builder;
    
    public QueueCmd(Bot bot)
    {
        super(bot);
        this.name = "queue";
        this.help = "shows the current queue";
        this.arguments = "[pagenum]";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = true;
        this.botPermissions = new Permission[]{Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_EMBED_LINKS};
        this.options = Arrays.asList(new OptionData(OptionType.INTEGER, "page", "Page number to display", false).setMinValue(1));
        builder = new Paginator.Builder()
                .setColumns(1)
                .setFinalAction(m -> {try{m.clearReactions().queue();}catch(PermissionException ignore){}})
                .setItemsPerPage(10)
                .waitOnSinglePage(false)
                .useNumberedItems(true)
                .showPageNumbers(true)
                .wrapPageEnds(true)
                .setEventWaiter(bot.getWaiter())
                .setTimeout(1, TimeUnit.MINUTES);
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        int pagenum = 1;
        try
        {
            pagenum = Integer.parseInt(event.getArgs());
        }
        catch(NumberFormatException ignore){}
        AudioHandler ah = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        List<QueuedTrack> list = ah.getQueue().getList();
        if(list.isEmpty())
        {
            MessageCreateData nowp = ah.getNowPlaying(event.getJDA());
            MessageCreateData nonowp = ah.getNoMusicPlaying(event.getJDA());
            MessageCreateData built = new MessageCreateBuilder()
                    .setContent(event.getClient().getWarning() + " There is no music in the queue!")
                    .setEmbeds((nowp==null ? nonowp : nowp).getEmbeds().get(0)).build();
            event.reply(built, m -> 
            {
                if(nowp!=null)
                    bot.getNowplayingHandler().setLastNPMessage(m);
            });
            return;
        }
        String[] songs = new String[list.size()];
        long total = 0;
        for(int i=0; i<list.size(); i++)
        {
            total += list.get(i).getTrack().getDuration();
            songs[i] = list.get(i).toString();
        }
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        long fintotal = total;
        builder.setText((i1,i2) -> getQueueTitle(ah, event.getClient().getSuccess(), songs.length, fintotal, settings.getRepeatMode(), settings.getQueueType()))
                .setItems(songs)
                .setUsers(event.getAuthor())
                .setColor(event.getSelfMember().getColor())
                ;
        builder.build().paginate(event.getChannel(), pagenum);
    }
    
    private String getQueueTitle(AudioHandler ah, String success, int songslength, long total, RepeatMode repeatmode, QueueType queueType)
    {
        StringBuilder sb = new StringBuilder();
        if(ah.getPlayer().getPlayingTrack() != null)
        {
            sb.append(ah.getStatusEmoji()).append(" **")
                    .append(ah.getPlayer().getPlayingTrack().getInfo().title).append("**\n");
        }
        return FormatUtil.filter(sb.append(success).append(" Current Queue | ").append(songslength)
                .append(" entries | `").append(TimeUtil.formatTime(total)).append("` ")
                .append("| ").append(queueType.getEmoji()).append(" `").append(queueType.getUserFriendlyName()).append('`')
                .append(repeatmode.getEmoji() != null ? " | " + repeatmode.getEmoji() : "").toString());
    }

    // ── Slash command — button-paginated queue embed ───────────────────────────

    @Override
    public void doCommand(SlashCommandEvent event)
    {
        AudioHandler ah = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        List<QueuedTrack> list = ah.getQueue().getList();
        if(list.isEmpty())
        {
            MessageCreateData nowp = ah.getNowPlaying(event.getJDA());
            MessageCreateData nonowp = ah.getNoMusicPlaying(event.getJDA());
            MessageCreateData built = new MessageCreateBuilder()
                    .setContent(event.getClient().getWarning() + " There is no music in the queue!")
                    .setEmbeds((nowp == null ? nonowp : nowp).getEmbeds().get(0)).build();
            event.reply(built).queue(hook ->
            {
                if(nowp != null)
                    hook.retrieveOriginal().queue(msg -> bot.getNowplayingHandler().setLastNPMessage(msg));
            });
            return;
        }

        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        int page = (int) event.optLong("page", 1);
        sendQueuePage(event, ah, list, settings, page, event.getUser(), event.getGuild());
    }

    private void sendQueuePage(SlashCommandEvent event, AudioHandler ah, List<QueuedTrack> list,
                                Settings settings, int page, User user, Guild guild)
    {
        String[] songs = buildSongArray(list);
        long total = list.stream().mapToLong(qt -> qt.getTrack().getDuration()).sum();
        int totalPages = (int) Math.ceil(songs.length / 10.0);
        page = Math.max(1, Math.min(page, totalPages));

        String title = getQueueTitle(ah, event.getClient().getSuccess(), songs.length, total, settings.getRepeatMode(), settings.getQueueType());
        String pageContent = buildPageContent(songs, page, title);

        Button prev = Button.primary("queue:prev:" + user.getId(), "◀").withDisabled(page <= 1);
        Button pageBtn = Button.secondary("queue:cur:" + user.getId(), page + "/" + totalPages).withDisabled(true);
        Button next = Button.primary("queue:next:" + user.getId(), "▶").withDisabled(page >= totalPages);

        if(totalPages <= 1)
        {
            event.reply(pageContent).queue();
        }
        else
        {
            final int finalPage = page;
            event.reply(pageContent).addComponents(ActionRow.of(prev, pageBtn, next)).queue(hook ->
                waitForQueueNav(hook, ah, settings, user, guild, finalPage, totalPages));
        }
    }

    private void waitForQueueNav(InteractionHook hook, AudioHandler ah, Settings settings,
                                  User user, Guild guild, int currentPage, int totalPages)
    {
        bot.getWaiter().waitForEvent(
            ButtonInteractionEvent.class,
            e -> e.getComponentId().startsWith("queue:") && e.getComponentId().endsWith(":" + user.getId()) && e.getUser().equals(user),
            e ->
            {
                AudioHandler freshAh = (AudioHandler) guild.getAudioManager().getSendingHandler();
                List<QueuedTrack> freshList = freshAh.getQueue().getList();
                String[] freshSongs = buildSongArray(freshList);
                long freshTotal = freshList.stream().mapToLong(qt -> qt.getTrack().getDuration()).sum();
                int freshTotalPages = freshSongs.length == 0 ? 1 : (int) Math.ceil(freshSongs.length / 10.0);

                int newPage = currentPage;
                if(e.getComponentId().startsWith("queue:next:")) newPage = Math.min(currentPage + 1, freshTotalPages);
                else if(e.getComponentId().startsWith("queue:prev:")) newPage = Math.max(currentPage - 1, 1);

                String freshTitle = getQueueTitle(freshAh, "\u2705", freshSongs.length, freshTotal, settings.getRepeatMode(), settings.getQueueType());
                String content = buildPageContent(freshSongs, newPage, freshTitle);

                Button prev = Button.primary("queue:prev:" + user.getId(), "◀").withDisabled(newPage <= 1);
                Button pageBtn = Button.secondary("queue:cur:" + user.getId(), newPage + "/" + freshTotalPages).withDisabled(true);
                Button next = Button.primary("queue:next:" + user.getId(), "▶").withDisabled(newPage >= freshTotalPages);

                final int np = newPage;
                e.editMessage(content).setComponents(ActionRow.of(prev, pageBtn, next)).queue(newHook ->
                    waitForQueueNav(newHook, freshAh, settings, user, guild, np, freshTotalPages));
            },
            60, TimeUnit.SECONDS,
            () -> hook.editOriginalComponents().queue()
        );
    }

    private static String[] buildSongArray(List<QueuedTrack> list)
    {
        String[] songs = new String[list.size()];
        for(int i = 0; i < list.size(); i++)
            songs[i] = list.get(i).toString();
        return songs;
    }

    private static String buildPageContent(String[] songs, int page, String title)
    {
        int start = (page - 1) * 10;
        int end = Math.min(start + 10, songs.length);
        StringBuilder sb = new StringBuilder(title).append("\n");
        for(int i = start; i < end; i++)
            sb.append("`").append(i + 1).append(".` ").append(songs[i]).append("\n");
        return sb.toString();
    }
}
