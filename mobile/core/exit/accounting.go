package exit

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
)

// AccountingManager provides dynamic, thread-safe session tracking and quota
// enforcement for commercial users on the exit node.
type AccountingManager struct {
	mu sync.RWMutex

	// quotas holds the remaining byte allowance for each non-admin UUID.
	// Using atomic.Int64 allows wait-free updates on the hot data path.
	quotas map[string]*atomic.Int64

	// sessionLimits holds custom dynamic active session ceilings per UUID.
	// If a UUID is missing, the default MaxSessionsPerUUID is used.
	sessionLimits map[string]int

	// activeSessions tracks the current number of in-flight sessions per UUID.
	activeSessions map[string]int

	defaultMaxSessions int
	adminUUIDs         map[string]bool
}

// NewAccountingManager creates a new in-memory AccountingManager.
func NewAccountingManager(defaultMaxSessions int, admins []string) *AccountingManager {
	am := &AccountingManager{
		quotas:             make(map[string]*atomic.Int64),
		sessionLimits:      make(map[string]int),
		activeSessions:     make(map[string]int),
		defaultMaxSessions: defaultMaxSessions,
		adminUUIDs:         make(map[string]bool),
	}
	for _, admin := range admins {
		am.adminUUIDs[admin] = true
	}
	return am
}

// IsAdmin checks if a UUID bypasses accounting.
func (am *AccountingManager) IsAdmin(uuid string) bool {
	am.mu.RLock()
	defer am.mu.RUnlock()
	return am.adminUUIDs[uuid]
}

// CanStartSession checks if a non-admin user is allowed to spawn a new session.
// If allowed, it automatically increments the active session count.
// The caller MUST call EndSession when the connection closes if this returns true.
func (am *AccountingManager) CanStartSession(uuid string) bool {
	am.mu.Lock()
	defer am.mu.Unlock()

	// 1. Enforce strict default deny: quota must exist.
	q, ok := am.quotas[uuid]
	if !ok || q.Load() <= 0 {
		return false
	}

	// 2. Enforce active session ceiling.
	active := am.activeSessions[uuid]
	limit, hasLimit := am.sessionLimits[uuid]
	if !hasLimit {
		limit = am.defaultMaxSessions
	}

	if active >= limit {
		return false
	}

	am.activeSessions[uuid]++
	return true
}

// EndSession decrements the active session counter for a UUID.
func (am *AccountingManager) EndSession(uuid string) {
	am.mu.Lock()
	defer am.mu.Unlock()
	if count, ok := am.activeSessions[uuid]; ok {
		if count > 1 {
			am.activeSessions[uuid] = count - 1
		} else {
			delete(am.activeSessions, uuid)
		}
	}
}

// ConsumeQuota deducts bytes from a non-admin user's quota atomically.
// It returns true if the user still has remaining quota (or exactly 0) AFTER deduction,
// and false if the quota is exhausted (<= 0).
func (am *AccountingManager) ConsumeQuota(uuid string, bytes int64) bool {
	am.mu.RLock()
	q, ok := am.quotas[uuid]
	am.mu.RUnlock()

	if !ok {
		return false // strictly deny if missing
	}

	remaining := q.Add(-bytes)
	return remaining >= 0
}

// CheckQuota provides a read-only peek at whether the quota is > 0.
func (am *AccountingManager) CheckQuota(uuid string) bool {
	am.mu.RLock()
	q, ok := am.quotas[uuid]
	am.mu.RUnlock()

	if !ok {
		return false
	}
	return q.Load() > 0
}

// --- Admin API Server Handlers ---

type QuotaUpdateReq struct {
	UUID        string `json:"uuid"`
	QuotaBytes  int64  `json:"quota_bytes"`
	MaxSessions int    `json:"max_sessions"`
}

func (am *AccountingManager) handlePostQuota(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req QuotaUpdateReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad JSON", http.StatusBadRequest)
		return
	}

	if req.UUID == "" {
		http.Error(w, "Missing uuid", http.StatusBadRequest)
		return
	}

	am.mu.Lock()
	defer am.mu.Unlock()

	// Update quota
	if q, ok := am.quotas[req.UUID]; ok {
		q.Store(req.QuotaBytes)
	} else {
		newQ := &atomic.Int64{}
		newQ.Store(req.QuotaBytes)
		am.quotas[req.UUID] = newQ
	}

	// Update session limit
	if req.MaxSessions > 0 {
		am.sessionLimits[req.UUID] = req.MaxSessions
	} else {
		delete(am.sessionLimits, req.UUID) // fallback to default
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status":"ok"}`))
}

type UsageSnapshot struct {
	QuotaBytes  int64 `json:"quota_bytes"`
	MaxSessions int   `json:"max_sessions"`
	Active      int   `json:"active_sessions"`
}

func (am *AccountingManager) handleGetUsage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	am.mu.RLock()
	defer am.mu.RUnlock()

	out := make(map[string]UsageSnapshot)
	for uuid, q := range am.quotas {
		limit, hasLimit := am.sessionLimits[uuid]
		if !hasLimit {
			limit = am.defaultMaxSessions
		}
		out[uuid] = UsageSnapshot{
			QuotaBytes:  q.Load(),
			MaxSessions: limit,
			Active:      am.activeSessions[uuid],
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(out)
}

// StartAdminAPI launches the background HTTP server for accounting control.
func (am *AccountingManager) StartAdminAPI(addr string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/quota", am.handlePostQuota)
	mux.HandleFunc("/usage", am.handleGetUsage)

	srv := &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	// Spawns in background
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			fmt.Printf("Admin API server failed: %v\n", err)
		}
	}()
	return nil
}