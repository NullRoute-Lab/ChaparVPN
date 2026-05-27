package carrier

import (
	"context"
	"log"
	"time"
)

// runAutoTuneLoop continuously evaluates the client's rolling performance
// metrics (like TTFB) to dynamically adjust `c.pollIdleSleep`.
func (c *Client) runAutoTuneLoop(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.evaluateAutoTune()
		}
	}
}

func (c *Client) evaluateAutoTune() {
	ttfb := c.stats.ttfb.Snapshot()

	c.mu.Lock()
	active := len(c.sessions)
	c.mu.Unlock()

	current := time.Duration(c.pollIdleSleep.Load())
	next := current

	// Aggressive Latency Reduction
	if ttfb.Count >= 8 && active > 0 && ttfb.P95 >= 750*time.Millisecond {
		next = (current * 3) / 4
		if c.cfg.DebugTiming {
			log.Printf("[autotune] scaled DOWN: p95=%s active=%d (new sleep %s)", ttfb.P95, active, next)
		}
	} else if active == 0 || (ttfb.Count >= 8 && ttfb.P95 <= 150*time.Millisecond) {
		// Quota Conservation
		next = (current * 5) / 4
		if c.cfg.DebugTiming {
			log.Printf("[autotune] scaled UP: p95=%s active=%d (new sleep %s)", ttfb.P95, active, next)
		}
	}

	// Clamp to configured bounds
	minSleep := time.Duration(c.cfg.AutoTuneMinSleepMs) * time.Millisecond
	maxSleep := time.Duration(c.cfg.AutoTuneMaxSleepMs) * time.Millisecond

	if next < minSleep {
		next = minSleep
	}
	if next > maxSleep {
		next = maxSleep
	}

	c.pollIdleSleep.Store(int64(next))
}
