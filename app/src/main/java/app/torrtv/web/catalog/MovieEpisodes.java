package app.torrtv.web.catalog;
import java.util.regex.*;
final class MovieEpisodes {
 static int season(String text) { return read(text, "(?iu)(?:season|сезон|s)[ ._-]*(\\d{1,3})", -1); }
 static int number(String text) { return read(text, "(?iu)(?:episode|епизод|e)[ ._-]*(\\d{1,4})", 0); }
 static int read(String text, String pattern, int fallback) { Matcher m=Pattern.compile(pattern).matcher(text); return m.find()?Integer.parseInt(m.group(1)):fallback; }
 static String baseTitle(String text) { return text.replaceAll("(?iu)(?:season|сезон)[ ._-]*\\d+.*", "").trim(); }
}
