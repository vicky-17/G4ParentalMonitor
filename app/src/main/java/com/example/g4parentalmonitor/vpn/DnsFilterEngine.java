package com.example.g4parentalmonitor.vpn;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * DnsFilterEngine — decides what to do with each DNS query.
 *
 * Priority order:
 *   1. SafeSearch redirect  (Google, YouTube, Bing)
 *   2. Block (NXDOMAIN)     (porn / harmful domains)
 *   3. Allow                (forward to upstream 8.8.8.8)
 */
public class DnsFilterEngine {

    // ── Decision types ────────────────────────────────────────────────────────

    public static abstract class FilterDecision {}

    public static class Allow extends FilterDecision {}

    public static class Block extends FilterDecision {}

    public static class SafeSearch extends FilterDecision {
        public final String redirectIp;
        SafeSearch(String ip) { this.redirectIp = ip; }
    }

    // ── SafeSearch IP map ──────────────────────────────────────────────────────
    // Official network-level SafeSearch IPs published by Google/Bing.
    // Returning these IPs causes the browser to connect to servers that enforce SafeSearch.

    private static final String GOOGLE_SAFESEARCH_IP  = "216.239.38.120";
    private static final String YOUTUBE_SAFESEARCH_IP = "216.239.38.119";
    private static final String BING_SAFESEARCH_IP    = "204.79.197.220";

    private static final String[] GOOGLE_DOMAINS  = { "google.com", "www.google.com",
            "google.co.in", "google.co.uk", "google.ca", "google.com.au" };
    private static final String[] YOUTUBE_DOMAINS = { "youtube.com", "www.youtube.com",
            "m.youtube.com", "youtu.be", "youtube-nocookie.com" };
    private static final String[] BING_DOMAINS    = { "bing.com", "www.bing.com" };

    private final Set<String> googleSet  = new HashSet<>(Arrays.asList(GOOGLE_DOMAINS));
    private final Set<String> youtubeSet = new HashSet<>(Arrays.asList(YOUTUBE_DOMAINS));
    private final Set<String> bingSet    = new HashSet<>(Arrays.asList(BING_DOMAINS));

    // ── Block list ─────────────────────────────────────────────────────────────

    private static final Set<String> BLOCK_LIST = new HashSet<>(Arrays.asList(
            // Adult content domains (representative sample — extend as needed)
            "pornhub.com", "www.pornhub.com",
            "xvideos.com", "www.xvideos.com",
            "xnxx.com", "www.xnxx.com",
            "xhamster.com", "www.xhamster.com",
            "redtube.com", "www.redtube.com",
            "youporn.com", "www.youporn.com",
            "tube8.com", "www.tube8.com",
            "xtube.com", "www.xtube.com",
            "spankbang.com", "www.spankbang.com",
            "beeg.com", "www.beeg.com",
            "tnaflix.com", "www.tnaflix.com",
            "txxx.com", "www.txxx.com",
            "hclips.com", "www.hclips.com",
            "porntrex.com", "www.porntrex.com",
            "4tube.com", "www.4tube.com",
            "drtuber.com", "www.drtuber.com",
            "keezmovies.com",
            "motherless.com",
            "literotica.com",
            "cam4.com", "chaturbate.com",
            "onlyfans.com",
            // Gambling
            "bet365.com", "draftkings.com", "fanduel.com",
            // Other harmful
            "roblox.com" // commonly blocked by parents — remove if not needed
    ));

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * @param domain    e.g. "www.youtube.com"
     * @param queryType DNS query type (1 = A, 28 = AAAA, etc.)
     */
    public FilterDecision decide(String domain, int queryType) {
        if (domain == null || domain.isEmpty()) return new Allow();

        String d = domain.toLowerCase().trim();
        // Strip trailing dot
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);

        // 1. SafeSearch (only hijack A / AAAA queries)
        if (queryType == 1 || queryType == 28) {
            if (googleSet.contains(d))  return new SafeSearch(GOOGLE_SAFESEARCH_IP);
            if (youtubeSet.contains(d)) return new SafeSearch(YOUTUBE_SAFESEARCH_IP);
            if (bingSet.contains(d))    return new SafeSearch(BING_SAFESEARCH_IP);
        }

        // 2. Block list
        if (isBlocked(d)) return new Block();

        // 3. Allow
        return new Allow();
    }

    private boolean isBlocked(String domain) {
        if (BLOCK_LIST.contains(domain)) return true;
        // Also check base domain (e.g. "sub.pornhub.com" → "pornhub.com")
        int dot = domain.indexOf('.');
        if (dot > 0) {
            String base = domain.substring(dot + 1);
            return BLOCK_LIST.contains(base);
        }
        return false;
    }
}