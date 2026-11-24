/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.util;

import android.os.Build;
import android.text.Html;
import android.text.TextUtils;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HtmlUtils {
    private static final Pattern ANCHOR_PATTERN =
            Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Strips HTML tags and decodes entities using Android's built-in parser (zero Jsoup dependency).
     */
    @SuppressWarnings("deprecation")
    public static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) {
            return html.trim();
        }
        CharSequence spanned;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        } else {
            spanned = Html.fromHtml(html);
        }
        return spanned.toString().trim();
    }

    /**
     * Replaces bare URL anchor tags with shortened hostnames for TTS readability without Jsoup.
     */
    public static String shortenLinksInHtml(String html, LinkFormatter formatter) {
        if (html == null || !html.contains("<a") && !html.contains("<A")) {
            return stripHtml(html);
        }
        Matcher matcher = ANCHOR_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String href = matcher.group(1);
            String innerText = stripHtml(matcher.group(2));
            if (href != null && href.equals(innerText)) {
                String hostname = getHostnameFromLink(href);
                if (hostname != null) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(formatter.formatHostname(hostname)));
                    continue;
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(innerText));
        }
        matcher.appendTail(sb);
        return stripHtml(sb.toString());
    }

    public interface LinkFormatter {
        String formatHostname(String hostname);
    }

    /**
     * Tries to get the link's hostname, returns `null` if not a valid URL.
     * @param link link to get the hostname of
     * @return String?
     */
    public static String getHostnameFromLink(String link) {
        // Cheap pre-check
        if (link != null && link.contains("://")) {
            try {
                URI maybeURI = URI.create(link);
                if (!TextUtils.isEmpty(maybeURI.getHost())) {
                    return maybeURI.getHost();
                }
            } catch (IllegalArgumentException e) {
                // not a valid URI
            }
        }
        return null;
    }
}
