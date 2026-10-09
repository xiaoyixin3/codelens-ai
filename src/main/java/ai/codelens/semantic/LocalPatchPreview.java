package ai.codelens.semantic;

import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.security.Redactor;
import ai.codelens.store.ReuseDecisionStore;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ephemeral, read-only Java method-body preview. Never a verified fix or publication permission. */
public final class LocalPatchPreview {
    public static final int MAX_REQUEST_BYTES=1024*1024, MAX_FILE_BYTES=256*1024, MAX_DIFF_BYTES=64*1024;
    public record Edit(int startLine, int endLine, List<String> replacement) {
        public Edit { replacement = replacement == null ? List.of() : List.copyOf(replacement); }
    }
    public record FileInput(String path, String source, List<Edit> edits) {
        public FileInput { edits = edits == null ? List.of() : List.copyOf(edits); }
        @Override public String toString() { return "FileInput[source=redacted,edits=redacted]"; }
    }
    public record Submission(String decisionId, int decisionRevision, String baseSha, String headSha,
                             List<FileInput> files) {
        public Submission { files = files == null ? List.of() : List.copyOf(files); }
        @Override public String toString() { return "PatchSubmission[code=redacted]"; }
    }
    public record FileContext(String path, String contentHash, List<SemanticModels.Symbol> symbols) {
        public FileContext { symbols=List.copyOf(symbols); }
    }
    public record Context(String runId, String baseSha, String headSha, ReuseDecisionStore.PlanningView planning,
                          List<FileContext> files) {
        public Context { files=List.copyOf(files); }
    }
    public record FileChange(String path, String beforeHash, String afterHash, int removedLines, int addedLines) {}
    public record Preview(String reviewRunId, String decisionId, int decisionRevision, String baseSha, String headSha,
                          String strategy, String selectedCandidateId, List<String> evidenceIds,
                          List<FileChange> files, List<String> changedSymbols, String unifiedDiff, String diffHash,
                          List<String> verification, List<String> limitations, boolean previewAllowed,
                          boolean publicationAllowed, boolean automaticApplyAllowed, boolean verifiedFix,
                          boolean publicBehavioralContractVerified) {
        @Override public String toString() { return "LocalPatchPreview[diff=redacted,verifiedFix=false]"; }
    }
    /** Internal ephemeral material only; never serialize this as an API response or persist its code. */
    public record PreparedFile(String path, @com.fasterxml.jackson.annotation.JsonIgnore String before,
                               @com.fasterxml.jackson.annotation.JsonIgnore String after) {
        @Override public String toString() { return "PreparedFile[code=redacted]"; }
    }
    public record PreparedPreview(Preview preview, List<PreparedFile> files) {
        public PreparedPreview { files=List.copyOf(files); }
        @Override public String toString() { return "PreparedPreview[code=redacted]"; }
    }

    public Preview generate(Context context, Submission input) {
        return prepare(context,input).preview();
    }

    public PreparedPreview prepare(Context context, Submission input) {
        require(context!=null && input!=null,"preview_context_required");
        var stored=context.planning().currentDecision();
        require(stored!=null && stored.revision()==input.decisionRevision() && stored.id().equals(input.decisionId()),"approved_decision_required");
        require(context.baseSha().equals(input.baseSha()) && context.headSha().equals(input.headSha()),"preview_revision_mismatch");
        var decision=validated(context.planning()); var option=decision.selectedOption();
        require(input.files().size()>0 && input.files().size()<=Math.min(3,decision.changeBudget().maxFiles()),"preview_file_budget_exceeded");
        var provenance=decision.provenance();
        require(provenance.baseSha().equals(context.baseSha()) && provenance.headSha().equals(context.headSha())
                && provenance.adapterVersion().equals(JavaSemanticAdapter.ADAPTER_VERSION),"preview_provenance_mismatch");
        Map<String,FileContext> indexed=context.files().stream().collect(java.util.stream.Collectors.toMap(FileContext::path,value->value));
        Set<String> paths=new LinkedHashSet<>(),changed=new LinkedHashSet<>();
        List<FileChange> changes=new ArrayList<>(); List<PreparedFile> prepared=new ArrayList<>();
        StringBuilder diff=new StringBuilder(); int churn=0;
        for(var file:input.files()) {
            safePath(file.path());
            require(paths.add(file.path()) && option.expectedFiles().contains(file.path()),"preview_file_outside_approved_option");
            var frozen=indexed.get(file.path()); require(frozen!=null,"preview_indexed_source_required");
            String source=file.source(); require(source!=null && source.getBytes(StandardCharsets.UTF_8).length<=MAX_FILE_BYTES,"preview_source_limit");
            require(StandardCharsets.UTF_8.newEncoder().canEncode(source) && CodeIntelligenceService.digest(source).equals(frozen.contentHash()),"preview_source_hash_mismatch");
            require(Redactor.redact(source).equals(source),"preview_sensitive_source_refused");
            boolean crlf=source.contains("\r\n");
            require(!source.replace("\r\n","").contains("\r") && (!crlf || !source.replace("\r\n","").contains("\n")),"preview_mixed_line_endings");
            String eol=crlf?"\r\n":"\n"; boolean terminal=source.endsWith(eol);
            List<String> before=new ArrayList<>(List.of(source.split("\\r?\\n",-1)));
            if(terminal) before.remove(before.size()-1);
            require(before.size()<=5000 && !file.edits().isEmpty() && file.edits().size()<=10,"preview_edit_limit");
            var edits=file.edits().stream().sorted(Comparator.comparingInt(Edit::startLine)).toList();
            CompilationUnit original=parse(source); var originalCalls=original.findAll(CallableDeclaration.class);
            int previous=0,removed=0,added=0;
            for(var edit:edits) {
                require(edit.startLine()>previous && edit.endLine()>=edit.startLine() && edit.endLine()<=before.size(),"preview_overlapping_or_invalid_range");
                require(edit.replacement().size()<=60 && edit.replacement().stream().allMatch(line->line.length()<=4000 && !line.contains("\n") && !line.contains("\r")),"preview_replacement_limit");
                require(originalCalls.stream().anyMatch(call->{var body=body(call); return body!=null
                        && edit.startLine()>body.getBegin().orElseThrow().line && edit.endLine()<body.getEnd().orElseThrow().line;}),"preview_method_body_only");
                previous=edit.endLine(); removed+=edit.endLine()-edit.startLine()+1; added+=edit.replacement().size();
            }
            churn+=removed+added; require(churn<=120,"preview_line_budget_exceeded");
            List<String> after=new ArrayList<>(before);
            for(int i=edits.size()-1;i>=0;i--) {var edit=edits.get(i); after.subList(edit.startLine()-1,edit.endLine()).clear(); after.addAll(edit.startLine()-1,edit.replacement());}
            String replacement=String.join(eol,after)+(terminal?eol:"");
            require(StandardCharsets.UTF_8.newEncoder().canEncode(replacement) && !replacement.equals(source) && replacement.getBytes(StandardCharsets.UTF_8).length<=MAX_FILE_BYTES,"preview_noop_or_source_limit");
            require(Redactor.redact(replacement).equals(replacement),"preview_sensitive_replacement_refused");
            CompilationUnit modified=parse(replacement);
            require(structure(original).equals(structure(modified)),"preview_declaration_or_field_change_refused");
            var modifiedCalls=modified.findAll(CallableDeclaration.class);
            require(originalCalls.size()==modifiedCalls.size(),"preview_declaration_or_field_change_refused");
            int fileChanged=0;
            for(int i=0;i<originalCalls.size();i++) {
                var call=(CallableDeclaration<?>)originalCalls.get(i); var left=body(call); var right=body(modifiedCalls.get(i));
                if(left==null || right==null || left.toString().equals(right.toString())) continue;
                var matches=frozen.symbols().stream().filter(symbol->symbol.startLine()==call.getBegin().orElseThrow().line
                        && symbol.endLine()==call.getEnd().orElseThrow().line
                        && (symbol.kind()==SemanticModels.SymbolKind.METHOD || symbol.kind()==SemanticModels.SymbolKind.CONSTRUCTOR)).toList();
                require(matches.size()==1,"preview_exact_symbol_anchor_required");
                var symbol=matches.get(0);
                require(option.expectedSymbols().contains(symbol.stableKey()) || option.expectedSymbols().contains(symbol.qualifiedName()),"preview_symbol_outside_approved_option");
                int interior=Math.max(0,left.getEnd().orElseThrow().line-left.getBegin().orElseThrow().line-1);
                int affected=edits.stream().filter(edit->edit.startLine()>left.getBegin().orElseThrow().line && edit.endLine()<left.getEnd().orElseThrow().line)
                        .mapToInt(edit->edit.endLine()-edit.startLine()+1).sum();
                require(interior<=8 || affected/(double)interior<=.35,"preview_broad_method_rewrite_refused");
                changed.add(symbol.stableKey()); fileChanged++;
            }
            require(fileChanged>0,"preview_no_semantic_body_change");
            require(changed.size()<=decision.changeBudget().maxChangedSymbols(),"preview_symbol_budget_exceeded");
            diff.append(unified(file.path(),before,edits,eol,terminal));
            require(diff.toString().getBytes(StandardCharsets.UTF_8).length<=MAX_DIFF_BYTES,"preview_diff_limit");
            changes.add(new FileChange(file.path(),frozen.contentHash(),CodeIntelligenceService.digest(replacement),removed,added));
            prepared.add(new PreparedFile(file.path(),source,replacement));
        }
        var preview=new Preview(context.runId(),stored.id(),stored.revision(),context.baseSha(),context.headSha(),decision.decision(),
                decision.selectedCandidateId(),option.evidenceIds(),List.copyOf(changes),List.copyOf(changed),diff.toString(),
                CodeIntelligenceService.digest(diff.toString()),option.verification(),List.of(
                "Preview only: compilation, tests, duplicate-logic and behavioral-contract verification were not performed.",
                "Bound to the recorded Base/Head and approved decision; the current remote PR revision was not verified.",
                "Only existing Java method/constructor bodies are supported; declarations, fields, file creation/deletion and broad rewrites are refused.",
                "Large-body rewrite detection is a conservative text heuristic, not proof of minimality or semantic correctness."),true,false,false,false,false);
        return new PreparedPreview(preview,prepared);
    }

    public static void safePath(String path) {
        require(path!=null && path.length()<=512 && path.matches("[A-Za-z0-9_.$/-]+\\.java")
                && !path.startsWith("/") && java.util.Arrays.stream(path.split("/",-1)).noneMatch(segment->segment.isEmpty() || Set.of(".","..",".git").contains(segment)),"preview_unsafe_java_path");
    }
    public static ReuseDecisionService.ValidatedDecision validated(ReuseDecisionStore.PlanningView view) {
        require(view!=null && view.currentDecision()!=null && view.currentRevision()==view.currentDecision().revision(),"approved_decision_required");
        var decision=view.currentDecision().decision(); var p=decision.provenance(); var option=decision.selectedOption();
        var submission=new ReuseDecisionService.Submission(0,decision.investigationId(),p.baseSha(),p.headSha(),p.adapterVersion(),p.baseBuildModelHash(),p.headBuildModelHash(),
                decision.goal(),decision.decision(),decision.selectedCandidateId(),decision.candidateRejections(),decision.justification(),decision.changeBudget(),
                new ReuseDecisionService.OptionSubmission(option.strategy(),option.title(),option.summary(),option.candidateId(),option.expectedFiles(),option.expectedSymbols(),option.verification(),option.tradeoffs()));
        var valid=new ReuseDecisionService().validate(submission,view.investigation());
        require(valid.equals(decision),"preview_frozen_evidence_mismatch"); return valid;
    }
    private static CompilationUnit parse(String source) {
        require(!source.matches("(?s).*\\\\u+[0-9a-fA-F]{4}.*") && source.chars().noneMatch(value->value==0),"preview_unsupported_source_encoding");
        int depth=0; for(char value:source.toCharArray()) {if(value=='{' || value=='(' || value=='[') require(++depth<=128,"preview_nesting_limit"); else if(value=='}' || value==')' || value==']') depth=Math.max(0,depth-1);}
        try {
            var result=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)).parse(source);
            require(result.isSuccessful() && result.getResult().isPresent(),"preview_java_parse_failed");
            var unit=result.getResult().orElseThrow(); require(unit.stream().limit(20001).count()<=20000,"preview_ast_limit"); return unit;
        } catch(StackOverflowError refused) { throw new IllegalArgumentException("preview_parser_depth_refused"); }
    }
    private static BlockStmt body(CallableDeclaration<?> call) {
        if(call instanceof MethodDeclaration method) return method.getBody().orElse(null);
        return call instanceof ConstructorDeclaration constructor ? constructor.getBody() : null;
    }
    private static String structure(CompilationUnit unit) {
        List<String> declarations=new ArrayList<>();
        for(var call:unit.findAll(CallableDeclaration.class)) {
            var copy=(CallableDeclaration<?>)call.clone(); copy.getAllContainedComments().forEach(Node::remove); copy.removeComment();
            boolean present=body(copy)!=null;
            if(copy instanceof MethodDeclaration method) method.removeBody();
            if(copy instanceof ConstructorDeclaration constructor) constructor.setBody(new BlockStmt());
            declarations.add(present+":"+copy);
        }
        unit.findAll(FieldDeclaration.class).forEach(field->declarations.add("field:"+field));
        unit.findAll(TypeDeclaration.class).forEach(type->{var copy=(TypeDeclaration<?>)type.clone(); copy.getMembers().clear(); declarations.add("type:"+copy);});
        var skeleton=unit.clone(); skeleton.getAllContainedComments().forEach(Node::remove); skeleton.removeComment();
        skeleton.findAll(MethodDeclaration.class).forEach(method->{if(method.getBody().isPresent()) method.setBody(new BlockStmt());});
        skeleton.findAll(ConstructorDeclaration.class).forEach(constructor->constructor.setBody(new BlockStmt()));
        return skeleton+"\n"+String.join("\n",declarations);
    }
    private static String unified(String path,List<String> original,List<Edit> edits,String eol,boolean terminal) {
        int first=Math.max(1,edits.get(0).startLine()-3),last=Math.min(original.size(),edits.get(edits.size()-1).endLine()+3);
        int removed=edits.stream().mapToInt(edit->edit.endLine()-edit.startLine()+1).sum(),added=edits.stream().mapToInt(edit->edit.replacement().size()).sum();
        StringBuilder out=new StringBuilder("--- a/"+path+"\n+++ b/"+path+"\n@@ -"+first+","+(last-first+1)+" +"+first+","+(last-first+1-removed+added)+" @@\n");
        int cursor=first; for(var edit:edits) {
            while(cursor<edit.startLine()) line(out,' ',original.get(cursor++-1),eol,terminal || cursor-1<original.size());
            while(cursor<=edit.endLine()) line(out,'-',original.get(cursor++-1),eol,terminal || cursor-1<original.size());
            edit.replacement().forEach(value->line(out,'+',value,eol,true));
        }
        while(cursor<=last) line(out,' ',original.get(cursor++-1),eol,terminal || cursor-1<original.size());
        return out.toString();
    }
    private static void line(StringBuilder out,char prefix,String content,String eol,boolean terminated) {
        out.append(prefix).append(content).append(terminated?eol:"\n"); if(!terminated) out.append("\\ No newline at end of file\n");
    }
    private static void require(boolean condition,String code) {if(!condition) throw new IllegalArgumentException(code);}
}
