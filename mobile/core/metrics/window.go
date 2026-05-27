package metrics

import (
	"sort"
	"sync"
	"time"
)

// DurationSummary represents computed percentiles for a slice of duration metrics.
type DurationSummary struct {
	Count int
	P50   time.Duration
	P95   time.Duration
	P99   time.Duration
}

// DurationWindow is a thread-safe rolling ring buffer of time.Duration values.
// It keeps the last N values (capped at size) and calculates percentiles efficiently.
type DurationWindow struct {
	mu     sync.Mutex
	values []time.Duration
	cursor int
	size   int
}

// NewDurationWindow creates a new rolling window with the specified capacity.
func NewDurationWindow(size int) *DurationWindow {
	if size <= 0 {
		size = 512
	}
	return &DurationWindow{
		values: make([]time.Duration, 0, size),
		size:   size,
	}
}

// Add inserts a new duration into the ring buffer, overwriting the oldest value if full.
func (w *DurationWindow) Add(v time.Duration) {
	w.mu.Lock()
	defer w.mu.Unlock()

	if len(w.values) < w.size {
		w.values = append(w.values, v)
	} else {
		w.values[w.cursor] = v
		w.cursor = (w.cursor + 1) % w.size
	}
}

// Snapshot returns a sorted copy of the recent durations and calculates their percentiles.
func (w *DurationWindow) Snapshot() DurationSummary {
	w.mu.Lock()
	count := len(w.values)
	if count == 0 {
		w.mu.Unlock()
		return DurationSummary{}
	}

	// Copy the slice so we don't hold the lock while sorting
	cp := make([]time.Duration, count)
	copy(cp, w.values)
	w.mu.Unlock()

	// Sort the slice to compute percentiles
	sort.Slice(cp, func(i, j int) bool {
		return cp[i] < cp[j]
	})

	return DurationSummary{
		Count: count,
		P50:   percentile(cp, 0.50),
		P95:   percentile(cp, 0.95),
		P99:   percentile(cp, 0.99),
	}
}

// percentile calculates the duration at the given rank (p between 0.0 and 1.0)
func percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	if p <= 0 {
		return sorted[0]
	}
	if p >= 1.0 {
		return sorted[len(sorted)-1]
	}
	// Nearest rank method
	idx := int(float64(len(sorted)) * p)
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}
