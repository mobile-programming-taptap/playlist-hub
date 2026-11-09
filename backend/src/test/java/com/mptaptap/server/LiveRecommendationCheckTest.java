package com.mptaptap.server;

import com.mptaptap.core.Conversation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real provider calls, isolated in-memory DB, no account or imported data. */
@EnabledIfEnvironmentVariable(named="TAPTAP_LIVE_AI_CHECK",matches="true")
class LiveRecommendationCheckTest {
    @Test void realRequestReportsGenerationVerificationAndFilteringSeparately() throws Exception {
        try(var app=start()) {
            var ai=app.getBean(OpenAi.class);var catalogue=app.getBean(RecommendationCatalogue.class);
            assertTrue(ai.available());
            String prompt=System.getenv().getOrDefault("TAPTAP_LIVE_PROMPT","신나는 제이팝");
            var json=app.getBean(tools.jackson.databind.ObjectMapper.class);
            Path snapshot=Path.of("../output/checks/live-recommendation/ai-plan.json");
            boolean replay="true".equals(System.getenv("TAPTAP_LIVE_REPLAY"));
            var plan=replay?json.readValue(Files.readString(snapshot),OpenAi.Plan.class):ai.plan(prompt,List.of(),null,Map.of());
            if(!replay){Files.createDirectories(snapshot.getParent());Files.writeString(snapshot,json.writeValueAsString(plan));}
            System.out.println("LIVE generated="+plan.candidates().size()+" conditions="+plan.conditions().tags);
            for(int attempt=1;attempt<=2;attempt++) {
                long started=System.nanoTime();
                try {
                    var verified=catalogue.resolve(plan.candidates());
                    var result=Conversation.recommend(verified,plan.conditions(),Map.of(),"openai",List.of(),List.of());
                    System.out.println("LIVE attempt="+attempt+" verified="+verified.size()+" shown="+result.tracks.size()
                        +" elapsedMs="+(System.nanoTime()-started)/1_000_000);
                    for(var song:plan.candidates())System.out.println("LIVE candidate="+song.title()+" / "+song.artist()
                        +" verified="+verified.stream().anyMatch(track->track.title.equalsIgnoreCase(song.title())&&track.artist.equalsIgnoreCase(song.artist())));
                    assertFalse(result.tracks.isEmpty(),"No verified result for the live request");
                } catch(Exception error) {
                    String reason=error.getMessage();
                    System.out.println("LIVE attempt="+attempt+" failure="+error.getClass().getSimpleName()
                        +" reason="+(reason!=null&&reason.matches("(?:CATALOGUE_[A-Z0-9_]+|RESPONSE_[A-Z_]+)")?reason:"redacted")
                        +" elapsedMs="+(System.nanoTime()-started)/1_000_000);
                    throw error;
                }
            }
        }
    }
    @Test void realApiReplenishesBroadRequestsWithinAppDeadline() throws Exception {
        try(var app=start()) {
            var api=app.getBean(Api.class);String token=app.getBean(Store.class).register().get("token");
            for(String prompt:System.getenv("TAPTAP_LIVE_PROMPT")==null?List.of("신나는 제이팝","드림 팝 음악 추천해줘"):List.of(System.getenv("TAPTAP_LIVE_PROMPT"))) {
                long started=System.nanoTime();
                var result=api.recommendations("Bearer "+token,new Api.Prompt(prompt,List.of(),List.of(),List.of()));
                long elapsed=(System.nanoTime()-started)/1_000_000;
                System.out.println("LIVE API prompt="+prompt+" shown="+result.tracks.size()+" conditions="+result.conditions.tags+" elapsedMs="+elapsed);
                assertEquals("openai",result.mode);assertTrue(result.tracks.size()>=7,"Broad live request must produce at least seven verified picks");
                assertTrue(elapsed<32_000,"Keep the full flow within the Android request timeout");
                assertFalse(result.conditions.tags.contains("unsupported_genre"));
                if(prompt.equals("슈게이즈 음악 추천해줘"))assertTrue(result.conditions.tags.contains("genre:shoegaze"),"Preserve the explicitly requested genre instead of neighboring aliases");
            }
        }
    }
    private org.springframework.context.ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TapTapApplication.class).run(
                "--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:live-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa","--spring.datasource.password=",
                "--taptap.openai.endpoint=https://api.openai.com/v1/responses",
                "--taptap.catalogue.endpoint=https://musicbrainz.org/ws/2/recording/",
                "--logging.level.com.mptaptap.server.RecommendationCatalogue=INFO",
                "--logging.level.com.mptaptap.server.Api=INFO","--logging.level.root=WARN","--spring.main.banner-mode=off");
    }

}
