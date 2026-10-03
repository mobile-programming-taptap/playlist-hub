package com.mptaptap.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC and HTTP; all provider requests stay on the fixture's loopback server. */
class PlatformCheckTest {
    private static final String SOURCE="S".repeat(22), SONG="T".repeat(22), TARGET="R".repeat(22), VIDEO="dQw4w9WgXcQ";
    private final ObjectMapper json=new ObjectMapper();

    @Test void officialOAuthRepeatedTracksAndLostTransferResponsesRemainIdempotent() throws Exception {
        try(Fixture f=new Fixture()) {
            Map<String,String> start=f.api.start(f.auth,"spotify");
            assertTrue(start.get("authorizationUrl").contains("code_challenge_method=S256"));
            assertFalse(start.get("authorizationUrl").contains("Bearer"));
            f.challenge=query(URI.create(start.get("authorizationUrl")),"code_challenge");
            assertEquals("taptap://connected?provider=spotify&status=connected",f.api.callback("spotify",start.get("state"),"code-only-for-test",null).getHeaders().getLocation().toString());
            assertThrows(IllegalArgumentException.class,()->f.api.callback("spotify",start.get("state"),"code-only-for-test",null));
            String ciphertext=f.db.queryForObject("SELECT token_json FROM platform_connections WHERE user_id=?",String.class,f.owner);
            assertFalse(ciphertext.contains("test-access"));assertFalse(json.writeValueAsString(f.api.connections(f.auth)).contains("test-refresh"));

            String id=UUID.randomUUID().toString();
            var request=new PlatformApi.ImportBody(id,"spotify","https://open.spotify.com/playlist/"+SOURCE,null);
            Map<String,Object> imported=f.api.importPlaylist(f.auth,request);
            assertEquals(2,rows(imported,"items").size(),"The same recording occurs twice and stays twice.");
            assertEquals(1,f.users.playlists(f.owner).size());
            f.api.importPlaylist(f.auth,request);
            assertEquals(2,rows(f.users.playlist(f.owner,id),"items").size());
            for(Map<String,Object> item:rows(f.users.playlist(f.owner,id),"items")){assertNull(item.get("trackId"));assertNull(map(item.get("track")).get("recommendationTrackId"));assertEquals(true,map(item.get("track")).get("external"));}
            assertEquals(Map.of(),f.users.feedback(f.owner));
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.imported(f.otherAuth,id)).getStatusCode().value());

            Map<String,Object> retained=f.api.imported(f.auth,id);Instant checkedAt=f.store.metadataCheckedAt(f.owner,id);
            f.users.delete(f.owner,id);assertEquals(id,f.api.imported(f.auth,id).get("localPlaylistId"));
            String otherOwner=f.users.user(f.otherAuth);f.users.createPlaylist(otherOwner,"Other owner's list",id);
            var restoreList=new PlatformApi.SaveImport("Restored source",id,null);
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.saveImport(f.auth,id,restoreList)).getStatusCode().value());
            assertEquals("Other owner's list",f.users.playlist(otherOwner,id).get("title"));assertEquals(retained,f.api.imported(f.auth,id));
            f.users.delete(otherOwner,id);f.api.saveImport(f.auth,id,restoreList);
            Map<String,Object> recreated=f.users.playlist(f.owner,id);assertEquals("Restored source",recreated.get("title"));assertEquals(2,rows(recreated,"items").size());
            assertEquals(checkedAt.toString(),map(rows(recreated,"items").get(0).get("track")).get("checkedAt"));
            List<Object> restoredIds=rows(recreated,"items").stream().map(item->item.get("id")).toList();
            f.api.saveImport(f.auth,id,restoreList);assertEquals(1,f.users.playlists(f.owner).size());
            assertEquals(restoredIds,rows(f.users.playlist(f.owner,id),"items").stream().map(item->item.get("id")).toList());
            assertEquals(checkedAt,f.store.metadataCheckedAt(f.owner,id));

            Map<String,Object> matches=f.api.matches(f.auth,new PlatformApi.MatchBody(id,"spotify",0));
            assertEquals(-1,matches.get("nextOffset"));
            assertThrows(IllegalArgumentException.class,()->f.api.transfer(f.auth,new PlatformApi.TransferBody(UUID.randomUUID().toString(),id,"spotify","Private transfer",true)),"Scored matches still require explicit review.");
            f.api.review(f.auth,id,new PlatformApi.ReviewBody("spotify",List.of(new PlatformApi.Selection(0,SONG,false),new PlatformApi.Selection(1,SONG,false))));
            assertEquals(true,rows(f.api.storedReview(f.auth,id,"spotify"),"items").get(1).get("approved"));

            String jobId=UUID.randomUUID().toString();
            var transfer=new PlatformApi.TransferBody(jobId,id,"spotify","Private transfer",true);
            assertThrows(IllegalArgumentException.class,()->f.api.transfer(f.auth,new PlatformApi.TransferBody(jobId,id,"spotify","Private transfer",false)));
            assertEquals(0,f.creates.get());
            f.holdCreate.set(true);f.api.transfer(f.auth,transfer);assertTrue(f.createStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try{assertEquals(409,assertThrows(ResponseStatusException.class,()->f.api.deleteTransfer(f.auth,jobId)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.deleteTransfer(f.otherAuth,jobId)).getStatusCode().value());}finally{f.releaseCreate.countDown();}
            Map<String,Object> first=f.awaitJob(jobId,"PARTIAL");
            assertNotEquals("COMPLETED",first.get("status"));assertEquals(1,f.creates.get());assertTrue(f.remoteItems.isEmpty());
            first.put("status","RUNNING");f.store.update(f.owner,jobId,"transfer",first);
            f.api.retry(f.auth,jobId,new PlatformApi.Confirm(true));
            Map<String,Object> second=f.awaitJob(jobId,"UNKNOWN");
            assertEquals("UNKNOWN",second.get("status"));assertEquals(1,f.creates.get());assertEquals(1,f.adds.get());
            assertEquals(1,((Number)map(second.get("counts")).get("unknown")).intValue());
            f.api.retry(f.auth,jobId,new PlatformApi.Confirm(true));
            Map<String,Object> complete=f.awaitJob(jobId,"COMPLETED");
            assertEquals("COMPLETED",complete.get("status"));assertEquals(List.of(SONG,SONG),f.remoteItems);
            assertEquals(1,f.creates.get());assertEquals(2,f.adds.get());assertEquals(2,((Number)map(complete.get("counts")).get("added")).intValue());
            assertEquals("https://open.spotify.com/playlist/"+TARGET,complete.get("playlistUrl"));assertEquals(1,f.api.transferHistory(f.auth,true).size());assertFalse(f.api.transferHistory(f.auth,true).get(0).containsKey("items"));assertFalse(f.api.imports(f.auth,true).get(0).containsKey("items"));
            f.api.transfer(f.auth,transfer);f.api.retry(f.auth,jobId,new PlatformApi.Confirm(true));
            assertEquals(2,f.adds.get(),"Replay of the confirmed job never sends its songs again.");

            Map<String,Object> preview=f.api.syncPreview(f.auth,id);
            assertEquals(2,rows(f.users.playlist(f.owner,id),"items").size(),"Preview has no local writes.");
            f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Reviewed source",id,List.of(new PlatformApi.TimelineEdit(0,"Edited source","Artist",false,null),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null))));
            assertEquals("Reviewed source",f.users.playlist(f.owner,id).get("title"),"Saving a new title updates the actual local playlist.");
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.saveImport(f.otherAuth,id,new PlatformApi.SaveImport("Other owner",id,null))).getStatusCode().value());
            Map<String,Object> beforeRollback=f.api.imported(f.auth,id);
            f.db.execute("ALTER TABLE platform_documents ADD CONSTRAINT taptap_save_rollback CHECK(document_json NOT LIKE '%Rollback title%')");
            try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Rollback title",id,List.of(new PlatformApi.TimelineEdit(0,"Rollback song","Artist",false,null),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null)))));}
            finally{f.db.execute("ALTER TABLE platform_documents DROP CONSTRAINT taptap_save_rollback");}
            assertEquals("Reviewed source",f.users.playlist(f.owner,id).get("title"));assertEquals("Edited source",map(rows(f.users.playlist(f.owner,id),"items").get(0).get("track")).get("title"));assertEquals(beforeRollback,f.api.imported(f.auth,id),"A failed document write rolls back playlist title and track changes too.");
            String destination=UUID.randomUUID().toString();f.users.createPlaylist(f.owner,"Keep destination name",destination);f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Source name",destination,null));
            assertEquals("Keep destination name",f.users.playlist(f.owner,destination).get("title"),"Appending to a different owned playlist preserves that playlist's name.");
            f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Reviewed source",id,List.of(new PlatformApi.TimelineEdit(0,"Edited source","Artist",false,null),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null))));f.users.delete(f.owner,destination);
            Map<String,Object> editedPreview=f.api.syncPreview(f.auth,id);assertEquals(1,editedPreview.get("added"));assertEquals(0,editedPreview.get("removed"));assertEquals(1,editedPreview.get("changed"));
            f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Repeated songs",id,List.of(new PlatformApi.TimelineEdit(0,"Edited again","Artist",false,null),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null))));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)editedPreview.get("previewId")))).getStatusCode().value(),"A later user edit invalidates the preview.");
            assertEquals("Edited again",map(rows(f.users.playlist(f.owner,id),"items").get(0).get("track")).get("title"));
            Map<String,Object> mainPreview=f.api.syncPreview(f.auth,id);String editedItem=(String)rows(f.users.playlist(f.owner,id),"items").get(0).get("id");
            f.users.editExternal(f.owner,id,editedItem,"Edited in main","Artist",null);
            assertEquals(409,assertThrows(ResponseStatusException.class,()->f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)mainPreview.get("previewId")))).getStatusCode().value(),"Editing the actual playlist invalidates the preview even when the import document is unchanged.");
            Map<String,Object> other=Map.of("platform","spotify","externalId",SONG,"title","Other import","artist","Artist","sourceUrl","https://open.spotify.com/track/"+SONG,"sourceIndex",99);
            String unrelated=(String)f.users.addExternal(f.owner,id,List.of(other),UUID.randomUUID().toString()).get(0).get("id");
            Map<String,Object> confirmed=f.api.syncPreview(f.auth,id);assertEquals(1,confirmed.get("added"));assertEquals(1,confirmed.get("changed"));
            String confirmedId=(String)confirmed.get("previewId");
            Map<String,Object> beforeSyncPlaylist=f.users.playlist(f.owner,id),beforeSyncImport=f.api.imported(f.auth,id),beforeSyncPreview=f.store.document(f.owner,confirmedId,"sync");
            String beforeSyncExpiry=f.db.queryForObject("SELECT metadata_expires_at FROM platform_documents WHERE id=?",String.class,id);
            f.db.execute("ALTER TABLE platform_documents ADD CONSTRAINT taptap_sync_rollback CHECK(id <> '"+id+"' OR kind <> 'import' OR document_json LIKE '%\"reviewedItems\"%')");
            try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,confirmedId)));}
            finally{f.db.execute("ALTER TABLE platform_documents DROP CONSTRAINT taptap_sync_rollback");}
            assertEquals(beforeSyncPlaylist,f.users.playlist(f.owner,id),"A failed sync document write rolls back track snapshots, order and other sources.");
            assertEquals(beforeSyncImport,f.api.imported(f.auth,id));assertEquals(beforeSyncExpiry,f.db.queryForObject("SELECT metadata_expires_at FROM platform_documents WHERE id=?",String.class,id));
            assertEquals(beforeSyncPreview,f.store.document(f.owner,confirmedId,"sync"),"The same preview remains available after a failed apply.");
            f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)confirmed.get("previewId")));assertEquals(3,rows(f.users.playlist(f.owner,id),"items").size());
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.store.document(f.owner,confirmedId,"sync")).getStatusCode().value(),"Retrying the unchanged preview succeeds and consumes it.");
            assertTrue(rows(f.users.playlist(f.owner,id),"items").stream().anyMatch(item->"Other import".equals(map(item.get("track")).get("title"))));f.users.remove(f.owner,id,unrelated);
            Map<String,Object> orderPreview=f.api.syncPreview(f.auth,id);List<Map<String,Object>> currentRows=rows(f.users.playlist(f.owner,id),"items");
            f.users.reorder(f.owner,id,List.of((String)currentRows.get(1).get("id"),(String)currentRows.get(0).get("id")));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)orderPreview.get("previewId")))).getStatusCode().value());
            Map<String,Object> deletionPreview=f.api.syncPreview(f.auth,id);f.users.remove(f.owner,id,(String)currentRows.get(0).get("id"));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)deletionPreview.get("previewId")))).getStatusCode().value());
            Map<String,Object> restore=f.api.syncPreview(f.auth,id);assertEquals(1,restore.get("added"));f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)restore.get("previewId")));
            f.sourceItems.set(1);
            Map<String,Object> changed=f.api.syncPreview(f.auth,id);
            assertEquals(1,((Number)changed.get("removed")).intValue());
            assertThrows(IllegalArgumentException.class,()->f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(false,(String)changed.get("previewId"))));
            f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)changed.get("previewId")));
            assertEquals(1,rows(f.users.playlist(f.owner,id),"items").size());
            Instant fetchedAt=Instant.now().minus(Duration.ofDays(29));String expiresAt=fetchedAt.plus(Duration.ofDays(30)).toString();f.db.update("UPDATE platform_documents SET metadata_expires_at=? WHERE id=?",expiresAt,id);
            f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Repeated songs",id,List.of(new PlatformApi.TimelineEdit(0,"Manual aged song","Artist",false,null))));
            Map<String,Object> agedItem=rows(f.users.playlist(f.owner,id),"items").get(0);Map<String,Object> agedTrack=map(agedItem.get("track"));assertEquals(fetchedAt,Instant.parse((String)agedTrack.get("checkedAt")),"Manual review keeps the original provider retrieval age.");
            assertEquals(expiresAt,f.db.queryForObject("SELECT metadata_expires_at FROM platform_documents WHERE id=?",String.class,id));assertEquals(404,assertThrows(ResponseStatusException.class,()->f.store.metadataCheckedAt(f.users.user(f.otherAuth),id)).getStatusCode().value());
            String share=(String)f.users.publish(f.owner,id,true).get("shareId");Map<String,Object> published=json.readValue(f.db.queryForObject("SELECT snapshot_json FROM public_playlists WHERE share_id=?",String.class,share),Map.class);
            // Advance both fixture snapshots by two days without changing the production clock.
            agedTrack.put("checkedAt",fetchedAt.minus(Duration.ofDays(2)).toString());map(rows(published,"items").get(0).get("track")).put("checkedAt",agedTrack.get("checkedAt"));
            f.db.update("UPDATE playlist_items SET track_json=? WHERE id=?",json.writeValueAsString(agedTrack),agedItem.get("id"));f.db.update("UPDATE public_playlists SET snapshot_json=? WHERE share_id=?",json.writeValueAsString(published),share);
            f.db.update("UPDATE platform_documents SET metadata_expires_at=? WHERE id=?",Instant.now().minusSeconds(1).toString(),id);f.store.purgeExpired();f.users.purgeExpiredExternal();
            assertEquals(true,f.api.imported(f.auth,id).get("needsRefresh"));for(Map<String,Object> expired:List.of(map(rows(f.users.playlist(f.owner,id),"items").get(0).get("track")),map(rows(f.users.publicPlaylist(share,f.owner),"items").get(0).get("track")))){assertEquals(true,expired.get("needsRefresh"));for(String field:List.of("title","artist","album","isrc","durationMs"))assertFalse(expired.containsKey(field));}
            assertNotNull(preview.get("previewId"));
            f.api.deleteTransfer(f.auth,jobId);assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.transferStatus(f.auth,jobId)).getStatusCode().value());assertEquals(List.of(SONG,SONG),f.remoteItems);
            f.api.deleteImport(f.auth,id);assertTrue(f.store.documents(f.owner,"sync").isEmpty());assertEquals(1,rows(f.users.playlist(f.owner,id),"items").size(),"Deleting a source record keeps the app playlist and remote playlist.");
            f.api.unlink(f.auth,"spotify");assertEquals(1,f.users.playlists(f.owner).size());assertEquals(0,rows(f.users.playlist(f.owner,id),"items").size());assertEquals("Repeated songs",f.users.playlist(f.owner,id).get("title"));
            f.assertHealthy();
        }
        try(Fixture f=new Fixture()) {
            f.sourceItems.set(3);f.unavailableSpotifyItems.set(true);
            Map<String,String> start=f.api.start(f.auth,"spotify");f.challenge=query(URI.create(start.get("authorizationUrl")),"code_challenge");f.api.callback("spotify",start.get("state"),"code-only-for-test",null);
            String id=UUID.randomUUID().toString(),jobId=UUID.randomUUID().toString();
            Map<String,Object> imported=f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(id,"spotify","https://open.spotify.com/playlist/"+SOURCE,null));
            assertEquals(List.of(0,2,4),rows(imported,"items").stream().map(row->row.get("sourceIndex")).toList(),"Readable recordings retain their original source positions.");
            assertEquals(3,imported.get("skippedCount"));assertEquals(List.of(1,3,5),rows(imported,"skippedItems").stream().map(row->row.get("sourceIndex")).toList());
            String notice=(String)imported.get("notice");for(String reason:List.of("3개 항목","로컬 파일","음악 곡이 아닌","곡 정보를 제공하지"))assertTrue(notice.contains(reason));
            assertEquals(3,rows(f.users.playlist(f.owner,id),"items").size());
            Map<String,Object> matches=f.api.matches(f.auth,new PlatformApi.MatchBody(id,"spotify",0));assertEquals(List.of(0,2,4),rows(matches,"items").stream().map(row->row.get("sourceIndex")).toList(),"Unavailable rows never become transfer candidates.");
            f.api.review(f.auth,id,new PlatformApi.ReviewBody("spotify",List.of(new PlatformApi.Selection(0,SONG,false),new PlatformApi.Selection(2,SONG,false),new PlatformApi.Selection(4,null,true))));
            f.api.transfer(f.auth,new PlatformApi.TransferBody(jobId,id,"spotify","Reviewed songs",true));f.awaitJob(jobId,"PARTIAL");
            f.api.retry(f.auth,jobId,new PlatformApi.Confirm(true));f.awaitJob(jobId,"UNKNOWN");
            f.api.retry(f.auth,jobId,new PlatformApi.Confirm(true));Map<String,Object> result=f.awaitJob(jobId,"COMPLETED");
            assertEquals(List.of(4),result.get("excludedSourceIndices"));assertEquals(1,map(result.get("counts")).get("excluded"));assertEquals(2,map(result.get("counts")).get("added"));
            assertEquals(List.of(0,2),rows(result,"items").stream().map(row->row.get("sourceIndex")).toList());assertEquals(List.of(SONG,SONG),f.remoteItems);
            assertEquals(List.of(4),f.api.transferStatus(f.auth,jobId).get("excludedSourceIndices"));assertEquals(1,map(f.api.transferHistory(f.auth,true).get(0).get("counts")).get("excluded"));
            f.api.retry(f.auth,jobId,new PlatformApi.Confirm(true));assertEquals(1,f.creates.get());assertEquals(2,f.adds.get());assertEquals(Map.of(),f.users.feedback(f.owner));
            f.sourceItems.set(0);var unreadableRequest=new PlatformApi.ImportBody(UUID.randomUUID().toString(),"spotify","https://open.spotify.com/playlist/"+SOURCE,null);
            Map<String,Object> unreadable=f.api.importPlaylist(f.auth,unreadableRequest);assertEquals(3,unreadable.get("skippedCount"));assertEquals(false,unreadable.get("saved"));assertTrue(rows(unreadable,"items").isEmpty());assertTrue(((String)unreadable.get("notice")).contains("3개 항목"));
            assertEquals(unreadable,f.api.importPlaylist(f.auth,unreadableRequest));assertEquals(1,f.users.playlists(f.owner).size(),"An unreadable source still shows its reasons without creating an empty app list.");f.assertHealthy();
        }
    }

    @Test void pagedMatchingDistinguishesUnsearchedRowsFromEmptySearchesAndKeepsReview() throws Exception {
        try(Fixture f=new Fixture()) {
            f.sourceItems.set(21);
            Map<String,String> start=f.api.start(f.auth,"spotify");f.challenge=query(URI.create(start.get("authorizationUrl")),"code_challenge");f.api.callback("spotify",start.get("state"),"code-only-for-test",null);
            String id=UUID.randomUUID().toString();f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(id,"spotify","https://open.spotify.com/playlist/"+SOURCE,null));
            Map<String,Object> first=f.api.matches(f.auth,new PlatformApi.MatchBody(id,"spotify",0));
            List<Map<String,Object>> initial=rows(first,"items");assertEquals(21,initial.size());assertEquals(20,first.get("nextOffset"));assertEquals(20,f.searches.get());
            for(int i=0;i<20;i++){assertEquals("MATCHED",initial.get(i).get("status"));assertEquals(false,initial.get(i).get("approved"));}
            Map<String,Object> unsearched=initial.get(20);assertEquals("NOT_SEARCHED",unsearched.get("status"));assertTrue(rows(unsearched,"candidates").isEmpty());assertNull(unsearched.get("selected"));assertEquals(false,unsearched.get("approved"));
            assertEquals(json.readTree(json.writeValueAsString(first)),json.readTree(json.writeValueAsString(f.api.storedReview(f.auth,id,"spotify"))),"Paging state survives the stored JSON round trip.");
            assertThrows(IllegalArgumentException.class,()->f.api.transfer(f.auth,new PlatformApi.TransferBody(UUID.randomUUID().toString(),id,"spotify","Paged transfer",true)));
            f.api.review(f.auth,id,new PlatformApi.ReviewBody("spotify",List.of(new PlatformApi.Selection(0,SONG,false),new PlatformApi.Selection(1,null,true))));
            List<Map<String,Object>> selected=rows(f.api.storedReview(f.auth,id,"spotify"),"items");Map<String,Object> approved=new LinkedHashMap<>(selected.get(0)),excluded=new LinkedHashMap<>(selected.get(1));
            assertEquals(true,approved.get("approved"));assertEquals("USER_EXCLUDED",excluded.get("status"));assertEquals(true,excluded.get("approved"));
            f.emptySearch.set(true);Map<String,Object> last=f.api.matches(f.auth,new PlatformApi.MatchBody(id,"spotify",20));
            assertEquals(21,f.searches.get());assertEquals(-1,last.get("nextOffset"));List<Map<String,Object>> completed=rows(last,"items");
            assertEquals(approved,completed.get(0));assertEquals(excluded,completed.get(1),"Loading a later page preserves explicit earlier approval and exclusion.");
            Map<String,Object> missing=completed.get(20);assertEquals("UNKNOWN",missing.get("status"));assertTrue(rows(missing,"candidates").isEmpty());assertNull(missing.get("selected"));assertEquals(false,missing.get("approved"));
            assertEquals(json.readTree(json.writeValueAsString(last)),json.readTree(json.writeValueAsString(f.api.storedReview(f.auth,id,"spotify"))));
            assertThrows(IllegalArgumentException.class,()->f.api.transfer(f.auth,new PlatformApi.TransferBody(UUID.randomUUID().toString(),id,"spotify","Paged transfer",true)),"Searching all pages never approves any unreviewed row.");
            assertEquals(0,f.creates.get());assertEquals(0,f.adds.get());f.assertHealthy();
        }
    }

    @Test void outerTransactionsKeepPlatformMonitorThroughCommit() throws Exception {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();var held=new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<Fixture> current=new AtomicReference<>();AtomicReference<Throwable> overlapFailure=new AtomicReference<>();
        List<java.util.concurrent.Future<?>> overlapping=new ArrayList<>();Thread caller=Thread.currentThread();
        try(Fixture f=new Fixture(()->{
            if(Thread.currentThread()!=caller||!armed.compareAndSet(true,false))return;
            Fixture active=current.get();held.set(Thread.holdsLock(active.users)&&Thread.holdsLock(active.store));
            // OLD code records the unsafe boundary without starting SQL that could deadlock.
            if(!held.get())return;
            try {
                var started=new java.util.concurrent.CountDownLatch(2);List<AtomicReference<Thread>> threads=new ArrayList<>();
                for(boolean purge:List.of(true,false)) {
                    AtomicReference<Thread> thread=new AtomicReference<>();threads.add(thread);
                    overlapping.add(active.worker.submit(()->{thread.set(Thread.currentThread());started.countDown();if(purge)active.store.purgeExpired();else active.store.documents(active.owner,"import");}));
                }
                assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
                long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();boolean blocked=false;
                do {
                    blocked=true;
                    for(var thread:threads) {
                        var info=java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(thread.get().getId());
                        blocked&=info!=null&&info.getThreadState()==Thread.State.BLOCKED&&info.getLockInfo()!=null
                                &&info.getLockInfo().getIdentityHashCode()==System.identityHashCode(active.store)&&info.getLockOwnerId()==caller.getId();
                    }
                    if(!blocked)Thread.sleep(10);
                }while(!blocked&&System.nanoTime()<deadline);
                assertTrue(blocked,"Purge and read wait for the store monitor until the actual JDBC commit finishes.");
            }catch(Throwable error){overlapFailure.set(error);}
        })) {
            current.set(f);f.store.connection(f.owner,"spotify",new PlatformClient.Tokens("test-access","test-refresh","owner","playlist-read-private",Instant.now().plusSeconds(600)));
            String id=UUID.randomUUID().toString();f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(id,"spotify","https://open.spotify.com/playlist/"+SOURCE,null));
            for(boolean sync:List.of(false,true)) {
                Map<String,Object> preview=sync?f.api.syncPreview(f.auth,id):Map.of();String expired=UUID.randomUUID().toString();
                f.store.create(f.owner,expired,"import","spotify",new LinkedHashMap<>(Map.of("id",expired,"title","Expired metadata","sourceRow","private provider text")));
                f.db.update("UPDATE platform_documents SET metadata_expires_at=? WHERE id=?",Instant.now().minusSeconds(1).toString(),expired);
                armed.set(true);held.set(false);
                if(sync)f.api.syncApply(f.auth,id,new PlatformApi.SyncApply(true,(String)preview.get("previewId")));
                else f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Monitor source",id,null));
                assertTrue(held.get(),"Both outer transactions must retain users -> store monitors through JDBC commit.");
                assertNull(overlapFailure.get());for(var future:overlapping)future.get(3,java.util.concurrent.TimeUnit.SECONDS);overlapping.clear();
                Map<String,Object> reference=f.store.document(f.owner,expired,"import");assertEquals(true,reference.get("needsRefresh"));assertFalse(reference.containsKey("sourceRow"));
                assertEquals(2,rows(f.users.playlist(f.owner,id),"items").size());
            }
            f.assertHealthy();
        }
    }

    @Test void officialVideoAndUserTimelineEditsStayOutsideRecommendationsAndAppleUsesMusicKit() throws Exception {
        try(Fixture f=new Fixture()) {
            String id=UUID.randomUUID().toString(),url="https://www.youtube.com/watch?v="+VIDEO;
            var request=new PlatformApi.Conversion(id,url,null,null);
            Map<String,Object> converted=f.api.conversion(f.auth,request);
            assertEquals("official_metadata",converted.get("metadataMode"));assertEquals(2,rows(converted,"entries").size());
            f.api.conversion(f.auth,request);assertEquals(1,f.videos.get(),"A conversion retry uses the same retained preview.");
            assertThrows(ResponseStatusException.class,()->f.api.conversion(f.auth,new PlatformApi.Conversion(id,url,"0:00 Changed",180)));
            assertThrows(IllegalArgumentException.class,()->f.api.conversion(f.auth,new PlatformApi.Conversion(null,"https://127.0.0.1/watch?v="+VIDEO,null,null)));
            assertEquals(1,f.videos.get(),"A pasted URL never becomes an arbitrary HTTP destination.");

            var edit=new PlatformApi.SaveImport("Reviewed timeline",null,List.of(new PlatformApi.TimelineEdit(0,"Edited song","Edited artist",false,150),new PlatformApi.TimelineEdit(1,"Excluded","Artist",true,null)));
            f.api.saveImport(f.auth,id,edit);f.api.saveImport(f.auth,id,edit);
            List<Map<String,Object>> saved=rows(f.users.playlist(f.owner,id),"items");assertEquals(1,saved.size());
            Map<String,Object> track=map(saved.get(0).get("track"));assertNull(saved.get(0).get("trackId"));assertEquals("Edited song",track.get("title"));
            assertEquals(150,((Number)track.get("timestamp")).intValue());assertEquals(180,((Number)track.get("videoDurationSeconds")).intValue());
            assertEquals("timeline",track.get("sourceType"));assertEquals(0,((Number)track.get("durationMs")).intValue());
            assertEquals(url+"&t=150s",track.get("sourceUrl"));assertEquals("0:00 Artist - Song one",track.get("sourceRow"));
            assertEquals(150,((Number)rows(f.api.imported(f.auth,id),"reviewedItems").get(0).get("timestamp")).intValue());
            assertThrows(IllegalArgumentException.class,()->f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Reviewed timeline",null,List.of(new PlatformApi.TimelineEdit(0,"Song","Artist",false,181),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null)))));
            var atEnd=new PlatformApi.SaveImport("Reviewed timeline",null,List.of(new PlatformApi.TimelineEdit(0,"Song","Artist",false,180),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null)));
            String savedItem=(String)saved.get(0).get("id");
            assertAll("A known video end is not a playable start",
                ()->assertThrows(IllegalArgumentException.class,()->f.api.saveImport(f.auth,id,atEnd)),
                ()->assertThrows(IllegalArgumentException.class,()->f.users.editExternal(f.owner,id,savedItem,"Song","Artist",180)));
            assertEquals(track,map(rows(f.users.playlist(f.owner,id),"items").get(0).get("track")),"Rejected start times preserve the saved snapshot.");
            f.api.saveImport(f.auth,id,new PlatformApi.SaveImport("Reviewed timeline",null,List.of(new PlatformApi.TimelineEdit(0,"Song","Artist",false,179),new PlatformApi.TimelineEdit(1,"Song","Artist",true,null))));
            assertEquals(179,map(f.users.editExternal(f.owner,id,savedItem,"Song","Artist",179).get("track")).get("timestamp"));
            String pasted=UUID.randomUUID().toString();
            f.api.conversion(f.auth,new PlatformApi.Conversion(pasted,url,"0:00 Artist - User supplied",null));
            f.api.saveImport(f.auth,pasted,new PlatformApi.SaveImport("Owned text",null,null));
            assertEquals(1,f.videos.get(),"User-owned timeline conversion does not pretend to retrieve video metadata.");
            assertEquals(0,((Number)map(rows(f.users.playlist(f.owner,pasted),"items").get(0).get("track")).get("videoDurationSeconds")).intValue());
            for(int unknown:List.of(0,-1)){
                String unknownId=UUID.randomUUID().toString();f.api.conversion(f.auth,new PlatformApi.Conversion(unknownId,url,"0:00 Artist - Unknown length",unknown));
                f.api.saveImport(f.auth,unknownId,new PlatformApi.SaveImport("Unknown length",null,List.of(new PlatformApi.TimelineEdit(0,"Song","Artist",false,21600))));
                Map<String,Object> unknownItem=rows(f.users.playlist(f.owner,unknownId),"items").get(0);assertEquals(21600,map(unknownItem.get("track")).get("timestamp"));
                assertEquals(21600,map(f.users.editExternal(f.owner,unknownId,(String)unknownItem.get("id"),"Song","Artist",21600).get("track")).get("timestamp"));
                assertThrows(IllegalArgumentException.class,()->f.api.saveImport(f.auth,unknownId,new PlatformApi.SaveImport("Unknown length",null,List.of(new PlatformApi.TimelineEdit(0,"Song","Artist",false,21601)))));
            }
            String shortVideos=UUID.randomUUID().toString();f.users.createPlaylist(f.owner,"Short videos",shortVideos);
            for(int[] boundary:List.of(new int[]{500,0},new int[]{2000,1})){
                Map<String,Object> row=Map.of("platform","youtube","externalId",VIDEO,"title","Short video","artist","Artist","sourceUrl",url,"durationMs",boundary[0],"sourceIndex",boundary[0]);
                String item=(String)f.users.addExternal(f.owner,shortVideos,List.of(row),UUID.randomUUID().toString()).get(0).get("id");
                assertEquals(boundary[1],map(f.users.editExternal(f.owner,shortVideos,item,"Short video","Artist",boundary[1]).get("track")).get("timestamp"));
                assertThrows(IllegalArgumentException.class,()->f.users.editExternal(f.owner,shortVideos,item,"Short video","Artist",boundary[1]+1));
            }
            assertEquals(Map.of(),f.users.feedback(f.owner));assertEquals(0,f.creates.get());

            Map<String,String> apple=f.api.start(f.auth,"apple_music");
            String html=f.api.appleAuthorize(apple.get("state")).getBody();
            assertTrue(html.contains("js-cdn.music.apple.com/musickit/v3/musickit.js"));assertTrue(html.contains("await MusicKit.configure"));
            assertFalse(html.contains(f.auth));assertFalse(html.contains("test-user-token"));
            assertEquals("taptap://connected?provider=apple_music&status=connected",f.api.appleCallback(new PlatformApi.AppleCallback(apple.get("state"),"test-user-token")).get("redirect"));
            assertThrows(IllegalArgumentException.class,()->f.api.appleCallback(new PlatformApi.AppleCallback(apple.get("state"),"test-user-token")));
            Map<String,Object> music=f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(UUID.randomUUID().toString(),"apple_music","https://music.apple.com/us/playlist/owned/pl.u-123",null));
            assertEquals(1,rows(music,"items").size(),"An own public Apple URL resolves through library playParams.globalId.");
            assertFalse(json.writeValueAsString(f.api.connections(f.auth)).contains("test-user-token"));
            String appleId=(String)music.get("id");f.api.matches(f.auth,new PlatformApi.MatchBody(appleId,"apple_music",0));
            f.api.review(f.auth,appleId,new PlatformApi.ReviewBody("apple_music",List.of(new PlatformApi.Selection(0,"123456",false))));
            String appleJob=UUID.randomUUID().toString();f.api.transfer(f.auth,new PlatformApi.TransferBody(appleJob,appleId,"apple_music","Apple private",true));f.awaitJob(appleJob,"PARTIAL");
            f.api.retry(f.auth,appleJob,new PlatformApi.Confirm(true));f.awaitJob(appleJob,"UNKNOWN");
            f.api.retry(f.auth,appleJob,new PlatformApi.Confirm(true));f.awaitJob(appleJob,"COMPLETED");
            assertEquals(1,f.creates.get());assertEquals(1,f.adds.get(),"Apple library IDs reconcile through the official catalogId after response loss.");
            f.api.unlink(f.auth,"apple_music");assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM platform_connections WHERE provider='apple_music'",Integer.class));
            assertEquals(0,rows(f.users.playlist(f.owner,appleId),"items").size());assertEquals(1,rows(f.users.playlist(f.owner,pasted),"items").size(),"Disconnecting Apple preserves unrelated user-owned YouTube rows.");
            f.assertHealthy();
        }
    }

    @Test void youtubeOAuthRefreshPrivateTransferPaginationAndRevocationUseOfficialCalls() throws Exception {
        try(Fixture f=new Fixture()){
            Map<String,String> started=f.api.start(f.auth,"youtube");f.challenge=query(URI.create(started.get("authorizationUrl")),"code_challenge");
            assertTrue(started.get("authorizationUrl").contains("access_type=offline"));
            assertEquals("taptap://connected?provider=youtube&status=connected",f.api.callback("youtube",started.get("state"),"google-test-code",null).getHeaders().getLocation().toString());
            PlatformClient.Tokens old=f.store.tokens(f.owner,"youtube");f.store.connection(f.owner,"youtube",new PlatformClient.Tokens(old.accessToken(),old.refreshToken(),old.accountId(),old.scopes(),Instant.EPOCH));
            String imported=UUID.randomUUID().toString();f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(imported,"youtube","https://www.youtube.com/playlist?list=PLTEST",null));
            assertEquals(1,f.refreshes.get());assertEquals(2,rows(f.users.playlist(f.owner,imported),"items").size());
            f.api.matches(f.auth,new PlatformApi.MatchBody(imported,"youtube",0));f.api.review(f.auth,imported,new PlatformApi.ReviewBody("youtube",List.of(new PlatformApi.Selection(0,VIDEO,false),new PlatformApi.Selection(1,VIDEO,false))));
            String job=UUID.randomUUID().toString();f.api.transfer(f.auth,new PlatformApi.TransferBody(job,imported,"youtube","Private YouTube",true));f.awaitJob(job,"PARTIAL");
            f.api.retry(f.auth,job,new PlatformApi.Confirm(true));f.awaitJob(job,"UNKNOWN");assertTrue(f.markerPages.get()>0,"The created playlist is recovered on a later official page.");
            f.api.retry(f.auth,job,new PlatformApi.Confirm(true));f.awaitJob(job,"COMPLETED");assertEquals(1,f.creates.get());assertEquals(2,f.adds.get());assertEquals(List.of(VIDEO,VIDEO),f.remoteItems);
            String timeline=UUID.randomUUID().toString();f.api.conversion(f.auth,new PlatformApi.Conversion(timeline,"https://www.youtube.com/watch?v="+VIDEO,"0:00 Artist - Completely different recording",180));
            Map<String,Object> candidate=rows(f.api.matches(f.auth,new PlatformApi.MatchBody(timeline,"youtube",0)),"items").get(0);
            assertNotEquals("MATCHED",candidate.get("status"),"A timeline's whole-video ID is not the identity of every named recording.");assertEquals(VIDEO,map(candidate.get("source")).get("externalId"));
            Map<String,String> spotify=f.api.start(f.auth,"spotify");f.challenge=query(URI.create(spotify.get("authorizationUrl")),"code_challenge");f.api.callback("spotify",spotify.get("state"),"spotify-test-code",null);
            String cross=UUID.randomUUID().toString(),retained=UUID.randomUUID().toString();
            f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(cross,"spotify","https://open.spotify.com/playlist/"+SOURCE,null));
            f.api.matches(f.auth,new PlatformApi.MatchBody(cross,"youtube",0));f.api.review(f.auth,cross,new PlatformApi.ReviewBody("youtube",List.of(new PlatformApi.Selection(0,VIDEO,false),new PlatformApi.Selection(1,VIDEO,false))));
            Map<String,Object> before=f.api.imported(f.auth,cross);assertTrue(rows(before,"reviews").stream().allMatch(row->!rows(row,"candidates").isEmpty()));
            String otherId=UUID.randomUUID().toString();Map<String,Object> otherDocument=new LinkedHashMap<>(before);otherDocument.put("id",otherId);otherDocument.put("saved",false);otherDocument.remove("localPlaylistId");f.store.create(f.users.user(f.otherAuth),otherId,"import","spotify",otherDocument);
            f.api.importPlaylist(f.auth,new PlatformApi.ImportBody(retained,"spotify","https://open.spotify.com/playlist/"+SOURCE,null));f.api.matches(f.auth,new PlatformApi.MatchBody(retained,"spotify",0));
            Map<String,Object> unlinked=f.api.unlink(f.auth,"youtube");assertEquals(false,unlinked.get("providerRevocationRequired"));assertEquals(1,f.revokes.get());
            Map<String,Object> after=f.api.imported(f.auth,cross);assertEquals(before.get("items"),after.get("items"));assertEquals(before.get("reviewedItems"),after.get("reviewedItems"));
            for(String field:List.of("reviews","targetProvider","nextOffset"))assertFalse(after.containsKey(field),"Disconnect removes target matching data from another provider's source import.");
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.storedReview(f.auth,cross,"youtube")).getStatusCode().value());
            assertEquals(2,rows(f.users.playlist(f.owner,cross),"items").size());assertEquals(2,rows(f.api.storedReview(f.auth,retained,"spotify"),"items").size());
            assertEquals(before.get("reviews"),f.api.storedReview(f.otherAuth,otherId,"youtube").get("items"),"Disconnect does not erase another owner's matching data.");
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.transferStatus(f.auth,job)).getStatusCode().value());f.assertHealthy();
        }
    }

    @Test void stalledBodiesHaveADeadlineAndUnlinkCannotResurrectAnInFlightConnection() throws Exception {
        try(Fixture f=new Fixture()){
            f.stallVideo.set(true);
            var pending=f.worker.submit(()->f.api.conversion(f.auth,new PlatformApi.Conversion(UUID.randomUUID().toString(),"https://www.youtube.com/watch?v="+VIDEO,null,null)));
            assertTrue(f.videoStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try{java.util.concurrent.ExecutionException failure=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(20,java.util.concurrent.TimeUnit.SECONDS));assertInstanceOf(PlatformClient.Failure.class,failure.getCause());}
            finally{f.stallVideo.set(false);f.releaseVideo.countDown();}
            f.api.conversion(f.auth,new PlatformApi.Conversion(UUID.randomUUID().toString(),"https://www.youtube.com/watch?v="+VIDEO,null,null));
            Map<String,String> started=f.api.start(f.auth,"spotify");f.challenge=query(URI.create(started.get("authorizationUrl")),"code_challenge");f.holdExchange.set(true);
            var callback=f.worker.submit(()->f.api.callback("spotify",started.get("state"),"held-code",null));assertTrue(f.exchangeStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var unlinkStarted=new java.util.concurrent.CountDownLatch(1);var unlink=f.worker.submit(()->{unlinkStarted.countDown();return f.api.unlink(f.auth,"spotify");});assertTrue(unlinkStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->unlink.get(200,java.util.concurrent.TimeUnit.MILLISECONDS),"Disconnect waits for the held token exchange rather than deleting before its late response.");
            f.releaseExchange.countDown();assertEquals("taptap://connected?provider=spotify&status=connected",callback.get(5,java.util.concurrent.TimeUnit.SECONDS).getHeaders().getLocation().toString());assertEquals(true,unlink.get(5,java.util.concurrent.TimeUnit.SECONDS).get("unlinked"));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM platform_connections WHERE user_id=?",Integer.class,f.owner));f.assertHealthy();
        }
    }

    @Test void stateUpgradeSessionBindingExpiryAndAccountDeletionPreserveUnrelatedData() throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:platform-upgrade-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(data);new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(data);
        var manager=new DataSourceTransactionManager(data);var users=new Store(db,manager,json,200,20);
        Map<String,String> registered=users.register();String owner=registered.get("userId"),auth="Bearer "+registered.get("token");
        String playlist=UUID.randomUUID().toString();users.createPlaylist(owner,"Keep me",playlist);
        db.execute("CREATE TABLE platform_oauth_states(state_hash VARCHAR(64) PRIMARY KEY,user_id VARCHAR(36) REFERENCES users(id) ON DELETE CASCADE,provider VARCHAR(20),verifier TEXT,expires_at VARCHAR(40))");
        db.update("INSERT INTO platform_oauth_states VALUES(?,?,?,?,?)","legacy",owner,"spotify","old-unbound",Instant.now().plusSeconds(600).toString());
        String key=Base64.getEncoder().encodeToString(new byte[32]);PlatformStore store=new PlatformStore(db,manager,json,key);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM platform_oauth_states",Integer.class));
        store.connection(owner,"spotify",new PlatformClient.Tokens("kept-token","refresh","owner","scope",Instant.now().plusSeconds(600)));
        PlatformStore restarted=new PlatformStore(db,manager,json,key);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->restarted.create(owner,UUID.randomUUID().toString(),"unknown-kind","spotify",new LinkedHashMap<>(Map.of("title","Rejected"))));
        assertEquals("kept-token",restarted.tokens(owner,"spotify").accessToken());assertEquals(1,users.playlists(owner).size());
        Map<String,String> pending=restarted.start(owner,"spotify",auth);
        db.update("UPDATE users SET token_hash=? WHERE id=?",PlatformStore.hash("X".repeat(43)),owner);
        assertThrows(IllegalArgumentException.class,()->restarted.pending(pending.get("state"),"spotify",true),"Logged-out or replaced sessions cannot finish old authorization.");
        db.update("UPDATE users SET token_hash=? WHERE id=?",PlatformStore.hash(registered.get("token")),owner);
        String id=UUID.randomUUID().toString();restarted.create(owner,id,"import","spotify",new LinkedHashMap<>(Map.of("id",id,"title","Expires", "sourceRow","private external metadata")));
        db.update("UPDATE platform_documents SET metadata_expires_at=? WHERE id=?",Instant.now().minus(Duration.ofDays(1)).toString(),id);
        restarted.purgeExpired();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM platform_documents",Integer.class));Map<String,Object> reference=restarted.document(owner,id,"import");assertEquals(true,reference.get("needsRefresh"));assertFalse(reference.containsKey("sourceRow"));assertFalse(json.writeValueAsString(reference).contains("private external metadata"));assertEquals(1,users.playlists(owner).size());
        restarted.start(owner,"spotify",auth);restarted.create(owner,UUID.randomUUID().toString(),"transfer","spotify",new LinkedHashMap<>(Map.of("title","Cascade")));
        db.update("DELETE FROM users WHERE id=?",owner);
        for(String table:List.of("platform_connections","platform_documents","platform_oauth_states"))assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> value,String key){return (List<Map<String,Object>>)value.get(key);}
    private static String query(URI uri,String key){for(String p:uri.getRawQuery().split("&")){String[] pair=p.split("=",2);if(pair[0].equals(key))return java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8);}return "";}

    private final class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final java.util.concurrent.ExecutorService worker=Executors.newCachedThreadPool();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicInteger creates=new AtomicInteger(),adds=new AtomicInteger(),videos=new AtomicInteger(),sourceItems=new AtomicInteger(2),refreshes=new AtomicInteger(),revokes=new AtomicInteger(),markerPages=new AtomicInteger(),searches=new AtomicInteger();
        final List<String> remoteItems=new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicBoolean stallVideo=new java.util.concurrent.atomic.AtomicBoolean(),holdExchange=new java.util.concurrent.atomic.AtomicBoolean(),holdCreate=new java.util.concurrent.atomic.AtomicBoolean(),unavailableSpotifyItems=new java.util.concurrent.atomic.AtomicBoolean(),emptySearch=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CountDownLatch videoStarted=new java.util.concurrent.CountDownLatch(1),releaseVideo=new java.util.concurrent.CountDownLatch(1),exchangeStarted=new java.util.concurrent.CountDownLatch(1),releaseExchange=new java.util.concurrent.CountDownLatch(1),createStarted=new java.util.concurrent.CountDownLatch(1),releaseCreate=new java.util.concurrent.CountDownLatch(1);
        final JdbcTemplate db;final Store users;final PlatformStore store;final PlatformClient remote;final PlatformApi api;
        final String owner,auth,otherAuth,testSchema;final JdbcTemplate schemaOwner;String challenge="";volatile String marker="";
        Fixture() throws Exception {this(null);}
        Fixture(Runnable beforeCommit) throws Exception {
            String configured=System.getenv("TAPTAP_TEST_DATABASE_URL");boolean postgres=configured!=null&&!configured.isBlank();
            String username=postgres?System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"):"sa",password=postgres?System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD",""):"";
            testSchema=postgres?"platform_"+UUID.randomUUID().toString().replace("-",""):null;
            schemaOwner=postgres?new JdbcTemplate(new DriverManagerDataSource(configured,username,password)):null;
            try {
            if(postgres)schemaOwner.execute("CREATE SCHEMA "+testSchema);
            String database=postgres?configured+(configured.contains("?")?"&":"?")+"currentSchema="+testSchema:"jdbc:h2:mem:platform-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
            var data=new DriverManagerDataSource(database,username,password);
            db=new JdbcTemplate(data);new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(data);
            var manager=new DataSourceTransactionManager(data){@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){if(beforeCommit!=null)beforeCommit.run();super.doCommit(status);}};
            users=new Store(db,manager,json,200,20);Map<String,String> first=users.register(),second=users.register();owner=first.get("userId");auth="Bearer "+first.get("token");otherAuth="Bearer "+second.get("token");
            store=new PlatformStore(db,manager,json,Base64.getEncoder().encodeToString(new byte[32]));
            server.setExecutor(worker);server.createContext("/",this::serve);server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
            Map<String,String> config=new LinkedHashMap<>(Map.of("SPOTIFY_CLIENT_ID","test-client","SPOTIFY_REDIRECT_URI",base+"/connections/spotify/callback","PUBLIC_BASE_URL",base,"YOUTUBE_API_KEY","test-key","APPLE_MUSIC_DEVELOPER_TOKEN","test-developer-token"));
            Map<String,String> endpoints=new LinkedHashMap<>();for(String key:List.of("spotify","spotify_auth","youtube","google_auth","google_authorize","apple_music"))endpoints.put(key,base+"/"+key);
            config.put("GOOGLE_CLIENT_ID","google-client");config.put("GOOGLE_CLIENT_SECRET","google-secret");config.put("GOOGLE_REDIRECT_URI",base+"/connections/youtube/callback");
            remote=new PlatformClient(json,config,endpoints);api=new PlatformApi(users,store,remote,json,manager);
            }catch(Exception|Error error){server.stop(0);worker.shutdownNow();if(schemaOwner!=null)try{schemaOwner.execute("DROP SCHEMA IF EXISTS "+testSchema+" CASCADE");}catch(RuntimeException cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        private Map<String,Object> spotifyTrack(String id){return Map.of("id",id,"type","track","name","Song","artists",List.of(Map.of("name","Artist")),"duration_ms",180000,"external_ids",Map.of("isrc","USABC1234567"),"album",Map.of("name","Album"));}
        private void serve(HttpExchange exchange) {
            try {
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                byte[] bytes=exchange.getRequestBody().readNBytes(100_001);assertTrue(bytes.length<=100_000);
                String body=new String(bytes,StandardCharsets.UTF_8);JsonNode value=body.isEmpty()||path.equals("/spotify_auth/api/token")||path.startsWith("/google_auth/")?json.createObjectNode():json.readTree(body);
                Object response;
                if(path.equals("/spotify_auth/api/token")){if(holdExchange.get()){exchangeStarted.countDown();assertTrue(releaseExchange.await(10,java.util.concurrent.TimeUnit.SECONDS));}String verifier=query(URI.create("http://fixture/?"+body),"code_verifier");assertEquals(challenge,Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.HexFormat.of().parseHex(PlatformStore.hash(verifier))));response=Map.of("access_token","test-access","refresh_token","test-refresh","expires_in",3600,"scope","playlist-read-private playlist-modify-private");}
                else if(path.equals("/google_auth/token")){String grant=query(URI.create("http://fixture/?"+body),"grant_type");if(grant.equals("refresh_token")){refreshes.incrementAndGet();assertEquals("google-refresh",query(URI.create("http://fixture/?"+body),"refresh_token"));}else{String verifier=query(URI.create("http://fixture/?"+body),"code_verifier");assertEquals(challenge,Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.HexFormat.of().parseHex(PlatformStore.hash(verifier))));}response=Map.of("access_token","google-access","refresh_token","google-refresh","expires_in",3600,"scope","youtube.force-ssl");}
                else if(path.equals("/google_auth/revoke")){assertEquals("google-refresh",query(URI.create("http://fixture/?"+body),"token"));revokes.incrementAndGet();response=Map.of();}
                else if(path.equals("/spotify/me"))response=Map.of("id","owner");
                else if(path.equals("/spotify/playlists/"+SOURCE))response=Map.of("name","Repeated songs","owner",Map.of("id","owner"),"collaborative",false);
                else if(path.equals("/spotify/playlists/"+SOURCE+"/items")){List<Object> items=new ArrayList<>();for(int i=0;i<Math.max(sourceItems.get(),unavailableSpotifyItems.get()?3:0);i++){if(i<sourceItems.get())items.add(Map.of("item",spotifyTrack(SONG)));if(unavailableSpotifyItems.get()&&i<3)items.add(i==0?Map.of("item",spotifyTrack(SONG),"is_local",true):i==1?Map.of("item",Map.of("id",SONG,"type","episode")):Map.of("item",Map.of()));}response=Map.of("items",items);}
                else if(path.equals("/spotify/search")){searches.incrementAndGet();response=Map.of("tracks",Map.of("items",emptySearch.get()?List.of():List.of(spotifyTrack(SONG))));}
                else if(path.equals("/spotify/me/playlists")&&method.equals("GET"))response=Map.of("items",marker.isEmpty()?List.of():List.of(Map.of("id",TARGET,"description",marker)));
                else if(path.equals("/spotify/me/playlists")&&method.equals("POST")){assertFalse(value.path("public").asBoolean(true));marker=value.path("description").asString();creates.incrementAndGet();if(holdCreate.get()){createStarted.countDown();assertTrue(releaseCreate.await(10,java.util.concurrent.TimeUnit.SECONDS));}lost(exchange);return;}
                else if(path.equals("/spotify/playlists/"+TARGET+"/items")&&method.equals("GET"))response=Map.of("items",remoteItems.stream().map(id->Map.of("item",spotifyTrack(id))).toList());
                else if(path.equals("/spotify/playlists/"+TARGET+"/items")&&method.equals("POST")){String uri=value.path("uris").get(0).asString();assertEquals("spotify:track:"+SONG,uri);remoteItems.add(SONG);if(adds.incrementAndGet()==1){lost(exchange);return;}response=Map.of("snapshot_id","snapshot");}
                else if(path.equals("/youtube/channels"))response=Map.of("items",List.of(Map.of("id","channel-owner")));
                else if(path.equals("/youtube/search"))response=Map.of("items",List.of(Map.of("id",Map.of("videoId",VIDEO),"snippet",Map.of("title","Song","channelTitle","Artist"))));
                else if(path.equals("/youtube/playlists")&&method.equals("POST")){assertEquals("private",value.path("status").path("privacyStatus").asString());marker=value.path("snippet").path("description").asString();creates.incrementAndGet();lost(exchange);return;}
                else if(path.equals("/youtube/playlists")){String query=exchange.getRequestURI().getRawQuery();if(query.contains("id=PLTEST"))response=Map.of("items",List.of(Map.of("id","PLTEST","snippet",Map.of("title","YouTube own","channelId","channel-owner"))));else if(marker.isEmpty())response=Map.of("items",List.of());else if(query.contains("pageToken=")){markerPages.incrementAndGet();response=Map.of("items",List.of(Map.of("id","PLTARGET","snippet",Map.of("description",marker))));}else response=Map.of("items",List.of(),"nextPageToken","page-two");}
                else if(path.equals("/youtube/playlistItems")&&method.equals("POST")){assertEquals("PLTARGET",value.path("snippet").path("playlistId").asString());assertEquals(VIDEO,value.path("snippet").path("resourceId").path("videoId").asString());remoteItems.add(VIDEO);if(adds.incrementAndGet()==1){lost(exchange);return;}response=Map.of("id","added");}
                else if(path.equals("/youtube/playlistItems")){int count=exchange.getRequestURI().getRawQuery().contains("playlistId=PLTEST")?2:remoteItems.size();List<Object> items=new ArrayList<>();for(int i=0;i<count;i++)items.add(Map.of("contentDetails",Map.of("videoId",VIDEO),"snippet",Map.of("title","Song","videoOwnerChannelTitle","Artist")));response=Map.of("items",items);}
                else if(path.equals("/youtube/videos")){videos.incrementAndGet();if(stallVideo.get()){exchange.sendResponseHeaders(200,64);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();videoStarted.countDown();releaseVideo.await(25,java.util.concurrent.TimeUnit.SECONDS);return;}response=Map.of("items",List.of(Map.of("id",VIDEO,"snippet",Map.of("title","Official video","description","0:00 Artist - Song one\n1:10 Artist - Song two"),"contentDetails",Map.of("duration","PT3M"))));}
                else if(path.equals("/apple_music/me/storefront")){assertEquals("test-developer-token",exchange.getRequestHeaders().getFirst("Authorization").substring(7));assertEquals("test-user-token",exchange.getRequestHeaders().getFirst("Music-User-Token"));response=Map.of("data",List.of(Map.of("id","us")));}
                else if(path.equals("/apple_music/catalog/us/search"))response=Map.of("results",Map.of("songs",Map.of("data",List.of(Map.of("id","123456","attributes",Map.of("name","Apple song","artistName","Artist","durationInMillis",180000))))));
                else if(path.equals("/apple_music/me/library/playlists")&&method.equals("POST")){marker=value.path("attributes").path("description").asString();creates.incrementAndGet();lost(exchange);return;}
                else if(path.equals("/apple_music/me/library/playlists")){List<Object> lists=new ArrayList<>();lists.add(Map.of("id","p.123","attributes",Map.of("name","My Apple list","playParams",Map.of("globalId","pl.u-123"))));if(!marker.isEmpty())lists.add(Map.of("id","p.created","attributes",Map.of("description",Map.of("standard",marker))));response=Map.of("data",lists);}
                else if(path.equals("/apple_music/me/library/playlists/p.created/tracks")&&method.equals("POST")){assertEquals("123456",value.path("data").get(0).path("id").asString());remoteItems.add("123456");if(adds.incrementAndGet()==1){lost(exchange);return;}response=Map.of();}
                else if(path.equals("/apple_music/me/library/playlists/p.created/tracks")){List<Object> songs=new ArrayList<>();for(String id:remoteItems)songs.add(Map.of("id","i.library-"+id,"attributes",Map.of("name","Apple song","artistName","Artist","playParams",Map.of("catalogId",id))));response=Map.of("data",songs);}
                else if(path.equals("/apple_music/me/library/playlists/p.123"))response=Map.of("data",List.of(Map.of("id","p.123","attributes",Map.of("name","My Apple list"))));
                else if(path.equals("/apple_music/me/library/playlists/p.123/tracks"))response=Map.of("data",List.of(Map.of("id","123456","attributes",Map.of("name","Apple song","artistName","Artist","durationInMillis",180000))));
                else throw new AssertionError("Unexpected mocked provider request: "+method+" "+path);
                byte[] result=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
            }catch(Throwable error){failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        }
        private void lost(HttpExchange exchange) throws Exception {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(201,64);exchange.close();}
        Map<String,Object> awaitJob(String id,String expected) throws Exception {
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();Map<String,Object> job;
            do{assertHealthy();job=api.transferStatus(auth,id);if(expected.equals(job.get("status")))return job;Thread.sleep(20);}while(System.nanoTime()<deadline);
            fail("Transfer did not reach "+expected+" within the fixture deadline; last state: "+job.get("status"));return job;
        }
        void assertHealthy(){assertNull(failure.get(),"Mock provider failure: "+failure.get());}
        @Override public void close(){try{releaseVideo.countDown();releaseExchange.countDown();releaseCreate.countDown();api.close();remote.close();server.stop(0);worker.shutdownNow();try{worker.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}finally{if(schemaOwner!=null)schemaOwner.execute("DROP SCHEMA IF EXISTS "+testSchema+" CASCADE");}}
    }
}
