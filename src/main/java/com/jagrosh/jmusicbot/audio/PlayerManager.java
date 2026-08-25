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
package com.jagrosh.jmusicbot.audio;

import com.dunctebot.sourcemanagers.DuncteBotSources;
import com.jagrosh.jmusicbot.Bot;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.EqualizerFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.AndroidVr;
import net.dv8tion.jda.api.entities.Guild;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlayerManager extends DefaultAudioPlayerManager {
    private final Bot bot;
    private final EqualizerFactory equalizerFactory = new EqualizerFactory();

    public PlayerManager(Bot bot) {
        this.bot = bot;
    }

    public void init() {
        // Use the highest available resampling and Opus encoding quality.
        // This is critical in containerised environments where the native
        // connector may not load and LavaPlayer falls back to a Java-based
        // audio pipeline; without these settings that path uses LOW quality
        // resampling which causes audible bass distortion.
        getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        getConfiguration().setOpusEncodingQuality(10);

        TransformativeAudioSourceManager.createTransforms(bot.getConfig().getTransforms())
                .forEach(t -> registerSourceManager(t));

        // Local signature deciphering can fall behind YouTube's player script changes;
        // a remote cipher server offloads that to an externally-maintained service.
        YoutubeSourceOptions ytOptions = new YoutubeSourceOptions().setAllowSearch(true);
        String ytCipherUrl = bot.getConfig().getYoutubeRemoteCipherUrl();
        if(ytCipherUrl != null && !ytCipherUrl.isEmpty())
            ytOptions.setRemoteCipher(ytCipherUrl, bot.getConfig().getYoutubeRemoteCipherPassword(), bot.getConfig().getYoutubeRemoteCipherUserAgent());

        YoutubeAudioSourceManager yt = new YoutubeAudioSourceManager(
                ytOptions,
                new Music(),
                new TvHtml5Simply(),
                new AndroidVr(),
                new Web(),
                // Extra fallback that needs neither OAuth nor a poToken.
                new MWeb(),
                // TV is the only client that actually uses the OAuth token configured below.
                new Tv());
        yt.setPlaylistPageCount(bot.getConfig().getMaxYTPlaylistPages());
        // Mitigates YouTube's "Sign in to confirm you're not a bot" errors by
        // authenticating requests as a real account. With no saved token, this triggers
        // the OAuth flow (URL + code printed to console) on the first login attempt.
        String ytRefreshToken = bot.getConfig().getYoutubeOauthRefreshToken();
        if(ytRefreshToken != null && !ytRefreshToken.isEmpty())
            yt.useOauth2(ytRefreshToken, true);
        else
            yt.useOauth2(null, false);
        // Covers the WEB client specifically, which OAuth above does not authenticate.
        String ytPoToken = bot.getConfig().getYoutubePoToken();
        String ytVisitorData = bot.getConfig().getYoutubeVisitorData();
        if(ytPoToken != null && !ytPoToken.isEmpty() && ytVisitorData != null && !ytVisitorData.isEmpty())
            Web.setPoTokenAndVisitorData(ytPoToken, ytVisitorData);
        registerSourceManager(yt);

        registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        registerSourceManager(new BandcampAudioSourceManager());
        registerSourceManager(new VimeoAudioSourceManager());
        registerSourceManager(new TwitchStreamAudioSourceManager());
        registerSourceManager(new BeamAudioSourceManager());
        registerSourceManager(new GetyarnAudioSourceManager());
        registerSourceManager(new NicoAudioSourceManager());
        registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));

        AudioSourceManagers.registerLocalSource(this);

        DuncteBotSources.registerAll(this, "en-US");
    }

    public Bot getBot() {
        return bot;
    }

    public EqualizerFactory getEqualizerFactory() {
        return equalizerFactory;
    }

    public void applyEqGain(int logicalVolume) {
        float gain = logicalVolume > 100
                ? Math.min((logicalVolume - 100) / 200.0f * 0.25f, 0.25f)
                : 0.0f;
        for (int band = 0; band < 15; band++)
            equalizerFactory.setGain(band, gain);
    }

    public boolean hasHandler(Guild guild) {
        return guild.getAudioManager().getSendingHandler() != null;
    }

    public AudioHandler setUpHandler(Guild guild) {
        AudioHandler handler;
        if (guild.getAudioManager().getSendingHandler() == null) {
            AudioPlayer player = createPlayer();
            player.setFilterFactory(equalizerFactory);
            int savedVolume = bot.getSettingsManager().getSettings(guild).getVolume();
            player.setVolume(Math.min(savedVolume, 100));
            applyEqGain(savedVolume);
            handler = new AudioHandler(this, guild, player);
            player.addListener(handler);
            guild.getAudioManager().setSendingHandler(handler);
        } else
            handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        return handler;
    }
}
