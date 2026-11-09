package com.mptaptap.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class H2PoolRetirementCheckTest {
    private static final String READER="00000000-0000-0000-0000-000000000002",TRACK="00000000-0000-0000-0000-000000000004",SHARE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @TempDir Path temporary;

    @Test void freshSchemaChecksSurviveRetiringTheirInitializationConnection() throws Exception { exercise(temporary.resolve("fresh"),false); }

    @Test void copied240FilePreservesRowsAndChecksAfterConnectionRetirement() throws Exception {
        Path database=temporary.resolve("legacy");
        try(var input=new ClassPathResource("h2/legacy-2.4.240.mv.db").getInputStream()){Files.copy(input,Path.of(database+".mv.db"));}
        exercise(database,true);
    }

    private void exercise(Path database,boolean legacy) throws Exception {
        String url="jdbc:h2:file:"+database+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("sa");config.setPassword("");config.setMaximumPoolSize(1);config.setMinimumIdle(0);
        try(var pool=new HikariDataSource(config);Connection initializer=pool.getConnection();Connection keeper=DriverManager.getConnection(url,"sa","")) {
            assertTrue(initializer.getMetaData().getDatabaseProductVersion().startsWith("2.5.252"));
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("platform-schema.sql")).populate(initializer);
            if(!legacy)new ResourceDatabasePopulator(new ClassPathResource("h2/legacy-seed.sql")).populate(initializer);
            int oldSession;
            try(var sql=initializer.createStatement();var rows=sql.executeQuery("SELECT SESSION_ID()")){assertTrue(rows.next());oldSession=rows.getInt(1);}
            // H2 2.4.240 bound CHECK(IN ...) comparators to this now-retired physical session.
            pool.evictConnection(initializer);initializer.close();
            try(var replacement=pool.getConnection();var sql=replacement.createStatement();var rows=sql.executeQuery("SELECT SESSION_ID()")){assertTrue(rows.next());assertNotEquals(oldSession,rows.getInt(1));}
            var db=new JdbcTemplate(pool);var store=new Store(db,new DataSourceTransactionManager(pool),new ObjectMapper(),200,20);
            assertEquals(1,db.queryForObject("SELECT reaction FROM recommendation_feedback WHERE user_id=? AND track_id=?",Integer.class,READER,TRACK));
            assertEquals("OPEN",db.queryForObject("SELECT status FROM community_reports WHERE id='00000000-0000-0000-0000-000000000005'",String.class));
            assertEquals("Persisted H2 fixture",store.publicPlaylist(SHARE,null).get("title"));
            String user=store.register().get("userId");
            store.feedback(user,TRACK,1);store.feedback(user,TRACK,-1);
            assertThrows(DataIntegrityViolationException.class,()->store.feedback(user,TRACK,2));
            assertEquals(-1,db.queryForObject("SELECT reaction FROM recommendation_feedback WHERE user_id=? AND track_id=?",Integer.class,user,TRACK));
            for(String target:List.of("playlist","author")) {
                Map<String,Object> report=store.reportPublic(user,SHARE,target,"other","Connection-retirement regression");
                assertEquals("OPEN",report.get("status"));
                for(String status:List.of("DISMISSED","HIDDEN"))assertEquals(1,db.update("UPDATE community_reports SET status=? WHERE id=?",status,report.get("reportId")));
                assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE community_reports SET status='invalid' WHERE id=?",report.get("reportId")));
                assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE community_reports SET target_kind='invalid' WHERE id=?",report.get("reportId")));
            }
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM community_reports",Integer.class));
            assertFalse(keeper.isClosed(),"A second connection must keep the DB alive throughout physical pool retirement.");
        }
    }
}
