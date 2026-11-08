package app.torrtv.web.catalog;
import java.util.regex.*;
public final class MovieEpisodes {
 public static int season(String text) {
  Matcher m=Pattern.compile("(?iu)(?:season|сезон|sezon)[\\s_:/–-]*(\\d{1,3})(?!\\d)|(?:^|[^a-z0-9])s(\\d{1,3})e\\d|(?:^|[^0-9])(\\d{1,3})x\\d+").matcher(text);
  if(!m.find())return -1;for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)return Integer.parseInt(m.group(i));return -1;
 }
 public static int number(String text) {
  Matcher m=Pattern.compile("(?iu)(?:episode|епизод|tvsites-episode)[\\s_:=/–-]*(\\d{1,4})|s\\d+e(\\d{1,4})|\\d+x(\\d{1,4})").matcher(text);
  if(m.find())for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)return Integer.parseInt(m.group(i));
  return text.trim().matches("[0-9]{1,4}")?Integer.parseInt(text.trim()):0;
 }
 public static String baseTitle(String text) {
  return java.text.Normalizer.normalize(text.replaceAll("(?iu)(?:season|сезон|sezon)[\\s_–-]*\\d+.*$", "")
   .replaceAll("(?iu)\\bs\\d+e\\d+.*$", "").replaceAll("\\s*\\((19|20)\\d{2}\\)\\s*$", ""),java.text.Normalizer.Form.NFKC)
   .toLowerCase(java.util.Locale.ROOT).replace('ѝ','и').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
 }
}
