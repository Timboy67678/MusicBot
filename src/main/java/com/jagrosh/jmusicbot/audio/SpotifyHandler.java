/*
 * Copyright 2026 Timboy67678 (me@timboy67678.com).
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

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Spotify track, album, and playlist links to a list of
 * {@link SpotifyTrackInfo} objects that can each be searched on YouTube.
 *
 * <p>
 * Uses the Spotify Web API with the Client Credentials OAuth flow.
 * Credentials are optional; if not configured the handler will not be
 * initialised and Spotify URLs will be passed through to lavaplayer
 * unchanged (which will likely result in an error).
 * </p>
 */
public class SpotifyHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SpotifyHandler.class);

    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API_BASE = "https://api.spotify.com/v1";

    /**
     * Matches Spotify track links and URIs, including internationalised URLs
     * such as {@code open.spotify.com/intl-de/track/...}.
     */
    private static final Pattern TRACK_URL = Pattern.compile(
            "(?:https?://open\\.spotify\\.com/(?:[a-z]{2}(?:-[a-zA-Z]{2,})?/)?track/|spotify:track:)" +
                    "([A-Za-z0-9]+)(?:\\?.*)?$");
    private static final Pattern PLAYLIST_URL = Pattern.compile(
            "(?:https?://open\\.spotify\\.com/(?:[a-z]{2}(?:-[a-zA-Z]{2,})?/)?playlist/|spotify:playlist:)" +
                    "([A-Za-z0-9]+)(?:\\?.*)?$");
    private static final Pattern ALBUM_URL = Pattern.compile(
            "(?:https?://open\\.spotify\\.com/(?:[a-z]{2}(?:-[a-zA-Z]{2,})?/)?album/|spotify:album:)" +
                    "([A-Za-z0-9]+)(?:\\?.*)?$");

    private final String clientId;
    private final String clientSecret;

    // Token cache – guarded by this monitor
    private String accessToken;
    private long tokenExpiresAt = 0; // epoch millis

    public SpotifyHandler(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code url} looks like a Spotify track, playlist,
     * or album link (both {@code https://open.spotify.com/…} and
     * {@code spotify:…} URI forms are accepted).
     */
    public boolean isSpotifyUrl(String url) {
        return TRACK_URL.matcher(url).find()
                || PLAYLIST_URL.matcher(url).find()
                || ALBUM_URL.matcher(url).find();
    }

    /**
     * Resolves a Spotify URL to a list of {@link SpotifyTrackInfo} objects
     * (one element for a track, multiple for a playlist or album).
     *
     * @throws IOException if the Spotify API cannot be reached or returns an error
     */
    public List<SpotifyTrackInfo> resolve(String url) throws IOException {
        Matcher m;
        if ((m = TRACK_URL.matcher(url)).find())
            return Collections.singletonList(fetchTrack(m.group(1)));
        if ((m = PLAYLIST_URL.matcher(url)).find())
            return fetchPlaylistTracks(m.group(1));
        if ((m = ALBUM_URL.matcher(url)).find())
            return fetchAlbumTracks(m.group(1));
        throw new IllegalArgumentException("Not a recognised Spotify URL: " + url);
    }

    // ── Data model ────────────────────────────────────────────────────────────

    /**
     * Simple value object carrying the track title and primary artist name
     * retrieved from Spotify.
     */
    public static class SpotifyTrackInfo {
        public final String title;
        public final String artist;

        public SpotifyTrackInfo(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }

        /** Returns a YouTube-search-friendly query string: {@code "Title Artist"}. */
        public String toSearchQuery() {
            return title + " " + artist;
        }

        @Override
        public String toString() {
            return title + " – " + artist;
        }
    }

    // ── Token management ──────────────────────────────────────────────────────

    private synchronized void ensureToken() throws IOException {
        if (System.currentTimeMillis() < tokenExpiresAt)
            return; // still valid

        String credentials = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpURLConnection con = openConnection(TOKEN_URL, "POST");
        con.setRequestProperty("Authorization", "Basic " + credentials);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write("grant_type=client_credentials".getBytes(StandardCharsets.UTF_8));
        }

        DataObject body = readJson(con);
        accessToken = body.getString("access_token");
        int expiresIn = body.getInt("expires_in"); // seconds
        tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 30) * 1000L;
        LOG.debug("Spotify: obtained new access token (expires in {}s)", expiresIn);
    }

    // ── Spotify API calls ─────────────────────────────────────────────────────

    private SpotifyTrackInfo fetchTrack(String id) throws IOException {
        ensureToken();
        DataObject data = apiGet("/tracks/" + id);
        String name = data.getString("name");
        String artist = data.getArray("artists").getObject(0).getString("name");
        return new SpotifyTrackInfo(name, artist);
    }

    private List<SpotifyTrackInfo> fetchPlaylistTracks(String id) throws IOException {
        ensureToken();
        List<SpotifyTrackInfo> tracks = new ArrayList<>();

        // Use fields parameter to reduce payload size
        String endpoint = "/playlists/" + id
                + "/tracks?limit=100&fields=next,items(track(name,artists(name),is_local))";
        DataObject page = apiGet(endpoint);

        while (true) {
            DataArray items = page.getArray("items");
            for (int i = 0; i < items.length(); i++) {
                DataObject item = items.getObject(i);
                // Skip null tracks (e.g. removed tracks) and local files
                if (item.isNull("track"))
                    continue;
                DataObject track = item.getObject("track");
                if (track.isNull("name") || track.isNull("artists"))
                    continue;
                // Skip local files that have no Spotify URI
                if (!track.isNull("is_local") && track.getBoolean("is_local"))
                    continue;
                DataArray artists = track.getArray("artists");
                if (artists.length() == 0)
                    continue;
                tracks.add(new SpotifyTrackInfo(
                        track.getString("name"),
                        artists.getObject(0).getString("name")));
            }

            if (page.isNull("next") || page.getString("next").isEmpty())
                break;
            page = apiGetFull(page.getString("next"));
        }
        return tracks;
    }

    private List<SpotifyTrackInfo> fetchAlbumTracks(String id) throws IOException {
        ensureToken();

        // Fetch the full album to get the primary album artist
        DataObject album = apiGet("/albums/" + id);
        String albumArtist = album.getArray("artists").getObject(0).getString("name");

        List<SpotifyTrackInfo> tracks = new ArrayList<>();
        DataObject page = album.getObject("tracks"); // first page is embedded in album response

        while (true) {
            DataArray items = page.getArray("items");
            for (int i = 0; i < items.length(); i++) {
                DataObject track = items.getObject(i);
                String name = track.getString("name");
                DataArray artists = track.getArray("artists");
                String artist = artists.length() > 0
                        ? artists.getObject(0).getString("name")
                        : albumArtist;
                tracks.add(new SpotifyTrackInfo(name, artist));
            }

            if (page.isNull("next") || page.getString("next").isEmpty())
                break;
            page = apiGetFull(page.getString("next"));
        }
        return tracks;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private DataObject apiGet(String path) throws IOException {
        return apiGetFull(API_BASE + path);
    }

    private DataObject apiGetFull(String fullUrl) throws IOException {
        HttpURLConnection con = openConnection(fullUrl, "GET");
        con.setRequestProperty("Authorization", "Bearer " + accessToken);
        return readJson(con);
    }

    private static HttpURLConnection openConnection(String url, String method) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(10_000);
        con.setReadTimeout(10_000);
        con.setRequestProperty("Accept", "application/json");
        return con;
    }

    private static DataObject readJson(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        InputStream stream = code >= 400 ? con.getErrorStream() : con.getInputStream();
        try (InputStream is = stream) {
            byte[] bytes = is.readAllBytes();
            DataObject obj = DataObject.fromJson(bytes);
            if (code >= 400) {
                String message = obj.hasKey("error")
                        ? obj.opt("error").map(Object::toString).orElse(String.valueOf(code))
                        : String.valueOf(code);
                throw new IOException("Spotify API error " + code + ": " + message);
            }
            return obj;
        }
    }
}
