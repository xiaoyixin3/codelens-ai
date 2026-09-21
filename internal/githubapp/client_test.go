package githubapp

import "testing"

func TestRepositoryFilePathRejectsTraversalAndWindowsPaths(t *testing.T) {
	unsafe := []string{"../secret", "src\\secret.go", "/etc/passwd", "C:/secret", "src/\x00secret"}
	for _, value := range unsafe {
		if _, err := repositoryFilePath(value); err == nil {
			t.Fatalf("expected %q to be rejected", value)
		}
	}
	path, err := repositoryFilePath("src/review/main.go")
	if err != nil || path != "src/review/main.go" {
		t.Fatalf("unexpected safe path result %q: %v", path, err)
	}
}
