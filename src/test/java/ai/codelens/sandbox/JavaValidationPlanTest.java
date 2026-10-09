package ai.codelens.sandbox;

import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.semantic.LocalPatchPreview;
import ai.codelens.semantic.LocalPatchPreviewTest;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaValidationPlanTest {
    static final String IMAGE="sha256:"+"d".repeat(64);
    static final String SOURCE=LocalPatchPreviewTest.SOURCE.replace("return value + 1;","return value;");
    static JavaValidationPlan.Source source(String path,String text){return new JavaValidationPlan.Source(path,text,CodeIntelligenceService.digest(text));}
    static JavaValidationPlan plan(String image,String existing,String regression) {
        return JavaValidationPlan.prepare(image,LocalPatchPreviewTest.context(SOURCE,2),LocalPatchPreviewTest.input(SOURCE,LocalPatchPreviewTest.input().files().get(0).edits()),
                List.of(source("src/main/java/example/Helper.java","package example; class Helper { static int increment(int value) { return value+1; } }")),
                source("tests/example/ExistingProbe.java",existing),"example.ExistingProbe",source("tests/example/RegressionProbe.java",regression),"example.RegressionProbe");
    }
    static JavaValidationPlan plan(){return plan(IMAGE,existing(),regression());}
    static String existing(){return "package example; public class ExistingProbe { public static void main(String[] args) { if(new Target().other(2)!=1) throw new AssertionError(); } }";}
    static String regression(){return "package example; public class RegressionProbe { public static void main(String[] args) { if(new Target().compute(2)!=3) throw new AssertionError(); } }";}

    @Test void bindsPreviewApprovalDiffAndAllPhaseSourceHashesWithoutPersistingCode() throws Exception {
        var plan=plan();var m=plan.manifest();
        assertEquals(LocalPatchPreviewTest.BASE,m.baseSha());assertEquals(LocalPatchPreviewTest.HEAD,m.headSha());assertEquals(1,m.decisionRevision());
        assertFalse(m.candidateEvidence().isEmpty());assertEquals(3,m.steps().size());
        assertEquals(m.steps().get(1).testMains(),m.steps().get(2).testMains());
        var old=m.steps().get(1).sources().stream().filter(s->s.path().equals(LocalPatchPreviewTest.PATH)).findFirst().orElseThrow();
        var changed=m.steps().get(2).sources().stream().filter(s->s.path().equals(LocalPatchPreviewTest.PATH)).findFirst().orElseThrow();
        assertNotEquals(old.hash(),changed.hash());assertEquals(CodeIntelligenceService.digest(SOURCE),old.hash());
        assertFalse(plan.canonical().contains("return value"));assertFalse(plan.toString().contains("compute"));
        assertEquals(CodeIntelligenceService.digest(plan.canonical()),plan.hash());
        assertEquals("head-model",m.headBuildModelHash());assertEquals(15,m.maxStageSeconds());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(source("A.java","class A {} ")).contains("class A"));
    }
    @Test void payloadPreservesUtf8AndHeadVsPatchedBytes() throws Exception {
        var plan=plan();
        for(var phase:JavaValidationPlan.Phase.values()) {
            var input=new DataInputStream(new ByteArrayInputStream(plan.payload(phase)));assertEquals(JavaSandboxHarness.MAGIC,input.readInt());
            int count=input.readInt();boolean found=false;
            for(int i=0;i<count;i++){String path=input.readUTF();String text=new String(input.readNBytes(input.readInt()),StandardCharsets.UTF_8);
                if(path.equals(LocalPatchPreviewTest.PATH)){found=true;assertEquals(phase==JavaValidationPlan.Phase.PATCHED_REGRESSION?SOURCE.replace("return value;","return Helper.increment(value);"):SOURCE,text);}}
            assertTrue(found);assertEquals(phase==JavaValidationPlan.Phase.BASELINE?1:2,input.readInt());
        }
    }
    @Test void refusesMutableImageTagsAndInvalidFrozenSources() {
        assertThrows(IllegalArgumentException.class,()->plan("eclipse-temurin:latest",existing(),regression()));
        assertThrows(IllegalArgumentException.class,()->new JavaValidationPlan.Source("src/a.java","class A {}","forged"));
        assertThrows(IllegalArgumentException.class,()->source("../A.java","class A {}"));
        assertThrows(IllegalArgumentException.class,()->source("src/A.java","class A { String password=\"synthetic\"; }"));
        assertThrows(IllegalArgumentException.class,()->source("src/A.java"," ".repeat(JavaSandboxHarness.MAX_FILE_BYTES+1)));
    }
    @Test void cannotPrepareAgainstForgedHeadOrUnapprovedEdits() {
        var input=LocalPatchPreviewTest.input();
        assertThrows(IllegalArgumentException.class,()->JavaValidationPlan.prepare(IMAGE,LocalPatchPreviewTest.context(SOURCE,2),input,List.of(),
                source("ExistingProbe.java",existing()),"example.ExistingProbe",source("RegressionProbe.java",regression()),"example.RegressionProbe"));
    }
    @Test void forbidsCaseCollisionsAndTestFileReplacingProductionSource() {
        var input=LocalPatchPreviewTest.input(SOURCE,LocalPatchPreviewTest.input().files().get(0).edits());
        var context=LocalPatchPreviewTest.context(SOURCE,2);
        assertThrows(IllegalArgumentException.class,()->JavaValidationPlan.prepare(IMAGE,context,input,List.of(source(LocalPatchPreviewTest.PATH.toUpperCase().replace(".JAVA",".java"),SOURCE)),
                source("ExistingProbe.java",existing()),"example.ExistingProbe",source("RegressionProbe.java",regression()),"example.RegressionProbe"));
        assertThrows(IllegalArgumentException.class,()->JavaValidationPlan.prepare(IMAGE,context,input,List.of(),source(LocalPatchPreviewTest.PATH,SOURCE),"example.Target",
                source("RegressionProbe.java",regression()),"example.RegressionProbe"));
    }
    @Test void protocolRejectsUnsafePathsAndArbitraryEntryPointArguments() {
        for(String path:List.of("/A.java","../A.java","src//A.java","C:/A.java",".git/A.java","a\\b.java"))assertFalse(JavaSandboxHarness.safePath(path));
        for(String main:List.of("-jar","example.Main --help","../Main","a.Main\n"))assertFalse(JavaSandboxHarness.safeMain(main));
        assertTrue(JavaSandboxHarness.safeMain("example.Main"));
    }
}
