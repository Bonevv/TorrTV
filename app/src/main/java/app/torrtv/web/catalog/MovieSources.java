package app.torrtv.web.catalog;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.json.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/** Small provider adapters; no player URLs or tokens are stored in the catalogue. */
public final class MovieSources {
    public enum Provider {
        ZIP("FilmiZip", "https://filmizip.com", "/filmi/"),
        NINE("Filmi9", "https://filmi9.com", "/filmi/1"),
        ZENO("ZenoPlay", "https://zenoplay.to", "/movies/"),
        ALL("Всички филми", "https://vsichkifilmi.net", "/movies"),
        ZT("FilmiZT", "https://filmizt.com", "/filmi/filmizt/29"),
        FEN("FilmiFen", "https://filmifen.com", "/filmi/");
        public final String label, base, catalog;
        Provider(String label, String base, String catalog) { this.label=label; this.base=base; this.catalog=catalog; }
        public String start(String query, int language) {
            if (!query.trim().isEmpty()) {
                String q; try { q=URLEncoder.encode(query,"UTF-8"); } catch(Exception e) { q=""; }
                if (this==ZIP || this==FEN) return base+"/index.php?do=search&subaction=search&story="+q;
                if (this==ZENO) return base+"/?s="+q;
                if (this==ZT) return base+"/search/?q="+q;
                return base+"/search?term="+q;
            }
            if (language==MovieCatalog.BG_AUDIO) {
                if (this==NINE) return base+"/filmi/bg-audio";
                if (this==ALL) return base+"/movies/bg-audio";
                if (this==ZENO) return base+"/genre/bg-audio/";
                if (this==ZT) return base+"/filmi/bgaudio/18";
                if (this==FEN) return base+"/filmi-bg-audio/";
            }
            return base+catalog;
        }
        public String seriesStart(){
            return base+(this==ZIP || this==FEN?"/seriali/":this==NINE?"/seriali":this==ZENO?"/tv-shows/":this==ALL?"/series":"/filmi/seriali/27");
        }
        public boolean owns(String url) {
            try { String host=new URI(url).getHost(); return host!=null && (host.replaceFirst("^www\\.","").equals(new URI(base).getHost()) || this==NINE && host.equals("cdn.filmi9.com") && new URI(url).getPath().startsWith("/movies")); }
            catch(Exception e) { return false; }
        }
    }
    public static final class Page {
        public final List<MovieCatalog.Entry> entries = new ArrayList<>();
        public String next = "";
        final Map<String,String> genres=new LinkedHashMap<>();
    }

    public static Page parse(Provider p, String url, String html) { return parse(p, Jsoup.parse(html,url)); }
    public static Page parse(Provider p, Document doc) {
        if(p==Provider.NINE && doc.location().startsWith("https://cdn.filmi9.com/movies"))return nineApi(doc);
        Page result=new Page(); result.genres.putAll(genres(p,doc)); Set<String> seen=new HashSet<>();
        String selector=p==Provider.ZIP?".poster":p==Provider.FEN?"a.item-poster":p==Provider.ZENO?"a.media-card":
                p==Provider.NINE?"a[href*=/film/][title],a[href*=/serial/][title]":p==Provider.ALL?"a[name=movie_card]":".ml-item";
        Map<String,String> names=new HashMap<>();
        for (Element script:doc.select("script[type=application/ld+json]")) {
            try { collectNames(new JSONTokener(script.data()).nextValue(),names); } catch(Exception ignored) {}
        }
        for (Element node:doc.select(selector)) {
            Element link=node.tagName().equals("a")?node:node.selectFirst("a[href]");
            if(link==null) continue;
            String page=link.absUrl("href");
            if(!isTitle(p,page) || !seen.add(page)) continue;
            Element card=p==Provider.NINE && node.parent()!=null && node.parent().parent()!=null?node.parent().parent():node;
            if(card.tagName().equals("body") || card.tagName().equals("html") ||
                (p==Provider.NINE && card.select("a[href*=/film/][title],a[href*=/serial/][title]").size()>1)) card=node;
            MovieCatalog.Entry e=new MovieCatalog.Entry(); e.provider=p.name(); e.page=page;e.series=isSeries(p,page);
            e.title=text(card,".poster__title,.item-poster__title,.media-title,h2");
            if(e.title.isEmpty()) e.title=link.attr("title");
            Element img=card.selectFirst("img");
            if(img!=null) {
                e.poster=img.absUrl(img.hasAttr("data-original")?"data-original":img.hasAttr("data-src")?"data-src":"src");
                e.original=img.attr("alt").replaceAll("(?i)\\s*\\(\\d{4}\\).*","").replaceAll("(?i)\\s+онлайн$","").trim();
                if(e.title.isEmpty()) e.title=e.original;
            }
            if(names.containsKey(page)) setNames(e,names.get(page));
            else if(p==Provider.ALL && img!=null) setNames(e,img.attr("alt"));
            else if(p==Provider.ZT) setNames(e,link.attr("oldtitle"));
            e.year=year(text(card,".poster__info,.item-poster__year,.media-year"));
            if(e.year==0) for(Element leaf:card.select("span,time"))if(leaf.ownText().trim().matches("(19|20)[0-9]{2}")){e.year=year(leaf.ownText());break;}
            if(e.year==0) e.year=year(card.text());
            e.title=e.title.replaceAll("\\s*\\((?:19|20)\\d{2}\\)\\s*$","").trim();
            if(e.original.equals(e.title) || e.original.startsWith("Гледай филми")) e.original="";
            markLanguage(e,card.text()+" "+card.select("[title]").eachAttr("title"));
            if(!card.select(".icon-subs").isEmpty()) e.bgSubs=true;
            if(inBgCategory(p,doc.location())) e.bgAudio=true;
            String genre=text(card,".item__poster-category"); if(!genre.isEmpty()) e.genres.add(genreName(genre));
            if(p==Provider.NINE) { Element g=card.selectFirst("p:last-child"); if(g!=null) for(String t:g.text().split(" · ")) if(year(t)==0 && !t.matches(".*\\d.*")) e.genres.add(genreName(t)); }
            if(!e.title.isEmpty()) result.entries.add(e);
        }
        for(Element a:doc.select("a[href]")) {
            String t=a.text().trim().toLowerCase(Locale.ROOT), rel=a.attr("rel");
            if(rel.contains("next") || a.hasClass("next") || a.hasClass("swchItem-next") || t.startsWith("напред") || t.contains("следваща") || t.contains("покажи още филми") || a.attr("aria-label").equalsIgnoreCase("Next page")) {
                String next=a.absUrl("href"); if(p.owns(next) && !next.equals(doc.location())) { result.next=next; break; }
            }
        }
        if(p==Provider.NINE && result.next.isEmpty() && doc.location().contains("/genre/")){
            for(Element script:doc.select("script")){
                String data=publicJson(script.data());Matcher id=Pattern.compile("\"genre\"\\s*:\\s*\\{[^}]*\"_id\"\\s*:\\s*\"([^\"]+)\"").matcher(data);
                if(id.find()){result.next="https://cdn.filmi9.com/movies/?page=2&limit=10&sort=-createdAt&genres="+id.group(1);break;}
            }
        }
        for(Map.Entry<String,String> genre:result.genres.entrySet()){
            String category=genre.getValue().replaceAll("/+$","");
            String location=doc.location().replaceAll("/+$","");
            if(location.equals(category) || location.startsWith(category+"/page/") || location.startsWith(category+"-"))
                for(MovieCatalog.Entry e:result.entries)e.genres.add(genre.getKey());
        }
        return result;
    }
    private static boolean inBgCategory(Provider p,String url) {
        if(p==Provider.ZIP || !p.owns(url))return false;
        try {
            String category=new URI(p.start("",MovieCatalog.BG_AUDIO)).getPath().replaceAll("/+$","");
            String path=new URI(url).getPath().replaceAll("/+$","");
            return path.equals(category) || path.startsWith(category+"/page/") || (p==Provider.ZT && path.startsWith(category+"-"));
        }catch(Exception e){return false;}
    }
    private static String publicJson(String text){for(int i=0;i<3;i++)text=text.replace("\\\"","\"").replace("\\/","/");return text;}
    private static Page nineApi(Document doc){
        Page page=new Page();
        try {
            JSONObject root=new JSONObject(doc.body().wholeText());JSONArray data=root.optJSONArray("data");if(data==null)return page;
            for(int i=0;i<data.length();i++){
                JSONObject item=data.optJSONObject(i);if(item==null)continue;MovieCatalog.Entry entry=new MovieCatalog.Entry();entry.provider="NINE";entry.series="series".equals(item.optString("type"));
                String slug=item.optString("slug");if(slug.isEmpty())continue;
                entry.page=Provider.NINE.base+(entry.series?"/serial/":"/film/")+slug;if(!isTitle(Provider.NINE,entry.page))continue;
                setNames(entry,item.optString("title"));entry.year=item.optInt("year");entry.poster=item.optString("movieImage");entry.bgAudio=item.optBoolean("isBgAudio");entry.tmdb=item.optString("tmdb_id","");entry.imdb=item.optString("imdb_id","");entry.description=item.optString("description");
                JSONArray genres=item.optJSONArray("genres");if(genres!=null)for(int g=0;g<genres.length();g++){Object value=genres.opt(g);String name=genreName(value instanceof JSONObject?((JSONObject)value).optString("name"):String.valueOf(value));if(COMMON_GENRES.contains(name))entry.genres.add(name);}
                if(!entry.title.isEmpty())page.entries.add(entry);
            }
            Matcher limit=Pattern.compile("[?&]limit=([0-9]+)").matcher(doc.location());int size=limit.find()?Integer.parseInt(limit.group(1)):10;
            Matcher number=Pattern.compile("([?&]page=)([0-9]+)").matcher(doc.location());if(data.length()>=size && number.find())page.next=number.replaceFirst(number.group(1)+(Integer.parseInt(number.group(2))+1));
        }catch(JSONException ignored){}
        return page;
    }
    public static boolean isSeries(Provider p,String url){
        try{String path=new URI(url).getPath();return p==Provider.ZENO?path.startsWith("/tv-show/"):p==Provider.ZT?path.startsWith("/filmi/seriali/"):path.startsWith("/serial/") || path.startsWith("/seriali/");}catch(Exception e){return false;}
    }
    public static boolean isTitle(Provider p,String url){
        if(isMovie(p,url))return true;
        if(!p.owns(url) || !isSeries(p,url))return false;
        try{String path=new URI(url).getPath();return p==Provider.ZIP || p==Provider.FEN?path.endsWith(".html"):p==Provider.ZT?path.matches(".*/[0-9]+-[0-9]+-[0-9]+-[0-9]+/?"):true;}catch(Exception e){return false;}
    }
    public static Map<String,String> genres(Provider p,Document doc){
        Map<String,String> result=new LinkedHashMap<>();
        for(Element a:doc.select("a[href]")){
            String url=a.absUrl("href"),label=genreName(a.text());
            if(!p.owns(url) || isTitle(p,url) || label.isEmpty() || label.length()>35 || label.matches(".*[0-9].*"))continue;
            String path=URI.create(url).getPath();
            boolean category=path.startsWith("/genre/") || path.startsWith("/g/") || ((p==Provider.ZT || p==Provider.FEN) && path.startsWith("/filmi/"));
            if((category || p==Provider.ZIP) && COMMON_GENRES.contains(label))result.putIfAbsent(label,url);
        }
        return result;
    }
    static final List<String> COMMON_GENRES=Arrays.asList("Екшън","Комедия","Драма","Ужаси","Трилър","Фантастика","Научна фантастика","Анимация","Приключенски","Криминален","Романтични","Семейни","Документални","Исторически","Военни","Музикални","Уестърн","Фентъзи","Мистерия","Спортни","Биографични","Детски","Коледни","Еротични","Тийн");
    static String genreName(String raw){
        String s=raw.trim().toLowerCase(Locale.ROOT).replace("научна-фантастика","научна фантастика");
        if(s.startsWith("комеди"))return "Комедия";if(s.startsWith("драм"))return "Драма";if(s.startsWith("трил"))return "Трилър";
        if(s.startsWith("приключ"))return "Приключенски";if(s.startsWith("кримин"))return "Криминален";if(s.startsWith("роман") || s.equals("романс"))return "Романтични";
        if(s.startsWith("документ"))return "Документални";if(s.startsWith("истор"))return "Исторически";if(s.startsWith("воен"))return "Военни";
        if(s.startsWith("семе"))return "Семейни";if(s.startsWith("музик"))return "Музикални";if(s.startsWith("биограф"))return "Биографични";
        if(s.startsWith("спорт"))return "Спортни";if(s.startsWith("детски, анима"))return "Анимация";
        return s.isEmpty()?"":s.substring(0,1).toUpperCase(Locale.ROOT)+s.substring(1);
    }
    public static boolean isMovie(Provider p,String url) {
        if(!p.owns(url)) return false;
        try {
            String path=new URI(url).getPath();
            if(p==Provider.NINE || p==Provider.ALL) return path.matches("/film/[^/]+/?");
            if(p==Provider.ZENO) return path.matches("/movie/[^/]+/?");
            if(p==Provider.ZT) return path.startsWith("/filmi/") && path.matches(".*/\\d+-\\d+-\\d+-\\d+/?") && !path.contains("/seriali/");
            return path.startsWith("/filmi/") && path.endsWith(".html");
        } catch(Exception ex) { return false; }
    }
    public static MovieCatalog.Entry detail(MovieCatalog.Entry old,Document doc) {
        MovieCatalog.Entry e=new MovieCatalog.Entry();
        e.provider=old.provider;e.page=old.page;e.title=old.title;e.original=old.original;e.poster=old.poster;e.description=old.description;
        e.imdb=old.imdb;e.tmdb=old.tmdb;e.embed=old.embed;e.year=old.year;e.bgAudio=old.bgAudio;e.bgSubs=old.bgSubs;e.series=old.series;
        e.episodes.addAll(old.episodes);e.seasons.addAll(old.seasons);e.genres.addAll(old.genres);
        Element main=doc.selectFirst("article.pmovie,article.detail-page,#mv-info"); if(main==null)main=doc.selectFirst("main"); if(main==null) main=doc.body();
        String title=text(main,".movie__bg-title");if(title.isEmpty())title=text(main,"h1,h3[itemprop=name]");
        Element heading=main.selectFirst("h1");if(heading!=null && heading.selectFirst(".pmovie__original-title")!=null && !heading.ownText().trim().isEmpty())title=heading.ownText().trim();
        if(!title.isEmpty()) { if(e.provider.equals("ZIP") || e.provider.equals("ZT") || title.contains(" / ")) setNames(e,title); else e.title=title.trim(); }
        String original=text(main,".pmovie__original-title,.movie__original-title").replaceAll("\\s*\\((19|20)[0-9]{2}\\)\\s*$","").replaceFirst("^\\s*/\\s*","");
        if(!original.isEmpty()) e.original=original;
        String desc=text(main,".page__text,.detail-overview,[itemprop=description],.full-text,.js-schema-description");
        if(!desc.isEmpty()) e.description=desc;
        int year=year(text(main,".pmovie__quality,.detail-meta,.mvici-right,[itemprop=dateCreated]")); if(year>0) e.year=year;
        for(Element a:main.select(".genre-chip-row a,.pmovie__header-row:first-child a,.mvici-left .entAllCats,[itemprop=genre]")) e.genres.add(genreName(a.text()));
        // Never read navigation labels or schema inLanguage as evidence of a dubbed soundtrack.
        markLanguage(e,text(main,".detail-meta,.pmovie__quality,.translation,.movie-language,[data-language]")
                +" "+main.select(".genre-chip-row a[href*=/bg-audio],.mvic-info a[href*=/bgaudio],.pmovie__header-row a[href*=/filmi-bg-audio]").text());
        Element tmdb=main.selectFirst("[data-tmdb-id]"); if(tmdb!=null) e.tmdb=tmdb.attr("data-tmdb-id");
        for(Element a:main.select("a[href*=imdb.com/title/],a[href*=themoviedb.org/movie/]")) {
            Matcher imdb=Pattern.compile("tt\\d{5,}").matcher(a.attr("href")); if(imdb.find())e.imdb=imdb.group();
            Matcher tm=Pattern.compile("themoviedb.org/movie/(\\d+)").matcher(a.attr("href")); if(tm.find())e.tmdb=tm.group(1);
        }
        for(Element script:doc.select("script[type=application/ld+json]")) {
            try { readMovie(new JSONTokener(script.data()).nextValue(),e); } catch(Exception ignored) {}
        }
        e.title=e.title.replaceAll("\\s*\\((19|20)[0-9]{2}\\)\\s*$","").trim();
        if(e.series)readEpisodes(e,doc);
        else if(e.provider.equals("NINE") || e.provider.equals("ALL")){
            for(Element script:doc.select("script")){
                Matcher embed=Pattern.compile("\"videoUrl\"[ ]*:[ ]*\"(https?://[^\"]+)\"").matcher(publicJson(script.data()));
                if(embed.find()){e.embed=embed.group(1);break;}
            }
        }
        if(e.embed.isEmpty() && e.provider.equals("ZT")){
            Element frame=doc.selectFirst(".movieplay iframe[src]");if(frame!=null && frame.absUrl("src").startsWith("http"))e.embed=frame.absUrl("src");
        }
        e.detailed=true; return e;
    }
    public static void readEpisodes(MovieCatalog.Entry e,Document doc){
        LinkedHashMap<String,MovieCatalog.Episode> found=new LinkedHashMap<>();
        LinkedHashMap<String,MovieCatalog.SeasonLink> seasons=new LinkedHashMap<>();
        int current=MovieEpisodes.season(e.title+" "+e.original+" "+e.page);
        for(Element a:doc.select(".poster a[href],a.item-poster"))if(a.absUrl("href").equals(e.page)){
            int n=MovieEpisodes.season(a.text());if(n>=0)current=n;
        }
        for(Element a:doc.select("a.episode-card,.series-episode,[data-tv_link]:not(.disabled)")){
            String url=a.hasAttr("href")?a.absUrl("href"):a.hasAttr("data-tv_link")?e.page+"#tvsites-episode="+a.attr("data-tv_link"):e.page;
            if(!Provider.valueOf(e.provider).owns(url))continue;
            int season=integer(a.attr("data-season-number"),-1);if(season<0)season=MovieEpisodes.season(url);if(season<0)season=current;
            int number=integer(a.attr("data-episode-number"),0);if(number==0)number=MovieEpisodes.number(url+" "+a.text());if(number==0)number=MovieEpisodes.number(a.text());
            if(a.hasAttr("data-tv_link"))number=integer(a.attr("data-tv_link"),number);
            String label=number>0?"Епизод "+number:a.text().trim();
            found.put(url,new MovieCatalog.Episode(label,url,"",season,number));
        }
        // Public Next.js payloads include the season's episodes and, on Filmi9, other seasons.
        for(Element script:doc.select("script")){
            String data=publicJson(script.data());
            if(e.provider.equals("ZT")){
                Matcher vk=Pattern.compile("vk\\.show\\(\\s*\\d+\\s*,\\s*(\\[\\s*\\[[\\s\\S]*?\\]\\s*\\])\\s*\\)").matcher(data);
                if(vk.find())try{JSONArray players=new JSONArray(vk.group(1).replace('\'', '"'));
                    for(int player=0;player<players.length();player++){JSONArray episodes=players.optJSONArray(player);if(episodes==null)continue;
                        for(int ep=0;ep<episodes.length();ep++){String embed=episodes.optString(ep);if(!embed.startsWith("http"))continue;
                            String page=e.page+"#tvsites-episode="+(ep+1)+"&tvsites-player="+player;
                            found.put(page,new MovieCatalog.Episode("Епизод "+(ep+1),page,embed,current,ep+1));
                        }
                    }
                }catch(JSONException ignored){}
            }
            Object own=jsonField(data,"movie");
            if(own instanceof JSONObject){JSONObject item=(JSONObject)own;if(!item.optString("title").isEmpty())setNames(e,item.optString("title"));addJsonEpisodes(item,e.page,current,found);}
            else {Object episodes=jsonField(data,"episodes");if(episodes instanceof JSONArray){try{addJsonEpisodes(new JSONObject().put("episodes",episodes),e.page,current,found);}catch(JSONException ignored){}}}
            Object related=jsonField(data,"seasons");if(related instanceof JSONArray){JSONArray list=(JSONArray)related;
                for(int i=0;i<list.length();i++){JSONObject item=list.optJSONObject(i);if(item==null)continue;
                    String slug=item.optString("slug");if(slug.isEmpty())continue;String page=Provider.valueOf(e.provider).base+"/serial/"+slug;
                    int season=item.optInt("seasonNumber",MovieEpisodes.season(item.optString("title")+" "+slug));
                    addJsonEpisodes(item,page,season,found);seasons.put(page,new MovieCatalog.SeasonLink(season,page,item.optJSONArray("episodes")!=null));
                }
            }
        }
        if(found.isEmpty())for(Element button:doc.select("button"))if(button.text().trim().matches("Епизод [0-9]+")){
            int number=MovieEpisodes.number(button.text());String page=e.page+"#tvsites-episode="+number;found.put(page,new MovieCatalog.Episode(button.text().trim(),page,"",current,number));
        }
        // Only links with the same series title are seasons; recommendations stay out.
        Set<String> titles=new HashSet<>();titles.add(MovieEpisodes.baseTitle(e.title));titles.add(MovieEpisodes.baseTitle(e.original));titles.remove("");
        for(Element a:doc.select("a[href]")){
            String page=a.absUrl("href");if(!Provider.valueOf(e.provider).owns(page) || page.equals(e.page))continue;
            int season=MovieEpisodes.season(a.text()+" "+page);if(season<0)continue;
            Set<String> labels=new HashSet<>();labels.add(MovieEpisodes.baseTitle(a.attr("title")));labels.add(MovieEpisodes.baseTitle(a.text()));
            Element img=a.selectFirst("img[alt]");if(img!=null)labels.add(MovieEpisodes.baseTitle(img.attr("alt")));
            Element title=a.selectFirst(".poster__title,.item-poster__title");if(title!=null)labels.add(MovieEpisodes.baseTitle(title.text()));
            labels.retainAll(titles);if(labels.isEmpty())continue;
            if(!seasons.containsKey(page))seasons.put(page,new MovieCatalog.SeasonLink(season,page,found.containsKey(page)));
        }
        if(!found.isEmpty() && (current>=0 || found.values().stream().anyMatch(ep->ep.season<0)))seasons.put(e.page,new MovieCatalog.SeasonLink(current,e.page,true));
        e.episodes.clear();e.episodes.addAll(found.values());e.seasons.clear();e.seasons.addAll(seasons.values());e.episodeSchema=2;e.episodesUpdated=System.currentTimeMillis();
    }
    private static int integer(String text,int fallback){try{return Integer.parseInt(text);}catch(NumberFormatException e){return fallback;}}
    private static void addJsonEpisodes(JSONObject item,String page,int fallback,Map<String,MovieCatalog.Episode> found){
        JSONArray episodes=item.optJSONArray("episodes");if(episodes==null)return;
        int season=item.optInt("seasonNumber",MovieEpisodes.season(item.optString("title")+" "+item.optString("slug")));if(season<0)season=fallback;
        for(int i=0;i<episodes.length();i++){
            JSONObject ep=episodes.optJSONObject(i);if(ep==null)continue;int number=ep.optInt("episodeNumber",i+1);
            String embed=ep.optString("videoUrl");JSONArray sources=ep.optJSONArray("sources");
            if(!embed.startsWith("http") && sources!=null && sources.optJSONObject(0)!=null)embed=sources.optJSONObject(0).optString("url");
            String url=page+"#tvsites-episode="+number;found.put(url,new MovieCatalog.Episode("Епизод "+number,url,embed,ep.optInt("seasonNumber",season),number));
        }
    }
    private static Object jsonField(String data,String field){
        Matcher matcher=Pattern.compile(Pattern.quote("\""+field+"\"")+"\\s*:\\s*([\\[{])").matcher(data);if(!matcher.find())return null;
        int start=matcher.end()-1,depth=0;boolean quoted=false,escaped=false;
        for(int i=start;i<data.length();i++){char c=data.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='\"')quoted=false;continue;}
            if(c=='\"')quoted=true;else if(c=='[' || c=='{')depth++;else if((c==']' || c=='}') && --depth==0){try{return new JSONTokener(data.substring(start,i+1)).nextValue();}catch(JSONException e){return null;}}
        }return null;
    }
    private static void readMovie(Object value,MovieCatalog.Entry e) {
        if(value instanceof JSONArray) { JSONArray arr=(JSONArray)value;for(int i=0;i<arr.length();i++)readMovie(arr.opt(i),e); }
        if(!(value instanceof JSONObject))return;
        JSONObject obj=(JSONObject)value;
        if("Movie".equals(obj.optString("@type"))) {
            setNames(e,obj.optString("name",e.title)); e.description=obj.optString("description",e.description);
            e.year=obj.optInt("copyrightYear",e.year);
            if(e.year==0)e.year=year(obj.optString("dateCreated"));
            Object genre=obj.opt("genre"); if(genre instanceof JSONArray)for(int i=0;i<((JSONArray)genre).length();i++)e.genres.add(genreName(((JSONArray)genre).optString(i)));
            else if(genre instanceof String)e.genres.add(genreName((String)genre));
            Matcher imdb=Pattern.compile("tt\\d{5,}").matcher(obj.optString("sameAs"));if(imdb.find())e.imdb=imdb.group();
        }
        if(obj.has("@graph"))readMovie(obj.opt("@graph"),e);
    }
    private static void collectNames(Object v,Map<String,String> names) {
        if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++)collectNames(a.opt(i),names);}
        if(v instanceof JSONObject){JSONObject o=(JSONObject)v;if(o.has("url") && o.has("name"))names.put(o.optString("url"),o.optString("name"));
            if(o.has("itemListElement"))collectNames(o.opt("itemListElement"),names);if(o.has("@graph"))collectNames(o.opt("@graph"),names);}
    }
    public static void setNames(MovieCatalog.Entry e,String value) {
        if(value.trim().isEmpty())return;
        String[] names=value.split("\\s+/\\s+",2);
        if(names.length==2) {
            if(names[0].matches(".*[А-Яа-я].*") && !names[1].matches(".*[А-Яа-я].*")){e.title=names[0].trim();e.original=names[1].trim();}
            else {e.original=names[0].trim();e.title=names[1].trim();}
        } else e.title=value.trim();
    }
    public static void markLanguage(MovieCatalog.Entry e,String label) {
        String s=label.toLowerCase(Locale.ROOT);
        e.bgAudio|=s.matches("(?s).*(бг[ \\-_]*(аудио|дублаж)|bg[ \\-_]*audio|българск[ои] (аудио|озвучаване|дублаж)|bulgarian (audio|dub)).*" );
        e.bgSubs|=s.matches("(?s).*(бг[ \\-_]*субтитри|български субтитри|bg[ \\-_]*subs).*" );
    }
    static int year(String text) { Matcher m=Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)").matcher(text);return m.find()?Integer.parseInt(m.group()):0; }
    static String text(Element root,String selector) { Element e=root.selectFirst(selector);return e==null?"":e.text().trim(); }
}
