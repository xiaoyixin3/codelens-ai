package ai.codelens.llm;

import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelContextPlanTest {
    static Models.ChangedFile file(String path, String patch) { return new Models.ChangedFile(path,"modified",1,0,patch,null); }
    static Models.RepositoryPolicy policy() { return new Models.RepositoryPolicy("policy","head","zh",false,8,Map.of(),List.of(),List.of(),List.of(),"reuse",List.of()); }
    static Models.PullRequest pull(List<Models.ChangedFile> files) { return new Models.PullRequest(1,"中文标题","说明","base","head",files); }

    @Test void listsEveryFileLimitAndMissingPatchWithoutClaimingFullCoverage() {
        var selected=ModelContextPlan.select(List.of(file("a.java",""),file("b.java","+ok")),1,100);
        assertEquals(1,selected.files().size()); assertTrue(selected.incomplete());
        assertEquals(List.of("patch_unavailable","file_limit"),selected.omissions().stream().map(ModelContextPlan.Omission::reason).toList());
    }
    @Test void cutsAtLineBoundaryAndNeverTreatsPartialAddedLineAsEvidence() {
        var selected=ModelContextPlan.select(List.of(file("a.java","@@ -1 +1 @@\n+完整的一行很长\n")),2,15);
        assertEquals("@@ -1 +1 @@\n",selected.files().get(0).patch());
        assertEquals("patch_truncated",selected.omissions().get(0).reason());
    }
    @Test void redactsBeforeTruncationAndKeepsNestedJsonValid() throws Exception {
        var files=List.of(file("a.java","@@ -1 +1 @@\n+password=verylongcredential\n"));
        var selection=ModelContextPlan.select(files,1,34);
        var json=new ObjectMapper();
        var request=ModelContextPlan.request(json,"fixture","summary","trusted",Map.of("files",selection.files(),
                "description","api_key=anothercredential\n\"quoted\""),selection,pull(files),policy());
        String payload=new String(request.payload(),StandardCharsets.UTF_8);
        assertFalse(payload.contains("verylongcredential")); assertFalse(payload.contains("anothercredential"));
        var user=json.readTree(json.readTree(payload).path("messages").get(1).path("content").asText());
        assertTrue(user.path("description").asText().contains("[REDACTED]"));
        assertEquals("head",user.path("headSha").asText());
    }
    @Test void accountsForFullSerializedUtf8EnvelopeAndProducesStableFingerprint() {
        var files=List.of(file("a.java","+中文\n")); var selection=ModelContextPlan.select(files,2,100);
        var first=ModelContextPlan.request(new ObjectMapper(),"model","summary","instructions",Map.of("files",selection.files()),selection,pull(files),policy());
        var second=ModelContextPlan.request(new ObjectMapper(),"model","summary","instructions",Map.of("files",selection.files()),selection,pull(files),policy());
        assertEquals(first.payload().length,first.metadata().requestBytes());
        assertTrue(first.payload().length>new String(first.payload(),StandardCharsets.UTF_8).length());
        assertEquals(first.metadata().requestHash(),second.metadata().requestHash());
        assertEquals(1,first.metadata().blockCount()); assertEquals("ModelRequest[body=redacted]",first.toString());
        byte[] bytes=first.payload(); bytes[0]=0; assertNotEquals(0,first.payload()[0]);
    }
    @Test void budgetOmissionsIncludeAllLaterFilesAndMetadataDoesNotContainSource() throws Exception {
        var files=List.of(file("a.java","+x\n"),file("b.java","+secret source"),file("c.java","+other"));
        var selection=ModelContextPlan.select(files,3,3);
        assertEquals(List.of("b.java","c.java"),selection.omissions().stream().map(ModelContextPlan.Omission::path).toList());
        var json=new ObjectMapper();
        var request=ModelContextPlan.request(json,"model","summary","instructions",Map.of("files",selection.files()),selection,pull(files),policy());
        assertFalse(json.writeValueAsString(request.metadata()).contains("secret source"));
        assertEquals(3,request.metadata().totalFiles());
        assertEquals("a.java",request.metadata().files().get(0).path());
        assertEquals(64,request.metadata().files().get(0).selectedPatchHash().length());
    }
    @Test void fieldInsertionOrderCannotChangeExactRequestFingerprint() {
        var files=List.of(file("a.java","+ok\n")); var selection=ModelContextPlan.select(files,2,100);
        var left=new java.util.LinkedHashMap<String,Object>(); left.put("files",selection.files()); left.put("title","中文");
        var right=new java.util.LinkedHashMap<String,Object>(); right.put("title","中文"); right.put("files",selection.files());
        var json=new ObjectMapper();
        assertArrayEquals(ModelContextPlan.request(json,"model","summary","system",left,selection,pull(files),policy()).payload(),
                ModelContextPlan.request(json,"model","summary","system",right,selection,pull(files),policy()).payload());
    }
}
