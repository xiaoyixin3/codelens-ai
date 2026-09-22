package intelligence

import (
	"fmt"
	"path/filepath"
	"regexp"
	"strings"
)

// The native parser is intentionally bounded. It extracts declarations and direct
// calls without compiling or executing repository code. Language-specific full
// compiler adapters can replace it later without changing the snapshot contract.

var (
	goFunction = regexp.MustCompile(`^\s*func\s+(?:\(\s*\w*\s*\*?([A-Za-z_]\w*)[^)]*\)\s*)?([A-Za-z_]\w*)\s*\(`)
	goType     = regexp.MustCompile(`^\s*type\s+([A-Za-z_]\w*)\s+(?:struct|interface)\b`)

	tsDeclaration = regexp.MustCompile(`^\s*(?:export\s+)?(?:default\s+)?(?:declare\s+)?(?:async\s+)?(class|interface|type|enum|function)\s+([A-Za-z_$][\w$]*)`)
	tsVariable    = regexp.MustCompile(`^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>`)

	jvmType      = regexp.MustCompile(`^\s*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|open|data|value|internal)\s+)*(class|interface|enum|record|object|annotation class)\s+([A-Za-z_$][\w$]*)`)
	javaMethod   = regexp.MustCompile(`^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\s+)*(?:<[^>]+>\s*)?[A-Za-z_$][\w$<>,.?\[\]]*\s+([A-Za-z_$][\w$]*)\s*\(`)
	kotlinMethod = regexp.MustCompile(`^\s*(?:(?:public|protected|private|internal|open|final|abstract|override|suspend|inline|tailrec|operator|infix|external)\s+)*fun\s+(?:<[^>]+>\s*)?([A-Za-z_$][\w$]*)\s*\(`)

	pythonType     = regexp.MustCompile(`^\s*class\s+([A-Za-z_]\w*)\b`)
	pythonFunction = regexp.MustCompile(`^\s*(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\(`)

	cType     = regexp.MustCompile(`^\s*(?:(?:public|protected|private|internal|abstract|sealed|static|partial|readonly|ref|unsafe|final)\s+)*(class|interface|struct|enum|union|namespace)\s+([A-Za-z_]\w*)`)
	cFunction = regexp.MustCompile(`^\s*(?:(?:public|protected|private|internal|static|virtual|override|abstract|sealed|async|extern|inline|constexpr|consteval|friend|unsafe)\s+)*(?:[A-Za-z_~][\w:<>,.?*&\[\]\s]+\s+)([A-Za-z_~]\w*)\s*\(`)

	rustType     = regexp.MustCompile(`^\s*(?:pub(?:\([^)]*\))?\s+)?(struct|enum|trait|union|type|mod)\s+([A-Za-z_]\w*)`)
	rustImpl     = regexp.MustCompile(`^\s*impl(?:<[^>]+>)?\s+(?:[^\s]+\s+for\s+)?([A-Za-z_]\w*)`)
	rustFunction = regexp.MustCompile(`^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(?:extern\s+"[^"]+"\s+)?fn\s+([A-Za-z_]\w*)\s*\(`)

	phpType     = regexp.MustCompile(`^\s*(?:(?:abstract|final|readonly)\s+)*(class|interface|trait|enum)\s+([A-Za-z_]\w*)`)
	phpFunction = regexp.MustCompile(`^\s*(?:(?:public|protected|private|static|final|abstract|readonly)\s+)*(?:function)\s+&?([A-Za-z_]\w*)\s*\(`)

	rubyType     = regexp.MustCompile(`^\s*(class|module)\s+([A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)`)
	rubyFunction = regexp.MustCompile(`^\s*def\s+(?:self\.)?([A-Za-z_]\w*[!?=]?)`)

	swiftType     = regexp.MustCompile(`^\s*(?:(?:public|open|internal|private|fileprivate|final|indirect)\s+)*(class|struct|protocol|enum|actor|extension)\s+([A-Za-z_]\w*)`)
	swiftFunction = regexp.MustCompile(`^\s*(?:(?:public|open|internal|private|fileprivate|static|class|final|override|mutating|nonmutating|required|convenience)\s+)*(?:async\s+)?func\s+([A-Za-z_]\w*)\s*\(`)

	callPattern = regexp.MustCompile(`\b([A-Za-z_$][\w$]*)\s*\(`)
)

type declaration struct {
	kind          string
	name          string
	qualifiedName string
	start         int
	end           int
	signature     string
	exported      bool
	container     bool
	identity      string
}

func supportedLanguage(path string) (string, bool) {
	extension := strings.ToLower(filepath.Ext(path))
	switch extension {
	case ".go":
		return "go", true
	case ".java":
		return "java", true
	case ".kt", ".kts":
		return "kotlin", true
	case ".py", ".pyi", ".pyw":
		return "python", true
	case ".ts", ".tsx", ".mts", ".cts":
		return "typescript", true
	case ".js", ".jsx", ".mjs", ".cjs":
		return "javascript", true
	case ".cs":
		return "csharp", true
	case ".c", ".h":
		return "c", true
	case ".cc", ".cpp", ".cxx", ".hh", ".hpp", ".hxx":
		return "cpp", true
	case ".rs":
		return "rust", true
	case ".php", ".phtml":
		return "php", true
	case ".rb", ".rake":
		return "ruby", true
	case ".swift":
		return "swift", true
	default:
		return "", false
	}
}

func parse(path, language, content string) ([]Symbol, []Edge) {
	lines := strings.Split(content, "\n")
	moduleKey := language + ":" + path + ":file:$module"
	symbols := []Symbol{{StableKey: moduleKey, Path: path, Kind: "file", Name: filepath.Base(path), QualifiedName: "$module", StartLine: 1, EndLine: max(1, len(lines)), Signature: path, ContentHash: digest([]byte(content)), Exported: true, Metadata: map[string]any{"language": language, "parser": ParserVersion}}}
	declarations := make([]declaration, 0)
	byName := map[string][]string{}

	for index := range lines {
		decl, ok := detectDeclaration(language, lines, index)
		if !ok || decl.name == "" {
			continue
		}
		for candidateIndex := len(declarations) - 1; candidateIndex >= 0; candidateIndex-- {
			candidate := declarations[candidateIndex]
			if candidate.container && candidate.start < index+1 && candidate.end >= index+1 {
				decl.qualifiedName = candidate.qualifiedName + "." + decl.name
				if decl.kind == "function" {
					decl.kind = "method"
				}
				break
			}
		}
		if decl.qualifiedName == "" {
			decl.qualifiedName = decl.name
		}
		declarations = append(declarations, decl)
		body := strings.Join(lines[index:decl.end], "\n")
		stable := fmt.Sprintf("%s:%s:%s:%s%s", language, path, decl.kind, decl.qualifiedName, decl.identity)
		symbols = append(symbols, Symbol{StableKey: stable, Path: path, Kind: decl.kind, Name: decl.name, QualifiedName: decl.qualifiedName, StartLine: index + 1, EndLine: decl.end, Signature: decl.signature, ContentHash: digest([]byte(body)), Exported: decl.exported, Metadata: map[string]any{"language": language}})
		byName[decl.name] = append(byName[decl.name], stable)
	}

	edges := make([]Edge, 0)
	for _, symbol := range symbols[1:] {
		for lineIndex := symbol.StartLine - 1; lineIndex < symbol.EndLine && lineIndex < len(lines); lineIndex++ {
			for _, match := range callPattern.FindAllStringSubmatch(stripLineComment(language, lines[lineIndex]), -1) {
				name := match[1]
				if name == symbol.Name || controlWord(name) {
					continue
				}
				target, confidence := "unresolved:"+name, .4
				if candidates := byName[name]; len(candidates) == 1 {
					target, confidence = candidates[0], .9
				}
				edges = append(edges, Edge{FromStableKey: symbol.StableKey, ToStableKey: target, Type: "CALLS", Confidence: confidence, SourcePath: path, SourceLine: lineIndex + 1})
			}
		}
	}
	return uniqueSymbols(symbols), uniqueEdges(edges)
}

func detectDeclaration(language string, lines []string, index int) (declaration, bool) {
	line := lines[index]
	result := declaration{start: index + 1, signature: strings.TrimSpace(line)}
	finish := func(kind, name string, exported, container bool) (declaration, bool) {
		if name == "" || controlWord(name) {
			return declaration{}, false
		}
		if result.qualifiedName != "" && kind == "function" {
			kind = "method"
		}
		result.kind, result.name, result.exported, result.container = kind, name, exported, container
		result.end = declarationEnd(language, lines, index)
		if kind == "function" || kind == "method" {
			result.identity = fmt.Sprintf("/%d", parameterArity(line))
		}
		return result, true
	}

	switch language {
	case "go":
		if match := goFunction.FindStringSubmatch(line); match != nil {
			name := match[2]
			if match[1] != "" {
				result.qualifiedName = match[1] + "." + name
			}
			return finish("function", name, startsUpper(name), false)
		}
		if match := goType.FindStringSubmatch(line); match != nil {
			return finish("type", match[1], startsUpper(match[1]), true)
		}
	case "typescript", "javascript":
		if match := tsDeclaration.FindStringSubmatch(line); match != nil {
			kind := match[1]
			return finish(kind, match[2], strings.Contains(line, "export"), kind != "function" && kind != "type")
		}
		if match := tsVariable.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], strings.Contains(line, "export"), false)
		}
	case "java", "kotlin":
		if match := jvmType.FindStringSubmatch(line); match != nil {
			return finish(normalizeTypeKind(match[1]), match[2], strings.Contains(line, "public") || language == "kotlin", true)
		}
		if rejectedDeclarationLine(line) {
			return declaration{}, false
		}
		if language == "java" {
			if match := javaMethod.FindStringSubmatch(line); match != nil {
				return finish("function", match[1], strings.Contains(line, "public"), false)
			}
		} else if match := kotlinMethod.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], !strings.Contains(line, "private") && !strings.Contains(line, "protected"), false)
		}
	case "python":
		if match := pythonType.FindStringSubmatch(line); match != nil {
			return finish("class", match[1], !strings.HasPrefix(match[1], "_"), true)
		}
		if match := pythonFunction.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], !strings.HasPrefix(match[1], "_"), false)
		}
	case "c", "cpp", "csharp":
		if match := cType.FindStringSubmatch(line); match != nil {
			return finish(normalizeTypeKind(match[1]), match[2], strings.Contains(line, "public") || language != "csharp", true)
		}
		if !rejectedDeclarationLine(line) {
			if match := cFunction.FindStringSubmatch(line); match != nil {
				return finish("function", match[1], strings.Contains(line, "public") || language != "csharp", false)
			}
		}
	case "rust":
		if match := rustType.FindStringSubmatch(line); match != nil {
			return finish(normalizeTypeKind(match[1]), match[2], strings.Contains(line, "pub"), true)
		}
		if match := rustImpl.FindStringSubmatch(line); match != nil {
			return finish("implementation", match[1], false, true)
		}
		if match := rustFunction.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], strings.Contains(line, "pub"), false)
		}
	case "php":
		if match := phpType.FindStringSubmatch(line); match != nil {
			return finish(normalizeTypeKind(match[1]), match[2], true, true)
		}
		if match := phpFunction.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], !strings.Contains(line, "private") && !strings.Contains(line, "protected"), false)
		}
	case "ruby":
		if match := rubyType.FindStringSubmatch(line); match != nil {
			return finish(match[1], match[2], true, true)
		}
		if match := rubyFunction.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], true, false)
		}
	case "swift":
		if match := swiftType.FindStringSubmatch(line); match != nil {
			return finish(normalizeTypeKind(match[1]), match[2], strings.Contains(line, "public") || strings.Contains(line, "open"), true)
		}
		if match := swiftFunction.FindStringSubmatch(line); match != nil {
			return finish("function", match[1], strings.Contains(line, "public") || strings.Contains(line, "open"), false)
		}
	}
	return declaration{}, false
}

func declarationEnd(language string, lines []string, start int) int {
	switch language {
	case "python":
		return indentationBlockEnd(lines, start)
	case "ruby":
		return rubyBlockEnd(lines, start)
	default:
		return blockEnd(lines, start)
	}
}

func indentationBlockEnd(lines []string, start int) int {
	base := leadingWhitespace(lines[start])
	for index := start + 1; index < len(lines); index++ {
		trimmed := strings.TrimSpace(lines[index])
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		if leadingWhitespace(lines[index]) <= base {
			return index
		}
	}
	return len(lines)
}

func rubyBlockEnd(lines []string, start int) int {
	depth := 0
	starter := regexp.MustCompile(`^\s*(?:class|module|def|if|unless|case|begin|while|until|for)\b|\bdo\s*(?:\|[^|]*\|)?\s*$`)
	ender := regexp.MustCompile(`^\s*end\b`)
	for index := start; index < len(lines) && index < start+500; index++ {
		line := stripLineComment("ruby", lines[index])
		if starter.MatchString(line) {
			depth++
		}
		if ender.MatchString(line) {
			depth--
			if depth <= 0 {
				return index + 1
			}
		}
	}
	return min(len(lines), start+200)
}

func leadingWhitespace(value string) int {
	count := 0
	for _, character := range value {
		if character == ' ' {
			count++
		} else if character == '\t' {
			count += 4
		} else {
			break
		}
	}
	return count
}

func parameterArity(line string) int {
	open := strings.Index(line, "(")
	if open < 0 {
		return 0
	}
	close := strings.LastIndex(line, ")")
	if close <= open || strings.TrimSpace(line[open+1:close]) == "" {
		return 0
	}
	value, depth, count := line[open+1:close], 0, 1
	for _, character := range value {
		switch character {
		case '<', '[', '(':
			depth++
		case '>', ']', ')':
			if depth > 0 {
				depth--
			}
		case ',':
			if depth == 0 {
				count++
			}
		}
	}
	return count
}

func stripLineComment(language, line string) string {
	marker := "//"
	if language == "python" || language == "ruby" {
		marker = "#"
	}
	if language == "php" {
		if index := strings.Index(line, "#"); index >= 0 {
			line = line[:index]
		}
	}
	if index := strings.Index(line, marker); index >= 0 {
		return line[:index]
	}
	return line
}

func normalizeTypeKind(kind string) string {
	switch kind {
	case "struct", "union", "record", "type", "object", "actor", "extension", "annotation class":
		return "type"
	default:
		return kind
	}
}

func startsUpper(value string) bool {
	return value != "" && value[0] >= 'A' && value[0] <= 'Z'
}

func rejectedDeclarationLine(line string) bool {
	trimmed := strings.TrimSpace(line)
	for _, prefix := range []string{"return ", "throw ", "if ", "if(", "for ", "for(", "while ", "while(", "switch ", "switch(", "catch ", "catch(", "case ", "else ", "new ", "delete "} {
		if strings.HasPrefix(trimmed, prefix) {
			return true
		}
	}
	return false
}
