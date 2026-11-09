package com.mptaptap.server;

import com.mptaptap.core.Track;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real provider test: no linked account or persistent user database. */
@EnabledIfEnvironmentVariable(named="TAPTAP_LIVE_YOUTUBE_KEY_CHECK",matches="true")
class LiveYoutubeKeyCheckTest {
    @Test void serverKeyFindsVideosForUsersWithoutAYouTubeConnection() throws Exception {
        try(var app=new SpringApplicationBuilder(TapTapApplication.class).run(
                "--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:youtube-key-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa","--spring.datasource.password=",
                "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            var remote=app.getBean(PlatformClient.class);assertFalse(remote.setting("YOUTUBE_API_KEY").isBlank());
            var users=app.getBean(Store.class);var session=users.register();String auth="Bearer "+session.get("token");
            assertTrue(app.getBean(PlatformStore.class).connections(session.get("userId")).isEmpty());
            var catalogue=app.getBean(RecommendationCatalogue.class);var json=app.getBean(ObjectMapper.class);var db=app.getBean(JdbcTemplate.class);
            for(var item:List.of(List.of("ポリリズム","Perfume"),List.of("Rising Hope","LiSA"))) {
                Track track=json.readValue(json.writeValueAsString(catalogue.all().get(0)),Track.class);
                track.id=UUID.randomUUID().toString();track.sourceUrl="https://musicbrainz.org/recording/"+track.id;
                track.title=item.get(0);track.artist=item.get(1);track.youtubeVideoId="";
                db.update("INSERT INTO recommendation_tracks (id,track_json) VALUES (?,?)",track.id,json.writeValueAsString(track));
                var result=app.getBean(PlaybackApi.class).resolve(auth,track.id,true).getBody();
                assertNotNull(result);assertTrue(result.playbackAllowed());
                System.out.println("LIVE no-account track="+track.title+" / "+track.artist+" playable="+result.playbackAllowed()+" confirmation="+result.requiresConfirmation());
            }
        }
    }
}
