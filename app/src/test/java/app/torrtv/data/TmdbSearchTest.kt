package app.torrtv.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class TmdbSearchTest {
    @Test fun browseAndSearchUseTheCorrectYearFieldForEachMediaType() {
        assertEquals(mapOf("primary_release_year" to "2002"), Tmdb.yearParams(MediaType.MOVIE, 2002))
        assertEquals(mapOf("first_air_date_year" to "2020"), Tmdb.yearParams(MediaType.TV, 2020))
        assertTrue(Tmdb.yearParams(MediaType.TV, null).isEmpty())
    }
    @Test fun peopleSearchKeepsActorsButRejectsCrewAdultAndDuplicateRows() {
        val j=JSONObject("""{"results":[{"id":31,"name":"Tom Hanks","known_for_department":"Acting","profile_path":"/tom.jpg"},{"id":31,"name":"Tom Hanks","known_for_department":"Acting"},{"id":32,"name":"Director","known_for_department":"Directing"},{"id":33,"name":"Adult","known_for_department":"Acting","adult":true}]}""")
        val actors=Tmdb.parseActors(j)
        assertEquals(1,actors.size)
        assertEquals("Tom Hanks",actors.first().name)
        assertEquals("/tom.jpg",actors.first().photo)
    }
    @Test fun creditsUseCastOnlyAndMergeRolesWithoutCollapsingMovieAndTvIds() {
        val j=JSONObject("""{"cast":[{"id":7,"media_type":"movie","title":"Film","release_date":"2002-01-01","vote_count":100},{"id":7,"media_type":"movie","title":"Film","release_date":"2002-01-01","character":"Second role"},{"id":7,"media_type":"tv","name":"Show","first_air_date":"2020-01-01"},{"id":8,"media_type":"movie","adult":true}],"crew":[{"id":9,"media_type":"movie","title":"Directed only"}]}""")
        val credits=Tmdb.parseCredits(j)
        assertEquals(2,credits.size)
        assertEquals(setOf("movie-7","tv-7"),credits.map { it.key }.toSet())
        assertEquals("2002",credits.first().year)
    }
}
