package ai.codelens.semantic;

import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.store.ReuseDecisionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class LocalPatchPreviewTest {
    public static final String PATH="src/main/java/example/Target.java",BASE="a".repeat(40),HEAD="b".repeat(40);
    public static final String SOURCE="package example;\nclass Target {\n  int compute(int value) {\n    return value + 1;\n  }\n  int other(int value) {\n    return value - 1;\n  }\n}\n";
    public static LocalPatchPreview.Context context(String source,int budget) {
        int lines=source.split("\n",-1).length;
        var symbols=List.of(new SemanticModels.Symbol("java:method:example.Target#compute(int)",SemanticModels.SymbolKind.METHOD,"example.Target.compute","int compute(int)",PATH,3,source.contains("int other")?5:lines-3,false,true),
                new SemanticModels.Symbol("java:method:example.Target#other(int)",SemanticModels.SymbolKind.METHOD,"example.Target.other","int other(int)",PATH,6,8,false,true));
        String candidate="candidate:helper";
        var evidence=new SemanticReusePlanner.Evidence("symbol:head:helper","symbol",HEAD,"src/main/java/example/Helper.java",3,"java:method:example.Helper#increment(int)","","","",true);
        var investigation=new SemanticReusePlanner.Investigation("reuse:"+HEAD,new SemanticReusePlanner.Provenance(BASE,HEAD,JavaSemanticAdapter.ADAPTER_VERSION,"base-model","head-model"),
                new SemanticReusePlanner.SearchScope(List.of(PATH),12,20,true,List.of()),
                List.of(new SemanticReusePlanner.SymbolRef(symbols.get(0).stableKey(),symbols.get(0).qualifiedName(),PATH,3,"method",true)),
                List.of(new SemanticReusePlanner.Candidate(candidate,evidence.stableKey(),"example.Helper.increment",evidence.path(),"method","same_role","extendable",.84,List.of(evidence),"Shared resolved caller")),
                new SemanticReusePlanner.PatchGate(false,List.of("approval required")));
        var p=investigation.provenance();
        var submission=new ReuseDecisionService.Submission(0,investigation.id(),BASE,HEAD,p.adapterVersion(),p.baseBuildModelHash(),p.headBuildModelHash(),"Use the existing helper instead of duplicate logic","reuse",candidate,Map.of(),
                "The frozen candidate has the existing helper contract required by this change.",new ReuseDecisionService.ChangeBudget(2,budget,false),
                new ReuseDecisionService.OptionSubmission("reuse","Use existing helper","Replace the local arithmetic with the approved in-repository helper.",candidate,List.of(PATH),
                        symbols.stream().map(SemanticModels.Symbol::stableKey).toList(),List.of("Compile and run Target tests in an approved sandbox."),List.of("Existing helper behavior requires subsequent verification.")));
        var decision=new ReuseDecisionService().validate(submission,investigation);
        var stored=new ReuseDecisionStore.StoredDecision("11111111-1111-4111-8111-111111111111",1,decision,"test-fixture",Instant.parse("2026-10-05T00:00:00Z"));
        return new LocalPatchPreview.Context("22222222-2222-4222-8222-222222222222",BASE,HEAD,new ReuseDecisionStore.PlanningView(investigation,1,stored),
                List.of(new LocalPatchPreview.FileContext(PATH,CodeIntelligenceService.digest(source),symbols)));
    }
    public static LocalPatchPreview.Submission input(String source,List<LocalPatchPreview.Edit> edits) {
        return new LocalPatchPreview.Submission("11111111-1111-4111-8111-111111111111",1,BASE,HEAD,List.of(new LocalPatchPreview.FileInput(PATH,source,edits)));
    }
    public static LocalPatchPreview.Submission input(){return input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("    return Helper.increment(value);"))));}
    private final LocalPatchPreview generator=new LocalPatchPreview();

    @Test void producesEvidenceBoundConcreteDiffWithoutClaimingValidationOrPublication() {
        var result=generator.generate(context(SOURCE,2),input());
        assertTrue(result.unifiedDiff().contains("-    return value + 1;\n+    return Helper.increment(value);"));
        assertEquals(List.of("java:method:example.Target#compute(int)"),result.changedSymbols());
        assertEquals(CodeIntelligenceService.digest(SOURCE.replace("return value + 1;","return Helper.increment(value);")),result.files().get(0).afterHash());
        assertEquals("candidate:helper",result.selectedCandidateId()); assertFalse(result.evidenceIds().isEmpty());
        assertTrue(result.previewAllowed()); assertFalse(result.verifiedFix()); assertFalse(result.publicationAllowed()); assertFalse(result.automaticApplyAllowed()); assertFalse(result.publicBehavioralContractVerified());
        assertFalse(result.toString().contains("Helper.increment"));
    }
    @Test void rejectsAbsentOrSupersededApprovalAndWrongRevision() {
        var valid=context(SOURCE,2);
        var missing=new LocalPatchPreview.Context(valid.runId(),BASE,HEAD,new ReuseDecisionStore.PlanningView(valid.planning().investigation(),0,null),valid.files());
        assertThrows(IllegalArgumentException.class,()->generator.generate(missing,input()));
        var old=new LocalPatchPreview.Submission(input().decisionId(),2,BASE,HEAD,input().files());
        assertThrows(IllegalArgumentException.class,()->generator.generate(valid,old));
        var head=new LocalPatchPreview.Submission(input().decisionId(),1,BASE,"c".repeat(40),input().files());
        assertThrows(IllegalArgumentException.class,()->generator.generate(valid,head));
    }
    @Test void rejectsForgedSourceAndOutOfScopeDuplicateFiles() {
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input(SOURCE+"//not indexed",input().files().get(0).edits())));
        var duplicate=new LocalPatchPreview.Submission(input().decisionId(),1,BASE,HEAD,List.of(input().files().get(0),input().files().get(0)));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),duplicate));
        var other=new LocalPatchPreview.Submission(input().decisionId(),1,BASE,HEAD,List.of(new LocalPatchPreview.FileInput("src/Other.java",SOURCE,input().files().get(0).edits())));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),other));
    }
    @Test void actualAstSymbolCountCannotBeUnderdeclared() {
        var two=input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("    return value + 2;")),new LocalPatchPreview.Edit(7,7,List.of("    return value - 2;"))));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,1),two));
        assertEquals(2,generator.generate(context(SOURCE,2),two).changedSymbols().size());
    }
    @Test void rejectsHeaderEditsAndInjectedNewDeclarationsEvenWhenSubmittedRangeIsInsideBody() {
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input(SOURCE,List.of(new LocalPatchPreview.Edit(3,3,List.of("  public int compute(int value) {"))))));
        var injected=input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("    return value;","  }","  int extra() { return 1; }","  int forged(int value) {","    return value;"))));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),injected));
    }
    @Test void rejectsUnsafePathsOverlapInvalidJavaAndNoopChanges() {
        for(String path:List.of("../Target.java","C:/Target.java","src/../Target.java","src/Target.java:stream","src\\Target.java","src/a\n.java",".git/Target.java")) assertThrows(IllegalArgumentException.class,()->LocalPatchPreview.safePath(path));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("return value;")),new LocalPatchPreview.Edit(4,4,List.of("return value;"))))));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("return )"))))));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("    return value + 1;"))))));
    }
    @Test void refusesSensitiveSourceOrReplacementInsteadOfSilentlyChangingPatchBytes() {
        var sensitive=SOURCE.replace("return value + 1;","String password = \"syntheticCredential\"; return value;");
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(sensitive,2),input(sensitive,input().files().get(0).edits())));
        var input=input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("    return \"password=syntheticCredential\".length();"))));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input));
    }
    @Test void refusesBroadBodyRewriteWithUnchangedMethodHeader() {
        var source="package example;\nclass Target {\n  int compute(int value) {\n"+"    value++;\n".repeat(20)+"    return value;\n  }\n}\n";
        var input=input(source,List.of(new LocalPatchPreview.Edit(4,24,List.of("    return value;"))));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(source,2),input));
    }
    @Test void rejectsUnicodeEscapeAndMalformedUtf16Sources() {
        var escaped=SOURCE.replace("return value + 1;","return value; // "+"\\"+"u000a");
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(escaped,2),input(escaped,input().files().get(0).edits())));
        var invalid=SOURCE.replace("return value + 1;","return \""+'\ud800'+"\".length();");
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(invalid,2),input(invalid,input().files().get(0).edits())));
    }
    @Test void refusesSourceAndReplacementLineLimits() {
        var huge=SOURCE+" ".repeat(LocalPatchPreview.MAX_FILE_BYTES);
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(huge,2),input(huge,input().files().get(0).edits())));
        assertThrows(IllegalArgumentException.class,()->generator.generate(context(SOURCE,2),input(SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("return value;\nreturn value;"))))));
    }
    @Test void generatedDiffIsApplicableForLfCrlfAndMissingFinalNewline(@TempDir Path temp) throws Exception {
        int i=0;
        for(String source:List.of(SOURCE,SOURCE.replace("\n","\r\n"),SOURCE.substring(0,SOURCE.length()-1),SOURCE.replace("\n","\r\n").stripTrailing())) {
            var preview=generator.generate(context(source,2),input(source,input().files().get(0).edits()));
            Path directory=Files.createDirectory(temp.resolve("fixture-"+i++)); Path file=directory.resolve(PATH);
            Files.createDirectories(file.getParent()); Files.writeString(file,source,StandardCharsets.UTF_8);
            var process=new ProcessBuilder("git","apply","--check","-").directory(directory.toFile()).redirectErrorStream(true).start();
            process.getOutputStream().write(preview.unifiedDiff().getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            if(!process.waitFor(5,TimeUnit.SECONDS)) {process.destroyForcibly();fail("Synthetic patch check timed out");}
            assertEquals(0,process.exitValue(),"Synthetic patch check failed");
            assertEquals(source,Files.readString(file)); // --check never applies or executes the code.
        }
    }
}
