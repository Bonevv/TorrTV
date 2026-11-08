package app.torrtv.web.catalog;

import java.text.Normalizer;
import java.util.*;

/** Source records are kept separately: language belongs to a version, not the whole film. */
public final class MovieCatalog {
    public static final int ALL = 0, BG_AUDIO = 1, BG_SUBS = 2;
    public static final class Entry {
        public String provider = "", page = "", title = "", original = "", poster = "", description = "";
        public String imdb = "", tmdb = "", embed="";
        public final List<Episode> episodes=new ArrayList<>();
        public final List<SeasonLink> seasons=new ArrayList<>();
        public int episodeSchema;
        public long episodesUpdated;
        public int year;
        public boolean bgAudio, bgSubs, detailed, series;
        public long added;
        public final Set<String> genres = new LinkedHashSet<>();
        public String language() { return bgAudio ? "БГ аудио" : bgSubs ? "БГ субтитри" : "Езикът не е указан"; }
        public boolean accepts(int language) { return language == ALL || (language == BG_AUDIO ? bgAudio : bgSubs); }
    }
    public static final class Episode {
        public final String name,page,embed;
        public final int season,number;
        Episode(String name,String page,String embed){this(name,page,embed,-1,MovieEpisodes.number(name));}
        Episode(String name,String page,String embed,int season,int number){this.name=name;this.page=page;this.embed=embed;this.season=season;this.number=number;}
    }
    public static final class SeasonLink {
        public final int number;final String page;final boolean loaded;
        SeasonLink(int number,String page,boolean loaded){this.number=number;this.page=page;this.loaded=loaded;}
    }
}
