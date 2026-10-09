package ai.codelens.sandbox;

import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.security.Redactor;
import ai.codelens.semantic.LocalPatchPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Internal standard-library-only probe, not a production execution approval or a verified fix. */
public final class JavaValidationPlan {
    public enum Phase { BASELINE, HEAD_REGRESSION, PATCHED_REGRESSION }
    public record Source(String path,@com.fasterxml.jackson.annotation.JsonIgnore String text,String hash) {
        public Source {
            require(JavaSandboxHarness.safePath(path) && text!=null && !text.isEmpty(),"invalid_frozen_source");
            require(text.getBytes(StandardCharsets.UTF_8).length<=JavaSandboxHarness.MAX_FILE_BYTES
                    && StandardCharsets.UTF_8.newEncoder().canEncode(text) && text.indexOf(0)<0,"source_limit_or_encoding");
            require(CodeIntelligenceService.digest(text).equals(hash),"frozen_source_hash_mismatch");
            require(Redactor.redact(text).equals(text),"sensitive_source_refused");
        }
        @Override public String toString(){return "SandboxSource[code=redacted]";}
    }
    public record SourceHash(String path,String hash) {}
    public record Step(Phase phase,List<SourceHash> sources,List<String> testMains) {}
    public record Manifest(String id,String policy,String imageId,int maxStageSeconds,String reviewRunId,String decisionId,int decisionRevision,
                           String baseSha,String headSha,String adapterVersion,String baseBuildModelHash,String headBuildModelHash,
                           String diffHash,List<String> candidateEvidence,List<Step> steps) {}
    private record Bundle(List<Source> sources,List<String> mains) {}
    private final Manifest manifest;
    private final List<Bundle> bundles;
    private final String canonical;
    private final String hash;
    private JavaValidationPlan(Manifest manifest,List<Bundle> bundles) {
        this.manifest=manifest;this.bundles=List.copyOf(bundles);
        try{canonical=new ObjectMapper().writeValueAsString(manifest);hash=CodeIntelligenceService.digest(canonical);}
        catch(Exception invalid){throw new IllegalArgumentException("invalid_validation_manifest");}
    }
    public static JavaValidationPlan prepare(String imageId,LocalPatchPreview.Context context,LocalPatchPreview.Submission input,
                                              List<Source> frozenSupport,Source existingTest,String existingMain,
                                              Source regressionTest,String regressionMain) {
        require(imageId!=null && imageId.matches("sha256:[0-9a-f]{64}"),"immutable_image_required");
        require(frozenSupport!=null && frozenSupport.size()<=19 && existingTest!=null && regressionTest!=null,"bounded_tests_required");
        require(JavaSandboxHarness.safeMain(existingMain) && JavaSandboxHarness.safeMain(regressionMain)
                && !existingMain.equals(regressionMain),"distinct_fixed_test_entrypoints_required");
        require(existingTest.path().endsWith(existingMain.substring(existingMain.lastIndexOf('.')+1)+".java")
                && regressionTest.path().endsWith(regressionMain.substring(regressionMain.lastIndexOf('.')+1)+".java"),"test_entrypoint_source_required");
        var prepared=new LocalPatchPreview().prepare(context,input); var preview=prepared.preview();
        List<Source> before=new ArrayList<>(frozenSupport),after=new ArrayList<>(frozenSupport);
        for(var file:prepared.files()) {
            before.add(new Source(file.path(),file.before(),CodeIntelligenceService.digest(file.before())));
            after.add(new Source(file.path(),file.after(),CodeIntelligenceService.digest(file.after())));
        }
        var baseline=bundle(before,List.of(existingTest),List.of(existingMain));
        var head=bundle(before,List.of(existingTest,regressionTest),List.of(existingMain,regressionMain));
        var patched=bundle(after,List.of(existingTest,regressionTest),List.of(existingMain,regressionMain));
        List<Bundle> bundles=List.of(baseline,head,patched); List<Step> steps=new ArrayList<>();
        for(var phase:Phase.values()){var b=bundles.get(phase.ordinal());steps.add(new Step(phase,b.sources().stream().map(source->new SourceHash(source.path(),source.hash())).toList(),b.mains()));}
        var provenance=context.planning().currentDecision().decision().provenance();
        return new JavaValidationPlan(new Manifest(UUID.randomUUID().toString(),JavaSandboxHarness.POLICY,imageId,15,
                preview.reviewRunId(),preview.decisionId(),preview.decisionRevision(),preview.baseSha(),preview.headSha(),
                provenance.adapterVersion(),provenance.baseBuildModelHash(),provenance.headBuildModelHash(),preview.diffHash(),
                List.copyOf(preview.evidenceIds()),List.copyOf(steps)),bundles);
    }
    private static Bundle bundle(List<Source> production,List<Source> tests,List<String> mains) {
        var files=new ArrayList<>(production);files.addAll(tests);
        require(files.size()<=JavaSandboxHarness.MAX_FILES,"source_count_limit");
        var names=new HashSet<String>();int bytes=0;
        for(var source:files){require(names.add(source.path().toLowerCase(java.util.Locale.ROOT)),"duplicate_source_path");bytes+=source.text().getBytes(StandardCharsets.UTF_8).length;}
        require(bytes<=JavaSandboxHarness.MAX_BYTES,"source_bundle_limit");files.sort(Comparator.comparing(Source::path));
        return new Bundle(List.copyOf(files),List.copyOf(mains));
    }
    public byte[] payload(Phase phase) {
        var bundle=bundles.get(phase.ordinal());
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(JavaSandboxHarness.MAGIC);out.writeInt(bundle.sources().size());
            for(var source:bundle.sources()){out.writeUTF(source.path());byte[] code=source.text().getBytes(StandardCharsets.UTF_8);out.writeInt(code.length);out.write(code);}
            out.writeInt(bundle.mains().size());for(String main:bundle.mains())out.writeUTF(main);out.close();return bytes.toByteArray();
        } catch(Exception invalid){throw new IllegalArgumentException("invalid_validation_payload");}
    }
    public Manifest manifest(){return manifest;}
    public String canonical(){return canonical;}
    public String hash(){return hash;}
    @Override public String toString(){return "JavaValidationPlan[code=redacted,verifiedFix=false]";}
    private static void require(boolean condition,String code){if(!condition)throw new IllegalArgumentException(code);}
}
