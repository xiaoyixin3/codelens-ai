package store

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/intelligence"
	"github.com/xiaoyixin3/codelens-ai/internal/llm"
	"github.com/xiaoyixin3/codelens-ai/internal/policy"
)

type Store struct{ pool *pgxpool.Pool }

type CreateReviewRunInput struct {
	RepositoryID    int64
	PullNumber      int
	BaseSHA         string
	HeadSHA         string
	PipelineVersion string
	ConfigHash      string
	Trigger         string
	RequestKey      string
}

type FindingFeedbackInput struct {
	RepositoryID      int64
	PullNumber        int
	FingerprintPrefix string
	Verdict           string
	ActorLogin        string
	SourceCommentID   int64
}

type Publication struct {
	ReviewRunID      string
	HeadSHA          string
	CheckRunID       *int64
	SummaryCommentID *int64
}

type ProviderConnection struct {
	ID                    string     `json:"id"`
	InstallationID        int64      `json:"installationId"`
	Name                  string     `json:"name"`
	ProviderKind          string     `json:"providerKind"`
	BaseURL               string     `json:"baseUrl"`
	CredentialCiphertext  string     `json:"-"`
	CredentialKeyVersion  int        `json:"-"`
	CredentialFingerprint string     `json:"credentialFingerprint"`
	DefaultModel          string     `json:"defaultModel"`
	Enabled               bool       `json:"enabled"`
	TimeoutSeconds        int        `json:"timeoutSeconds"`
	MaxRetries            int        `json:"maxRetries"`
	LastTestStatus        string     `json:"lastTestStatus"`
	LastTestDetail        string     `json:"lastTestDetail,omitempty"`
	LastTestedAt          *time.Time `json:"lastTestedAt,omitempty"`
	CreatedAt             time.Time  `json:"createdAt"`
	UpdatedAt             time.Time  `json:"updatedAt"`
}

type CreateProviderConnectionInput struct {
	ID                    string
	InstallationID        int64
	Name                  string
	ProviderKind          string
	BaseURL               string
	CredentialCiphertext  string
	CredentialKeyVersion  int
	CredentialFingerprint string
	DefaultModel          string
	Enabled               bool
	TimeoutSeconds        int
	MaxRetries            int
	Actor                 string
}

type UpdateProviderConnectionInput struct {
	InstallationID int64
	ID             string
	Name           string
	BaseURL        string
	DefaultModel   string
	Enabled        bool
	TimeoutSeconds int
	MaxRetries     int
	Actor          string
}

func Open(ctx context.Context, databaseURL string) (*Store, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("open postgres: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping postgres: %w", err)
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close()                         { s.pool.Close() }
func (s *Store) Ping(ctx context.Context) error { return s.pool.Ping(ctx) }

func (s *Store) ClaimDelivery(ctx context.Context, deliveryID, event, action string, rawBody []byte) (bool, error) {
	digest := sha256.Sum256(rawBody)
	result, err := s.pool.Exec(ctx, `
		INSERT INTO webhook_deliveries (delivery_id, event, action, payload_hash, signature_valid)
		VALUES ($1, $2, NULLIF($3, ''), $4, true)
		ON CONFLICT (delivery_id) DO NOTHING`, deliveryID, event, action, hex.EncodeToString(digest[:]))
	if err != nil {
		return false, err
	}
	return result.RowsAffected() == 1, nil
}

func (s *Store) MarkDeliveryProcessed(ctx context.Context, deliveryID, detail string) error {
	status := "processed"
	var errorDetail any
	if detail != "" {
		status = "failed"
		errorDetail = detail
	}
	_, err := s.pool.Exec(ctx, `
		UPDATE webhook_deliveries SET status=$2, error_detail=$3, processed_at=now()
		WHERE delivery_id=$1`, deliveryID, status, errorDetail)
	return err
}

func (s *Store) CreateOrGetReviewRun(ctx context.Context, input CreateReviewRunInput) (contracts.ReviewRun, bool, error) {
	if input.Trigger == "" {
		input.Trigger = "webhook"
	}
	if input.RequestKey == "" {
		input.RequestKey = "automatic"
	}
	id := uuid.NewString()
	row := s.pool.QueryRow(ctx, `
		INSERT INTO review_runs (
			id, github_repository_id, pull_number, base_sha, head_sha, status,
			pipeline_version, config_hash, trigger, request_key
		) VALUES ($1,$2,$3,$4,$5,'queued',$6,$7,$8,$9)
		ON CONFLICT (github_repository_id, pull_number, head_sha, pipeline_version, request_key)
		DO NOTHING RETURNING id, github_repository_id, pull_number, base_sha, head_sha,
		status, pipeline_version, config_hash, trigger, request_key, COALESCE(summary, 'null'::jsonb)`,
		id, input.RepositoryID, input.PullNumber, input.BaseSHA, input.HeadSHA,
		input.PipelineVersion, input.ConfigHash, input.Trigger, input.RequestKey)
	run, err := scanReviewRun(row)
	if err == nil {
		return run, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return contracts.ReviewRun{}, false, err
	}
	row = s.pool.QueryRow(ctx, `
		SELECT id, github_repository_id, pull_number, base_sha, head_sha,
		status, pipeline_version, config_hash, trigger, request_key, COALESCE(summary, 'null'::jsonb)
		FROM review_runs WHERE github_repository_id=$1 AND pull_number=$2 AND head_sha=$3
		AND pipeline_version=$4 AND request_key=$5 LIMIT 1`,
		input.RepositoryID, input.PullNumber, input.HeadSHA, input.PipelineVersion, input.RequestKey)
	run, err = scanReviewRun(row)
	return run, false, err
}

func (s *Store) CreateOrGetReviewRunAndEnqueue(ctx context.Context, input CreateReviewRunInput, job contracts.ReviewJob) (contracts.ReviewRun, bool, error) {
	if input.Trigger == "" {
		input.Trigger = "webhook"
	}
	if input.RequestKey == "" {
		input.RequestKey = "automatic"
	}
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return contracts.ReviewRun{}, false, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `
		INSERT INTO github_installations (id,account_login,active)
		VALUES ($1,$2,true)
		ON CONFLICT (id) DO UPDATE SET account_login=EXCLUDED.account_login,active=true,updated_at=now()`,
		job.InstallationID, job.Owner); err != nil {
		return contracts.ReviewRun{}, false, err
	}
	if _, err := tx.Exec(ctx, `
		DELETE FROM repository_model_policies
		WHERE github_repository_id=$1 AND installation_id<>$2`, input.RepositoryID, job.InstallationID); err != nil {
		return contracts.ReviewRun{}, false, err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO github_repositories (id,installation_id,owner_login,name,selected,last_seen_at)
		VALUES ($1,$2,$3,$4,true,now())
		ON CONFLICT (id) DO UPDATE SET installation_id=EXCLUDED.installation_id,
			owner_login=EXCLUDED.owner_login,name=EXCLUDED.name,selected=true,
			last_seen_at=now(),updated_at=now()`,
		input.RepositoryID, job.InstallationID, job.Owner, job.Repo); err != nil {
		return contracts.ReviewRun{}, false, err
	}
	id := uuid.NewString()
	run, err := scanReviewRun(tx.QueryRow(ctx, `
		INSERT INTO review_runs (
			id, github_repository_id, pull_number, base_sha, head_sha, status,
			pipeline_version, config_hash, trigger, request_key
		) VALUES ($1,$2,$3,$4,$5,'queued',$6,$7,$8,$9)
		ON CONFLICT (github_repository_id, pull_number, head_sha, pipeline_version, request_key)
		DO NOTHING RETURNING id, github_repository_id, pull_number, base_sha, head_sha,
		status, pipeline_version, config_hash, trigger, request_key, COALESCE(summary, 'null'::jsonb)`,
		id, input.RepositoryID, input.PullNumber, input.BaseSHA, input.HeadSHA,
		input.PipelineVersion, input.ConfigHash, input.Trigger, input.RequestKey))
	created := err == nil
	if errors.Is(err, pgx.ErrNoRows) {
		run, err = scanReviewRun(tx.QueryRow(ctx, `
			SELECT id, github_repository_id, pull_number, base_sha, head_sha,
			status, pipeline_version, config_hash, trigger, request_key, COALESCE(summary, 'null'::jsonb)
			FROM review_runs WHERE github_repository_id=$1 AND pull_number=$2 AND head_sha=$3
			AND pipeline_version=$4 AND request_key=$5 LIMIT 1`,
			input.RepositoryID, input.PullNumber, input.HeadSHA, input.PipelineVersion, input.RequestKey))
	}
	if err != nil {
		return contracts.ReviewRun{}, false, err
	}
	if created {
		job.ReviewRunID = run.ID
		if !job.Valid() {
			return contracts.ReviewRun{}, false, errors.New("invalid review job")
		}
		payload, err := json.Marshal(job)
		if err != nil {
			return contracts.ReviewRun{}, false, err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO review_jobs (id,review_run_id,payload) VALUES ($1,$2,$3)`, uuid.NewString(), run.ID, payload); err != nil {
			return contracts.ReviewRun{}, false, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return contracts.ReviewRun{}, false, err
	}
	return run, created, nil
}

func (s *Store) CreateProviderConnection(ctx context.Context, input CreateProviderConnectionInput) (ProviderConnection, error) {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return ProviderConnection{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `
		INSERT INTO github_installations (id,account_login,active)
		VALUES ($1,$2,true)
		ON CONFLICT (id) DO UPDATE SET active=true,updated_at=now()`,
		input.InstallationID, fmt.Sprintf("installation:%d", input.InstallationID)); err != nil {
		return ProviderConnection{}, err
	}
	connection, err := scanProviderConnection(tx.QueryRow(ctx, `
		INSERT INTO provider_connections (
			id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		RETURNING id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries,last_test_status,COALESCE(last_test_detail,''),
			last_tested_at,created_at,updated_at`,
		input.ID, input.InstallationID, input.Name, input.ProviderKind, input.BaseURL,
		input.CredentialCiphertext, input.CredentialKeyVersion, input.CredentialFingerprint,
		input.DefaultModel, input.Enabled, input.TimeoutSeconds, input.MaxRetries))
	if err != nil {
		return ProviderConnection{}, err
	}
	if err := insertProviderAudit(ctx, tx, input.InstallationID, input.ID, "created", input.Actor, map[string]any{"providerKind": input.ProviderKind, "model": input.DefaultModel}); err != nil {
		return ProviderConnection{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ProviderConnection{}, err
	}
	return connection, nil
}

func (s *Store) ListProviderConnections(ctx context.Context, installationID int64) ([]ProviderConnection, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries,last_test_status,COALESCE(last_test_detail,''),
			last_tested_at,created_at,updated_at
		FROM provider_connections WHERE installation_id=$1 ORDER BY name,id`, installationID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	connections := make([]ProviderConnection, 0)
	for rows.Next() {
		connection, err := scanProviderConnection(rows)
		if err != nil {
			return nil, err
		}
		connections = append(connections, connection)
	}
	return connections, rows.Err()
}

func (s *Store) GetProviderConnection(ctx context.Context, installationID int64, id string) (ProviderConnection, error) {
	return scanProviderConnection(s.pool.QueryRow(ctx, `
		SELECT id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries,last_test_status,COALESCE(last_test_detail,''),
			last_tested_at,created_at,updated_at
		FROM provider_connections WHERE installation_id=$1 AND id=$2`, installationID, id))
}

func (s *Store) UpdateProviderConnection(ctx context.Context, input UpdateProviderConnectionInput) (ProviderConnection, error) {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return ProviderConnection{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	connection, err := scanProviderConnection(tx.QueryRow(ctx, `
		UPDATE provider_connections SET name=$3,base_url=$4,default_model=$5,enabled=$6,
			timeout_seconds=$7,max_retries=$8,last_test_status='untested',
			last_test_detail=NULL,last_tested_at=NULL,updated_at=now()
		WHERE installation_id=$1 AND id=$2
		RETURNING id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries,last_test_status,COALESCE(last_test_detail,''),
			last_tested_at,created_at,updated_at`, input.InstallationID, input.ID, input.Name,
		input.BaseURL, input.DefaultModel, input.Enabled, input.TimeoutSeconds, input.MaxRetries))
	if err != nil {
		return ProviderConnection{}, err
	}
	if err := insertProviderAudit(ctx, tx, input.InstallationID, input.ID, "updated", input.Actor, map[string]any{"model": input.DefaultModel, "enabled": input.Enabled}); err != nil {
		return ProviderConnection{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ProviderConnection{}, err
	}
	return connection, nil
}

func (s *Store) RotateProviderCredential(ctx context.Context, installationID int64, id, ciphertext, fingerprint, actor string, keyVersion int) (ProviderConnection, error) {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return ProviderConnection{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	connection, err := scanProviderConnection(tx.QueryRow(ctx, `
		UPDATE provider_connections SET credential_ciphertext=$3,credential_key_version=$4,
			credential_fingerprint=$5,last_test_status='untested',last_test_detail=NULL,
			last_tested_at=NULL,updated_at=now()
		WHERE installation_id=$1 AND id=$2
		RETURNING id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries,last_test_status,COALESCE(last_test_detail,''),
			last_tested_at,created_at,updated_at`, installationID, id, ciphertext, keyVersion, fingerprint))
	if err != nil {
		return ProviderConnection{}, err
	}
	if err := insertProviderAudit(ctx, tx, installationID, id, "rotated", actor, map[string]any{"credentialFingerprint": fingerprint}); err != nil {
		return ProviderConnection{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ProviderConnection{}, err
	}
	return connection, nil
}

func (s *Store) RecordProviderTest(ctx context.Context, installationID int64, id, status, detail, actor string) (ProviderConnection, error) {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return ProviderConnection{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	connection, err := scanProviderConnection(tx.QueryRow(ctx, `
		UPDATE provider_connections SET last_test_status=$3,last_test_detail=NULLIF($4,''),
			last_tested_at=now(),updated_at=now()
		WHERE installation_id=$1 AND id=$2
		RETURNING id,installation_id,name,provider_kind,base_url,credential_ciphertext,
			credential_key_version,credential_fingerprint,default_model,enabled,
			timeout_seconds,max_retries,last_test_status,COALESCE(last_test_detail,''),
			last_tested_at,created_at,updated_at`, installationID, id, status, detail))
	if err != nil {
		return ProviderConnection{}, err
	}
	if err := insertProviderAudit(ctx, tx, installationID, id, "tested", actor, map[string]any{"status": status}); err != nil {
		return ProviderConnection{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ProviderConnection{}, err
	}
	return connection, nil
}

func (s *Store) DeleteProviderConnection(ctx context.Context, installationID int64, id, actor string) error {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	result, err := tx.Exec(ctx, `DELETE FROM provider_connections WHERE installation_id=$1 AND id=$2`, installationID, id)
	if err != nil {
		return err
	}
	if result.RowsAffected() == 0 {
		return pgx.ErrNoRows
	}
	if err := insertProviderAudit(ctx, tx, installationID, id, "deleted", actor, map[string]any{}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func scanProviderConnection(row pgx.Row) (ProviderConnection, error) {
	var connection ProviderConnection
	err := row.Scan(&connection.ID, &connection.InstallationID, &connection.Name,
		&connection.ProviderKind, &connection.BaseURL, &connection.CredentialCiphertext,
		&connection.CredentialKeyVersion, &connection.CredentialFingerprint,
		&connection.DefaultModel, &connection.Enabled, &connection.TimeoutSeconds,
		&connection.MaxRetries, &connection.LastTestStatus, &connection.LastTestDetail,
		&connection.LastTestedAt, &connection.CreatedAt, &connection.UpdatedAt)
	return connection, err
}

func insertProviderAudit(ctx context.Context, executor pgx.Tx, installationID int64, connectionID, action, actor string, detail map[string]any) error {
	encoded, _ := json.Marshal(detail)
	_, err := executor.Exec(ctx, `
		INSERT INTO provider_connection_audit (id,installation_id,connection_id,action,actor,detail)
		VALUES ($1,$2,NULLIF($3,'')::uuid,$4,$5,$6)`, uuid.NewString(), installationID,
		connectionID, action, actor, json.RawMessage(encoded))
	return err
}

func scanReviewRun(row pgx.Row) (contracts.ReviewRun, error) {
	var run contracts.ReviewRun
	var summary []byte
	err := row.Scan(&run.ID, &run.RepositoryID, &run.PullNumber, &run.BaseSHA, &run.HeadSHA,
		&run.Status, &run.PipelineVersion, &run.ConfigHash, &run.Trigger, &run.RequestKey, &summary)
	if err == nil && string(summary) != "null" {
		run.Summary = summary
	}
	return run, err
}

func (s *Store) GetReviewRun(ctx context.Context, id string) (contracts.ReviewRun, error) {
	return scanReviewRun(s.pool.QueryRow(ctx, `
		SELECT id, github_repository_id, pull_number, base_sha, head_sha,
		status, pipeline_version, config_hash, trigger, request_key, COALESCE(summary, 'null'::jsonb)
		FROM review_runs WHERE id=$1`, id))
}

func (s *Store) UpdateReviewRun(ctx context.Context, id, status string, summary json.RawMessage, errorCode, errorDetail string) error {
	var summaryValue any
	if len(summary) > 0 {
		summaryValue = summary
	}
	_, err := s.pool.Exec(ctx, `
		UPDATE review_runs SET status=$2, summary=$3, error_code=NULLIF($4,''), error_detail=NULLIF($5,''),
		started_at=CASE WHEN $2='in_progress' THEN COALESCE(started_at,now()) ELSE started_at END,
		completed_at=CASE WHEN $2 IN ('completed','failed','stale','skipped') THEN now() ELSE completed_at END,
		updated_at=now() WHERE id=$1`, id, status, summaryValue, errorCode, errorDetail)
	return err
}

func (s *Store) UpdateReviewRunConfig(ctx context.Context, id, configHash string) error {
	_, err := s.pool.Exec(ctx, `UPDATE review_runs SET config_hash=$2,updated_at=now() WHERE id=$1`, id, configHash)
	return err
}

func (s *Store) SavePolicy(ctx context.Context, repositoryID int64, configured policy.Policy) error {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `DELETE FROM project_rules WHERE github_repository_id=$1 AND source_commit_sha=$2`, repositoryID, configured.SourceCommitSHA); err != nil {
		return err
	}
	for _, rule := range configured.Rules {
		if _, err := tx.Exec(ctx, `
			INSERT INTO project_rules (
				id,github_repository_id,source,rule_key,content,scope_glob,severity,
				enabled,source_commit_sha,config_hash
			) VALUES ($1,$2,$3,$4,$5,NULLIF($6,''),NULLIF($7,''),true,$8,$9)`,
			uuid.NewString(), repositoryID, rule.Source, rule.Key, rule.Content, rule.Scope,
			rule.Severity, configured.SourceCommitSHA, configured.Hash); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (s *Store) SaveIntelligence(ctx context.Context, reviewRunID string, result intelligence.Result) error {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	saveSnapshot := func(snapshot intelligence.Snapshot) (string, error) {
		coverage, _ := json.Marshal(snapshot.Coverage)
		actualID := snapshot.ID
		err := tx.QueryRow(ctx, `
			INSERT INTO code_snapshots (
				id,github_repository_id,commit_sha,base_sha,parser_version,scope_hash,
				status,scope,coverage,completed_at
			) VALUES ($1,$2,$3,$4,$5,$6,'ready','pull_request_delta',$7,now())
			ON CONFLICT (github_repository_id,commit_sha,parser_version,scope_hash)
			DO UPDATE SET status='ready',coverage=EXCLUDED.coverage,error_detail=NULL,
				completed_at=now(),updated_at=now()
			RETURNING id`, snapshot.ID, snapshot.RepositoryID, snapshot.CommitSHA,
			snapshot.BaseSHA, intelligence.ParserVersion, snapshot.ScopeHash, json.RawMessage(coverage)).Scan(&actualID)
		if err != nil {
			return "", err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM indexed_files WHERE snapshot_id=$1`, actualID); err != nil {
			return "", err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM code_edges WHERE snapshot_id=$1`, actualID); err != nil {
			return "", err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM code_symbols WHERE snapshot_id=$1`, actualID); err != nil {
			return "", err
		}
		for _, file := range snapshot.Files {
			if _, err := tx.Exec(ctx, `
				INSERT INTO indexed_files (snapshot_id,path,language,content_hash,status,skip_reason,parse_errors)
				VALUES ($1,$2,NULLIF($3,''),NULLIF($4,''),$5,NULLIF($6,''),$7)`,
				actualID, file.Path, file.Language, file.ContentHash, file.Status, file.SkipReason, file.ParseErrors); err != nil {
				return "", err
			}
		}
		for _, symbol := range snapshot.Symbols {
			metadata, _ := json.Marshal(symbol.Metadata)
			if _, err := tx.Exec(ctx, `
				INSERT INTO code_symbols (
					id,snapshot_id,stable_key,path,kind,name,qualified_name,start_line,end_line,
					signature,content_hash,exported,metadata
				) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
				uuid.NewString(), actualID, symbol.StableKey, symbol.Path, symbol.Kind, symbol.Name,
				symbol.QualifiedName, symbol.StartLine, symbol.EndLine, symbol.Signature,
				symbol.ContentHash, symbol.Exported, json.RawMessage(metadata)); err != nil {
				return "", err
			}
		}
		for _, edge := range snapshot.Edges {
			if _, err := tx.Exec(ctx, `
				INSERT INTO code_edges (
					id,snapshot_id,from_stable_key,to_stable_key,type,confidence,source_path,source_line
				) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
				uuid.NewString(), actualID, edge.FromStableKey, edge.ToStableKey, edge.Type,
				edge.Confidence, edge.SourcePath, edge.SourceLine); err != nil {
				return "", err
			}
		}
		return actualID, nil
	}

	baseID, err := saveSnapshot(result.Base)
	if err != nil {
		return err
	}
	headID, err := saveSnapshot(result.Head)
	if err != nil {
		return err
	}
	coverage, _ := json.Marshal(map[string]any{
		"scope": "pull_request_delta", "baseSymbols": len(result.Base.Symbols),
		"headSymbols": len(result.Head.Symbols), "baseEdges": len(result.Base.Edges),
		"headEdges": len(result.Head.Edges), "maxDepth": 2,
		"warning": result.Summary.CoverageWarning,
	})
	analysisID := uuid.NewString()
	if err := tx.QueryRow(ctx, `
		INSERT INTO impact_analyses (
			id,review_run_id,base_snapshot_id,head_snapshot_id,max_depth,
			blast_radius_score,risk_level,coverage
		) VALUES ($1,$2,$3,$4,2,$5,$6,$7)
		ON CONFLICT (review_run_id) DO UPDATE SET
			base_snapshot_id=EXCLUDED.base_snapshot_id,head_snapshot_id=EXCLUDED.head_snapshot_id,
			blast_radius_score=EXCLUDED.blast_radius_score,risk_level=EXCLUDED.risk_level,
			coverage=EXCLUDED.coverage,updated_at=now()
		RETURNING id`, analysisID, reviewRunID, baseID, headID, result.Summary.Score,
		result.Summary.Level, json.RawMessage(coverage)).Scan(&analysisID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM symbol_changes WHERE impact_analysis_id=$1`, analysisID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM impact_paths WHERE impact_analysis_id=$1`, analysisID); err != nil {
		return err
	}
	for _, change := range result.Changes {
		if _, err := tx.Exec(ctx, `
			INSERT INTO symbol_changes (
				id,impact_analysis_id,change_type,kind,qualified_name,before_stable_key,
				after_stable_key,before_path,after_path,body_changed,signature_changed
			) VALUES ($1,$2,$3,$4,$5,NULLIF($6,''),NULLIF($7,''),NULLIF($8,''),NULLIF($9,''),$10,$11)`,
			uuid.NewString(), analysisID, change.Type, change.Kind, change.QualifiedName,
			change.BeforeStableKey, change.AfterStableKey, change.BeforePath, change.AfterPath,
			change.BodyChanged, change.SignatureChanged); err != nil {
			return err
		}
	}
	for _, path := range result.Paths {
		keys, _ := json.Marshal(path.Keys)
		evidence, _ := json.Marshal(path.Evidence)
		if _, err := tx.Exec(ctx, `
			INSERT INTO impact_paths (
				id,impact_analysis_id,changed_stable_key,impacted_stable_key,impacted_name,
				impacted_path,impacted_kind,depth,score,path,evidence
			) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
			uuid.NewString(), analysisID, path.ChangedStableKey, path.ImpactedStableKey,
			path.ImpactedName, path.ImpactedPath, path.ImpactedKind, path.Depth, path.Score,
			json.RawMessage(keys), json.RawMessage(evidence)); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (s *Store) RecordLLMCall(ctx context.Context, call llm.Call) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO llm_calls (
			id,review_run_id,provider,model,task,prompt_hash,status,input_chars,output_chars,
			input_tokens,output_tokens,duration_ms,http_status,error_code,error_detail,created_at
		) VALUES ($1,NULLIF($2,'')::uuid,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,
			NULLIF($14,''),NULLIF($15,''),$16)`,
		call.ID, call.ReviewRunID, call.Provider, call.Model, call.Task, call.PromptHash,
		call.Status, call.InputChars, call.OutputChars, call.InputTokens, call.OutputTokens,
		call.DurationMS, call.HTTPStatus, call.ErrorCode, call.ErrorDetail, call.CreatedAt)
	return err
}

func (s *Store) SavePublication(ctx context.Context, publication Publication) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO publications (id,review_run_id,head_sha,check_run_id,summary_comment_id)
		VALUES ($1,$2,$3,$4,$5)
		ON CONFLICT (review_run_id) DO UPDATE SET head_sha=EXCLUDED.head_sha,
		check_run_id=COALESCE(EXCLUDED.check_run_id,publications.check_run_id),
		summary_comment_id=COALESCE(EXCLUDED.summary_comment_id,publications.summary_comment_id),updated_at=now()`,
		uuid.NewString(), publication.ReviewRunID, publication.HeadSHA, publication.CheckRunID, publication.SummaryCommentID)
	return err
}

func (s *Store) GetPublication(ctx context.Context, reviewRunID string) (Publication, error) {
	var publication Publication
	err := s.pool.QueryRow(ctx, `SELECT review_run_id,head_sha,check_run_id,summary_comment_id
		FROM publications WHERE review_run_id=$1`, reviewRunID).Scan(
		&publication.ReviewRunID, &publication.HeadSHA, &publication.CheckRunID, &publication.SummaryCommentID)
	return publication, err
}

func (s *Store) SaveFindingFeedback(ctx context.Context, input FindingFeedbackInput) (bool, error) {
	result, err := s.pool.Exec(ctx, `
		WITH matched AS (
			SELECT f.id FROM findings f JOIN review_runs r ON r.id=f.review_run_id
			WHERE r.github_repository_id=$1 AND r.pull_number=$2 AND f.fingerprint LIKE $3 AND f.status='verified'
			ORDER BY r.created_at DESC LIMIT 1
		)
		INSERT INTO finding_feedback (id,finding_id,verdict,actor_login,source_comment_id)
		SELECT $4,matched.id,$5,$6,$7 FROM matched
		ON CONFLICT (source_comment_id) DO NOTHING`, input.RepositoryID, input.PullNumber,
		input.FingerprintPrefix+"%", uuid.NewString(), input.Verdict, input.ActorLogin, input.SourceCommentID)
	return result.RowsAffected() == 1, err
}

func (s *Store) SaveFindings(ctx context.Context, reviewRunID string, findings []contracts.Finding) error {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	for _, finding := range findings {
		findingID := uuid.NewString()
		published := finding.Publishable && finding.Status == "verified"
		err := tx.QueryRow(ctx, `
			INSERT INTO findings (
				id,review_run_id,fingerprint,source,rule_id,category,severity,confidence,
				title,claim,suggestion,verification,status,rejection_reason,published
			) VALUES ($1,$2,$3,$4,NULLIF($5,''),$6,$7,$8,$9,$10,$11,$12,$13,NULL,$14)
			ON CONFLICT (review_run_id,fingerprint) DO UPDATE SET
				confidence=EXCLUDED.confidence,status=EXCLUDED.status,published=EXCLUDED.published
			RETURNING id`,
			findingID, reviewRunID, finding.Fingerprint, finding.Source, finding.RuleID,
			finding.Category, finding.Severity, finding.Confidence, finding.Title, finding.Claim,
			finding.Suggestion, finding.Verification, finding.Status, published).Scan(&findingID)
		if err != nil {
			return err
		}
		if finding.Evidence != nil {
			if _, err = tx.Exec(ctx, `DELETE FROM finding_evidence WHERE finding_id=$1`, findingID); err != nil {
				return err
			}
			_, err = tx.Exec(ctx, `
				INSERT INTO finding_evidence (id,finding_id,path,start_line,end_line,side,excerpt_hash,evidence_type)
				VALUES ($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT DO NOTHING`,
				uuid.NewString(), findingID, finding.Evidence.Path, finding.Evidence.StartLine,
				finding.Evidence.EndLine, finding.Evidence.Side, finding.Evidence.ExcerptHash, finding.Evidence.EvidenceType)
			if err != nil {
				return err
			}
		}
	}
	return tx.Commit(ctx)
}

func (s *Store) Enqueue(ctx context.Context, job contracts.ReviewJob) error {
	if !job.Valid() {
		return errors.New("invalid review job")
	}
	payload, err := json.Marshal(job)
	if err != nil {
		return err
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO review_jobs (id,review_run_id,payload) VALUES ($1,$2,$3)
		ON CONFLICT (review_run_id) DO NOTHING`, uuid.NewString(), job.ReviewRunID, payload)
	return err
}

type ClaimedJob struct {
	ID       string
	Payload  contracts.ReviewJob
	Attempts int
}

func (s *Store) ClaimJob(ctx context.Context) (ClaimedJob, error) {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return ClaimedJob{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var claimed ClaimedJob
	var payload []byte
	err = tx.QueryRow(ctx, `
		SELECT id,payload,attempts FROM review_jobs
		WHERE status IN ('queued','retry') AND available_at<=now()
		ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1`).Scan(&claimed.ID, &payload, &claimed.Attempts)
	if err != nil {
		return ClaimedJob{}, err
	}
	if err := json.Unmarshal(payload, &claimed.Payload); err != nil {
		return ClaimedJob{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE review_jobs SET status='processing',locked_at=now(),updated_at=now() WHERE id=$1`, claimed.ID); err != nil {
		return ClaimedJob{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ClaimedJob{}, err
	}
	return claimed, nil
}

func (s *Store) CompleteJob(ctx context.Context, id string) error {
	_, err := s.pool.Exec(ctx, `UPDATE review_jobs SET status='completed',locked_at=NULL,updated_at=now() WHERE id=$1`, id)
	return err
}

func (s *Store) RecoverStaleJobs(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, `UPDATE review_jobs SET status='retry',locked_at=NULL,available_at=now(),
		last_error='worker lease expired',updated_at=now()
		WHERE status='processing' AND locked_at < now()-interval '15 minutes'`)
	return err
}

func (s *Store) RetryJob(ctx context.Context, id string, attempts int, detail string) (bool, error) {
	nextAttempts := attempts + 1
	terminal := nextAttempts >= 3
	status := "retry"
	delay := time.Duration(1<<attempts) * 2 * time.Second
	if terminal {
		status = "failed"
		delay = 0
	}
	_, err := s.pool.Exec(ctx, `UPDATE review_jobs SET status=$2,attempts=$3,last_error=$4,
		available_at=now()+$5::interval,locked_at=NULL,updated_at=now() WHERE id=$1`,
		id, status, nextAttempts, detail, fmt.Sprintf("%f seconds", delay.Seconds()))
	return terminal, err
}
