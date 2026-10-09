package ai.codelens.store;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.semantic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS",matches="true")
class PatchPreviewIntegrationTest {
    @Test void usesActualProductionAdapterHashesAndSymbolsAndOnlyReadsAuditRows(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            String original=f.fingerprint(); var context=f.context();
            var preview=new LocalPatchPreview().generate(context,f.input());
            assertTrue(preview.unifiedDiff().contains("+    return Helper.compute(value);"));
            assertEquals(f.head.symbolsAtPath(LocalPatchPreviewTest.PATH).stream().filter(symbol->symbol.qualifiedName().equals("example.Target.compute")).findFirst().orElseThrow().stableKey(),preview.changedSymbols().get(0));
            assertEquals(context,f.context()); assertEquals(original,f.fingerprint()); assertFalse(preview.verifiedFix());
        }
    }
    @Test void installationOwnershipAndApprovedFileScopeCannotBeForged(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            assertThrows(EmptyResultDataAccessException.class,()->f.planning.previewContext(f.installation+1,f.id,f.decision.id(),1,List.of(LocalPatchPreviewTest.PATH)));
            assertThrows(IllegalArgumentException.class,()->f.planning.previewContext(f.installation,f.id,f.decision.id(),1,List.of("src/main/java/example/Helper.java")));
        }
    }
    @Test void oldDecisionCannotBeUsedAfterNewApprovalRevision(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            var d=f.submission; var newer=new ReuseDecisionService.Submission(1,d.investigationId(),d.baseSha(),d.headSha(),d.adapterVersion(),d.baseBuildModelHash(),d.headBuildModelHash(),d.goal(),d.decision(),d.selectedCandidateId(),d.candidateRejections(),d.justification(),d.changeBudget(),d.option());
            assertEquals(2,f.planning.save(f.installation,f.id,newer,"test-fixture").revision());
            assertThrows(ReuseDecisionStore.DecisionConflictException.class,f::context);
        }
    }
    @Test void newerReviewRunBlocksHistoricalApprovalEvenWhenItsSourceWasIndexed(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            f.reviews.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(f.repository,7,LocalPatchPreviewTest.BASE,"c".repeat(40),Models.PIPELINE_VERSION,"new-config","webhook","newer"),
                    new Models.ReviewJob("pending",f.installation,"patch-test","fixture",7,LocalPatchPreviewTest.BASE,"c".repeat(40)));
            assertThrows(EmptyResultDataAccessException.class,f::context);
        }
    }
    @Test void inactiveInstallationAndDeselectedRepositoryRefusePreview(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            f.jdbc.update("UPDATE github_installations SET active=false WHERE id=?",f.installation);
            assertThrows(EmptyResultDataAccessException.class,f::context);
            f.jdbc.update("UPDATE github_installations SET active=true WHERE id=?",f.installation);
            f.jdbc.update("UPDATE github_repositories SET selected=false WHERE id=?",f.repository);
            assertThrows(EmptyResultDataAccessException.class,f::context);
        }
    }
    @Test void indexProvenanceOrFileFailureCannotBeHiddenBySupplyingValidSource(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            f.jdbc.update("UPDATE repository_snapshots SET build_model_hash='changed' WHERE github_repository_id=? AND commit_sha=?",f.repository,LocalPatchPreviewTest.HEAD);
            assertThrows(ReuseDecisionStore.DecisionConflictException.class,f::context);
            f.jdbc.update("UPDATE repository_snapshots SET build_model_hash=? WHERE github_repository_id=? AND commit_sha=?",f.head.buildModelHash(),f.repository,LocalPatchPreviewTest.HEAD);
            f.jdbc.update("UPDATE semantic_index_files SET status='failed' WHERE snapshot_id=(SELECT head_snapshot_id FROM semantic_review_analyses WHERE review_run_id=?::uuid) AND path=?",f.id,LocalPatchPreviewTest.PATH);
            assertThrows(EmptyResultDataAccessException.class,f::context);
        }
    }
    @Test void snapshotRetentionClosesPreviewWithoutBackfill(@TempDir Path root) throws Exception {
        try(Fixture f=new Fixture(root)) {
            f.jdbc.update("DELETE FROM repository_snapshots WHERE github_repository_id=? AND commit_sha=?",f.repository,LocalPatchPreviewTest.HEAD);
            assertThrows(EmptyResultDataAccessException.class,f::context);
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM reuse_decisions WHERE review_run_id=?::uuid",Integer.class,f.id));
        }
    }
    static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper(); final HikariDataSource pool; final JdbcTemplate jdbc;
        final JdbcStore reviews; final ReuseDecisionStore planning; final long repository=UUID.randomUUID().getLeastSignificantBits()&Long.MAX_VALUE;
        final long installation=UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE;
        final String id; final SemanticModels.Index head; final ReuseDecisionService.Submission submission; final ReuseDecisionStore.StoredDecision decision;
        Fixture(Path root) throws Exception {
            Path file=root.resolve(LocalPatchPreviewTest.PATH);Files.createDirectories(file.getParent());Files.writeString(file,LocalPatchPreviewTest.SOURCE);
            Files.writeString(file.resolveSibling("Helper.java"),"package example; class Helper { static int compute(int value) { return value + 1; } }");
            Files.writeString(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>fixture</artifactId><version>1</version></project>");
            var model=new BuildModelDetector().detect(root); var adapter=new JavaSemanticAdapter();
            var base=adapter.index(root,LocalPatchPreviewTest.BASE,model);head=adapter.index(root,LocalPatchPreviewTest.HEAD,model);
            var runtime=RuntimeConfig.fromEnvironment();var settings=new HikariConfig();settings.setJdbcUrl(runtime.jdbcUrl());settings.setUsername(runtime.databaseUser());settings.setPassword(runtime.databasePassword());settings.setMaximumPoolSize(3);
            pool=new HikariDataSource(settings);jdbc=new JdbcTemplate(pool);reviews=new JdbcStore(jdbc,pool,json);planning=new ReuseDecisionStore(jdbc,pool,json);
            id=reviews.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository,7,LocalPatchPreviewTest.BASE,LocalPatchPreviewTest.HEAD,Models.PIPELINE_VERSION,"fixture","webhook","fixture"),
                    new Models.ReviewJob("pending",installation,"patch-test","fixture",7,LocalPatchPreviewTest.BASE,LocalPatchPreviewTest.HEAD)).run().id();
            var snapshots=new JdbcSemanticSnapshotStore(jdbc,pool,json);
            snapshots.save(new SemanticSnapshotStore.Key(Long.toString(repository),base.commitSha(),base.adapterVersion(),base.buildModelHash()),base);
            snapshots.save(new SemanticSnapshotStore.Key(Long.toString(repository),head.commitSha(),head.adapterVersion(),head.buildModelHash()),head);
            var investigation=new SemanticReusePlanner().investigate(base,head,Set.of(LocalPatchPreviewTest.PATH));
            new JdbcSemanticReviewAuditStore(jdbc,pool,json).save(id,repository,base,head,new Models.ImpactSummary("low",0,1,0,List.of(),"fixture"),new Models.Coverage(2,2,false,"semantic","S1",List.of("No tests executed")),investigation);
            var candidate=investigation.candidates().stream().filter(value->value.qualifiedName().equals("example.Helper.compute")).findFirst().orElseThrow();var p=investigation.provenance();
            submission=new ReuseDecisionService.Submission(0,investigation.id(),p.baseSha(),p.headSha(),p.adapterVersion(),p.baseBuildModelHash(),p.headBuildModelHash(),"Use the existing helper for this bounded change","reuse",candidate.id(),java.util.Map.of(),
                    "The existing candidate shares the required method contract in this fixture.",new ReuseDecisionService.ChangeBudget(1,1,false),
                    new ReuseDecisionService.OptionSubmission("reuse","Reuse the helper","Replace local arithmetic with the frozen helper candidate.",candidate.id(),List.of(LocalPatchPreviewTest.PATH),List.of("example.Target.compute"),List.of("Compile and execute targeted tests in a future approved sandbox."),List.of("Behavior remains unverified until tests are executed.")));
            decision=planning.save(installation,id,submission,"test-fixture"); reviews.updateReviewRun(id,"completed",null,"","");
        }
        LocalPatchPreview.Context context(){return planning.previewContext(installation,id,decision.id(),1,List.of(LocalPatchPreviewTest.PATH));}
        LocalPatchPreview.Submission input(){return new LocalPatchPreview.Submission(decision.id(),1,LocalPatchPreviewTest.BASE,LocalPatchPreviewTest.HEAD,
                List.of(new LocalPatchPreview.FileInput(LocalPatchPreviewTest.PATH,LocalPatchPreviewTest.SOURCE,List.of(new LocalPatchPreview.Edit(4,4,List.of("    return Helper.compute(value);"))))));}
        String fingerprint(){return jdbc.queryForObject("SELECT md5(row_to_json(a)::text) FROM semantic_review_analyses a WHERE review_run_id=?::uuid",String.class,id)+
                jdbc.queryForObject("SELECT md5(row_to_json(d)::text) FROM reuse_decisions d WHERE id=?::uuid",String.class,decision.id());}
        public void close(){try{jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?",repository);jdbc.update("DELETE FROM repository_snapshots WHERE github_repository_id=?",repository);
            jdbc.update("DELETE FROM github_repositories WHERE id=?",repository);jdbc.update("DELETE FROM github_installations WHERE id=?",installation);}finally{pool.close();}}
    }
}
