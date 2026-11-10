package main

/*
#cgo android LDFLAGS: -llog
#include <stdlib.h>
#include <signal.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

static void androidLogProxy(char *msg) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "TgWsProxy", "%s", msg);
#endif
}
*/
import "C"

import (
	"bufio"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

// ---------------------------------------------------------------------------
// Constants & Configuration
// ---------------------------------------------------------------------------

const (
	defaultPort    = 1443
	tcpNodelay     = true
	defaultRecvBuf = 512 * 1024 // bigger: media downloads need larger buffers
	defaultSendBuf = 512 * 1024
	defaultPoolSz  = 10 // 10 pre-warmed connections survive burst traffic peaks

	wsPoolMaxAge = 180.0 // 3 min: keep alive long-lived CF connections

	dcFailCooldown = 15.0 // avoid aggressive retries on a failing DC

	wsFailTimeout = 1.2 // 1.2s: blacklisted DC gets a very short probe

	poolMaintainInterval = 15 // bounded background refill cadence

	// Bridge read deadlines
	bridgeReadTimeout  = 90 * time.Second
	bridgePingInterval = 15 * time.Second   // detect dead mobile connections faster
	wsWriteTimeout     = 8 * time.Second    // CF sometimes slow to accept large frames
	wsControlTimeout   = 3 * time.Second
	wsPoolProbeTimeout = 600 * time.Millisecond // fast probe: discard dead connections quickly
	wsPoolProbeAfter   = 6.0
	wsBridgeChunkSize  = 128 * 1024
	pooledFrameCap     = wsBridgeChunkSize + 32

	wsPoolReuseMaxAge = 180.0 // match wsPoolMaxAge

	cfproxyCacheFileName    = "cfproxy-domains-cache.txt"
	cfproxyActiveFileName   = "cfproxy-active-domain.txt"
	cfproxyRefreshInterval  = 6 * time.Hour   // refresh more often: CF domains change
	cfproxyDialPhaseTimeout = 3 * time.Second // fail faster on bad CF domains
	cfproxyFallbackParallel = 3               // bounded parallel fallback
	cfproxy429Cooldown      = 15 * time.Second  // quick recovery from CF rate limits
	cfproxy429MaxCooldown   = 120 * time.Second // bounded exponential rate-limit backoff
	cfproxyGlobalParallel   = 4              // more concurrent CF attempts during burst
)

var (
	recvBuf    = defaultRecvBuf
	sendBuf    = defaultSendBuf
	poolSize   atomic.Int32
	logVerbose = false
)

type cfproxy429State struct {
	until   time.Time
	strikes int
}

func init() {
	poolSize.Store(defaultPoolSz)
}

// Cloudflare proxy config
var (
	cfproxyEnabled          = true
	cfproxyUserDomain       = ""
	cfproxyDomains          []string
	activeCfDomain          string
	cfproxyCacheDir         = ""
	cfproxyMu               sync.RWMutex
	cfproxy429StateByDomain = make(map[string]cfproxy429State)
	cfproxy429Mu            sync.RWMutex
	cfproxyAttemptSem       = make(chan struct{}, cfproxyGlobalParallel)
)

const cfproxyDomainsURL = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

// MTProto proxy secret (hex, 32 chars = 16 bytes)
var (
	proxySecret   = "00000000000000000000000000000000"
	proxySecretMu sync.RWMutex
)

// FakeTLS config (ee-secret)
var (
	fakeTlsEnabled = false
	fakeTlsDomain  = ""
	fakeTlsMu      sync.RWMutex
)

// DNS over HTTPS (DoH) Cache and Clients
type dohCacheEntry struct {
	ip  string
	exp time.Time
}

var (
	dohCache  sync.Map
	dohClient = &http.Client{
		Timeout: 1500 * time.Millisecond,
		Transport: &http.Transport{
			MaxIdleConns:        10,
			MaxIdleConnsPerHost: 10,
			IdleConnTimeout:     90 * time.Second,
			TLSHandshakeTimeout: 1 * time.Second,
		},
	}
	githubClient = &http.Client{
		Timeout: 10 * time.Second,
	}
)

// wsPaths is the ordered list of WebSocket endpoint paths tried on each domain.
// Rotating paths makes it harder for DPI to match on a single URI signature.
var wsPaths = []string{"/apiws", "/api/ws", "/tg/ws", "/ws"}

type parallelResult struct {
	ws         *RawWebSocket
	err        error
	isRedirect bool
	domain     string
}

func connectParallelWS(ctx context.Context, ip string, domains []string, timeout float64) (*RawWebSocket, bool, bool) {
	if len(domains) == 0 {
		return nil, false, false
	}

	resultChan := make(chan parallelResult, len(domains)*len(wsPaths))
	attemptCtx, cancelAttempts := context.WithCancel(ctx)
	defer cancelAttempts()

	var wg sync.WaitGroup
	
	tryOne := func(dom, path string) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ws, err := wsConnect(attemptCtx, ip, dom, path, timeout)
			isRedirect := false
			if err != nil {
				var wsErr *WsHandshakeError
				if errors.As(err, &wsErr) && wsErr.IsRedirect() {
					isRedirect = true
				}
			}
			
			select {
			case resultChan <- parallelResult{
				ws:         ws,
				err:        err,
				isRedirect: isRedirect,
				domain:     dom,
			}:
			case <-attemptCtx.Done():
				if ws != nil {
					ws.Close()
				}
			}
		}()
	}

	// Try the primary attempt (first domain, first path)
	tryOne(domains[0], wsPaths[0])

	// Wait 120ms before starting other domains/paths in parallel (Happy Eyeballs delay)
	select {
	case res := <-resultChan:
		if res.err == nil {
			cancelAttempts()
			return res.ws, false, false
		}
		// If primary failed immediately, launch remaining attempts right away
	case <-time.After(120 * time.Millisecond):
		// Spacing elapsed, start other combinations in parallel
	case <-ctx.Done():
		return nil, false, false
	}

	for i, dom := range domains {
		for j, path := range wsPaths {
			if i == 0 && j == 0 {
				continue // already tried/trying primary
			}
			tryOne(dom, path)
		}
	}

	// Wait for all attempts to finish or context cancellation
	go func() {
		wg.Wait()
		close(resultChan)
	}()

	var successfulWs *RawWebSocket
	wsFailedRedirect := false
	allRedirects := true

	for res := range resultChan {
		if res.err == nil {
			if successfulWs == nil {
				successfulWs = res.ws
				cancelAttempts() // cancel other ongoing dial/handshake attempts
			} else {
				res.ws.Close()
			}
		} else {
			stats.wsErrors.Add(1)
			if res.isRedirect {
				wsFailedRedirect = true
			} else {
				allRedirects = false
			}
		}
	}

	if successfulWs != nil {
		return successfulWs, wsFailedRedirect, false
	}

	return nil, wsFailedRedirect, allRedirects
}

func connectOneWS(ctx context.Context, ip string, domains []string) *RawWebSocket {
	ws, _, _ := connectParallelWS(ctx, ip, domains, 4.0)
	return ws
}

var dcDefaultIPs = map[int]string{
	1:   "149.154.175.50",
	2:   "149.154.167.51",
	3:   "149.154.175.100",
	4:   "149.154.167.91",
	5:   "149.154.171.5",
	203: "91.105.192.100",
}

func resolveConfiguredTarget(dc int, isMedia bool) (string, bool) {
	dcOptMu.RLock()
	defer dcOptMu.RUnlock()

	if isMedia {
		if target, ok := dcOpt[-dc]; ok && target != "" {
			return target, true
		}
	}
	if target, ok := dcOpt[dc]; ok && target != "" {
		return target, true
	}
	return "", false
}

func resolveFallbackTarget(dc int, isMedia bool) string {
	return dcDefaultIPs[dc]
}

// ---------------------------------------------------------------------------
// Logger
// ---------------------------------------------------------------------------

var (
	logInfo  *log.Logger
	logWarn  *log.Logger
	logError *log.Logger
	logDebug *log.Logger
)

type androidLogWriter struct{}

func (w androidLogWriter) Write(p []byte) (n int, err error) {
	_, _ = os.Stderr.Write(p)
	cs := C.CString(string(p))
	C.androidLogProxy(cs)
	C.free(unsafe.Pointer(cs))
	return len(p), nil
}

func initLogging(verbose bool) {
	logVerbose = verbose
	flags := 0
	out := androidLogWriter{}
	logInfo = log.New(out, "", flags)
	logWarn = log.New(out, "[WARN] ", flags)
	logError = log.New(out, "[ERROR] ", flags)
	if verbose {
		logDebug = log.New(out, "[DEBUG] ", flags)
	} else {
		logDebug = log.New(io.Discard, "", 0)
	}
	signal.Ignore(syscall.SIGPIPE)
}

// ---------------------------------------------------------------------------
// Cloudflare proxy domain decoding
// ---------------------------------------------------------------------------

var cfproxyEnc = []string{"virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com"}

func decodeCfDomain(s string) string {
	if !strings.HasSuffix(s, ".com") {
		return s
	}
	suffix := string([]byte{46, 99, 111, 46, 117, 107})
	p := s[:len(s)-4]
	n := 0
	for _, c := range p {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
			n++
		}
	}
	var result []byte
	for _, c := range []byte(p) {
		if c >= 'a' && c <= 'z' {
			result = append(result, byte((int(c-'a')-n%26+26)%26+'a'))
		} else if c >= 'A' && c <= 'Z' {
			result = append(result, byte((int(c-'A')-n%26+26)%26+'A'))
		} else {
			result = append(result, c)
		}
	}
	return string(result) + suffix
}

func normalizeCfDomain(s string) string {
	decoded := strings.ToLower(strings.TrimSpace(decodeCfDomain(s)))
	decoded = strings.TrimSuffix(decoded, ".")
	if decoded == "" || !strings.HasSuffix(decoded, ".co.uk") {
		return ""
	}
	return decoded
}

func defaultCfproxyDomains() []string {
	domains := make([]string, 0, len(cfproxyEnc))
	for _, enc := range cfproxyEnc {
		if domain := normalizeCfDomain(enc); domain != "" {
			domains = append(domains, domain)
		}
	}
	return domains
}

func mergeCfproxyDomains(lists ...[]string) []string {
	seen := make(map[string]struct{})
	merged := make([]string, 0)
	for _, list := range lists {
		for _, raw := range list {
			domain := normalizeCfDomain(raw)
			if domain == "" {
				continue
			}
			if _, ok := seen[domain]; ok {
				continue
			}
			seen[domain] = struct{}{}
			merged = append(merged, domain)
		}
	}
	return merged
}

func clearCfproxy429Cooldowns() {
	cfproxy429Mu.Lock()
	cfproxy429StateByDomain = make(map[string]cfproxy429State)
	cfproxy429Mu.Unlock()
}

func clearCfproxy429Cooldown(domain string) {
	domain = normalizeCfDomain(domain)
	if domain == "" {
		return
	}

	cfproxy429Mu.Lock()
	delete(cfproxy429StateByDomain, domain)
	cfproxy429Mu.Unlock()
}

func retryAfterDelay(err error) time.Duration {
	var wsErr *WsHandshakeError
	if !errors.As(err, &wsErr) || wsErr == nil {
		return 0
	}

	retryAfter := strings.TrimSpace(wsErr.Headers["retry-after"])
	if retryAfter == "" {
		return 0
	}

	if seconds, convErr := strconv.Atoi(retryAfter); convErr == nil && seconds > 0 {
		return time.Duration(seconds) * time.Second
	}

	if when, convErr := http.ParseTime(retryAfter); convErr == nil {
		if delay := time.Until(when); delay > 0 {
			return delay
		}
	}

	return 0
}

func nextCfproxy429CooldownDelay(prev cfproxy429State, retryAfter time.Duration) time.Duration {
	if retryAfter > 0 {
		if retryAfter > cfproxy429MaxCooldown {
			return cfproxy429MaxCooldown
		}
		return retryAfter
	}

	strikes := prev.strikes
	if prev.until.IsZero() || time.Since(prev.until) > cfproxy429MaxCooldown {
		strikes = 0
	}

	delay := cfproxy429Cooldown
	for i := 0; i < strikes; i++ {
		delay *= 2
		if delay >= cfproxy429MaxCooldown {
			return cfproxy429MaxCooldown
		}
	}

	if delay > cfproxy429MaxCooldown {
		return cfproxy429MaxCooldown
	}
	return delay
}

func markCfproxy429Cooldown(domain string, err error) {
	domain = normalizeCfDomain(domain)
	if domain == "" {
		return
	}

	retryAfter := retryAfterDelay(err)
	cfproxy429Mu.Lock()
	prev := cfproxy429StateByDomain[domain]
	delay := nextCfproxy429CooldownDelay(prev, retryAfter)
	strikes := prev.strikes + 1
	if prev.until.IsZero() || time.Since(prev.until) > cfproxy429MaxCooldown {
		strikes = 1
	}
	cfproxy429StateByDomain[domain] = cfproxy429State{
		until:   time.Now().Add(delay),
		strikes: strikes,
	}
	cfproxy429Mu.Unlock()

	logDebug.Printf(" CF cooldown %s: %.0fs after 429", domain, math.Ceil(delay.Seconds()))
}

func cfproxy429CooldownRemaining(domain string) time.Duration {
	domain = normalizeCfDomain(domain)
	if domain == "" {
		return 0
	}

	cfproxy429Mu.RLock()
	state, ok := cfproxy429StateByDomain[domain]
	cfproxy429Mu.RUnlock()
	if !ok {
		return 0
	}

	remaining := time.Until(state.until)
	if remaining <= 0 {
		cfproxy429Mu.Lock()
		delete(cfproxy429StateByDomain, domain)
		cfproxy429Mu.Unlock()
		return 0
	}
	return remaining
}

func acquireCfproxyAttemptSlot(ctx context.Context) bool {
	select {
	case cfproxyAttemptSem <- struct{}{}:
		return true
	case <-ctx.Done():
		return false
	}
}

func releaseCfproxyAttemptSlot() {
	select {
	case <-cfproxyAttemptSem:
	default:
	}
}

func cfproxyCachePath() string {
	cfproxyMu.RLock()
	cacheDir := strings.TrimSpace(cfproxyCacheDir)
	cfproxyMu.RUnlock()
	if cacheDir == "" {
		return ""
	}
	return filepath.Join(cacheDir, cfproxyCacheFileName)
}

func cfproxyActiveDomainPath() string {
	cfproxyMu.RLock()
	cacheDir := strings.TrimSpace(cfproxyCacheDir)
	cfproxyMu.RUnlock()
	if cacheDir == "" {
		return ""
	}
	return filepath.Join(cacheDir, cfproxyActiveFileName)
}

func loadCfproxyDomainsFromCache() []string {
	cachePath := cfproxyCachePath()
	if cachePath == "" {
		return nil
	}

	data, err := os.ReadFile(cachePath)
	if err != nil {
		return nil
	}

	return mergeCfproxyDomains(strings.Split(string(data), "\n"))
}

func loadActiveCfproxyDomain() string {
	activePath := cfproxyActiveDomainPath()
	if activePath == "" {
		return ""
	}

	data, err := os.ReadFile(activePath)
	if err != nil {
		return ""
	}
	return normalizeCfDomain(string(data))
}

func saveCfproxyDomainsToCache(domains []string) {
	cachePath := cfproxyCachePath()
	if cachePath == "" || len(domains) == 0 {
		return
	}

	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		logDebug.Printf(" CF: cache create failed: %s", err)
		return
	}

	data := strings.Join(domains, "\n")
	if err := os.WriteFile(cachePath, []byte(data), 0o644); err != nil {
		logDebug.Printf(" CF: cache save failed: %s", err)
	}
}

func saveActiveCfproxyDomain(domain string) {
	activePath := cfproxyActiveDomainPath()
	domain = normalizeCfDomain(domain)
	if activePath == "" || domain == "" {
		return
	}

	if err := os.MkdirAll(filepath.Dir(activePath), 0o755); err != nil {
		logDebug.Printf(" CF: active-domain cache create failed: %s", err)
		return
	}

	if err := os.WriteFile(activePath, []byte(domain), 0o644); err != nil {
		logDebug.Printf(" CF: active-domain cache save failed: %s", err)
	}
}

func shouldRefreshCfproxyDomains() bool {
	cachePath := cfproxyCachePath()
	if cachePath == "" {
		return true
	}

	info, err := os.Stat(cachePath)
	if err != nil {
		return true
	}

	return time.Since(info.ModTime()) >= cfproxyRefreshInterval
}

func setActiveCfproxyDomainLocked(preferred string) {
	if len(cfproxyDomains) == 0 {
		activeCfDomain = ""
		return
	}
	preferred = normalizeCfDomain(preferred)
	for _, domain := range cfproxyDomains {
		if domain == preferred {
			activeCfDomain = domain
			return
		}
	}
	activeCfDomain = cfproxyDomains[0]
}

func initCfproxyDomains() {
	defaults := defaultCfproxyDomains()
	cached := loadCfproxyDomainsFromCache()
	persistedActive := loadActiveCfproxyDomain()

	cfproxyMu.Lock()
	defer cfproxyMu.Unlock()
	if cfproxyUserDomain != "" {
		cfproxyDomains = []string{cfproxyUserDomain}
		activeCfDomain = cfproxyUserDomain
		return
	}

	if len(cached) > 0 {
		cfproxyDomains = mergeCfproxyDomains(cached, defaults)
		logInfo.Printf(" CF: loaded %d domains from cache", len(cached))
	} else {
		cfproxyDomains = defaults
	}
	setActiveCfproxyDomainLocked(persistedActive)
}

func startCfproxyRefresh(ctx context.Context) {
	if !shouldRefreshCfproxyDomains() {
		logDebug.Printf(" CF: cache is fresh, skipping refresh")
		return
	}

	go func() {
		for i := 0; i < 3; i++ {
			if tryRefreshCfproxyDomains(ctx) {
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(10 * time.Second):
				continue
			}
		}
		logDebug.Printf(" CF: failed to refresh domain list, using cache/builtin")
	}()
}

func tryRefreshCfproxyDomains(ctx context.Context) bool {
	cfproxyMu.RLock()
	hasUserDomain := cfproxyUserDomain != ""
	cfproxyMu.RUnlock()
	if hasUserDomain {
		return true
	}

	req, err := http.NewRequestWithContext(ctx, "GET", cfproxyDomainsURL, nil)
	if err != nil {
		return false
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 tg-ws-proxy-android")

	resp, err := githubClient.Do(req)
	if err != nil {
		logDebug.Printf(" CF: GitHub unavailable: %s", err)
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		logDebug.Printf(" CF: GitHub returned %d", resp.StatusCode)
		return false
	}

	var newDomains []string
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if domain := normalizeCfDomain(line); domain != "" {
			newDomains = append(newDomains, domain)
		}
	}
	if err := scanner.Err(); err != nil {
		logDebug.Printf(" CF: failed to read domain list: %s", err)
		return false
	}

	if len(newDomains) > 0 {
		merged := mergeCfproxyDomains(newDomains, defaultCfproxyDomains())
		cfproxyMu.Lock()
		if cfproxyUserDomain != "" {
			cfproxyMu.Unlock()
			return true
		}
		currentActive := activeCfDomain
		cfproxyDomains = merged
		setActiveCfproxyDomainLocked(currentActive)
		cfproxyMu.Unlock()

		saveCfproxyDomainsToCache(merged)
		logInfo.Printf(" CF: domain list updated (%d new)", len(newDomains))
		return true
	}
	return false
}

// ---------------------------------------------------------------------------
// Telegram IP ranges & DC mapping
// ---------------------------------------------------------------------------

var validProtos = map[uint32]bool{
	0xEFEFEFEF: true,
	0xEEEEEEEE: true,
	0xDDDDDDDD: true,
}

var dcOverrides = map[int]int{
	203: 2,
}

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

var (
	dcOpt   map[int]string
	dcOptMu sync.RWMutex

	wsBlackMu   sync.RWMutex
	wsBlacklist = make(map[[2]int]bool)

	dcFailMu      sync.RWMutex
	dcFailUntil   = make(map[[2]int]float64)
	dcFailStrikes = make(map[[2]int]int) // exponential backoff strike counter

	zero64 = make([]byte, 64)
)

// ---------------------------------------------------------------------------
// Pool slot registry — tracks which DC slots have been used so the background
// maintainer can proactively refill them without waiting for a Get() call.
// ---------------------------------------------------------------------------

type poolSlotInfo struct {
	targetIP string
	domains  []string
}

var poolKnownSlots sync.Map // map[dcSlot]poolSlotInfo

func registerPoolSlot(slot dcSlot, targetIP string, domains []string) {
	poolKnownSlots.Store(slot, poolSlotInfo{targetIP: targetIP, domains: domains})
}

// startPoolMaintainer runs a periodic goroutine that proactively refills all
// known DC pool slots. This ensures connections are ready before peak-hour bursts
// and recovers from silent connection deaths faster than lazy Get() refills.
func startPoolMaintainer(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(poolMaintainInterval * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				poolKnownSlots.Range(func(k, v interface{}) bool {
					slot := k.(dcSlot)
					info := v.(poolSlotInfo)
					q, s := wsPool.getQueue(slot)
					needed := int(poolSize.Load()) - len(q)
					if needed > 0 && s.CompareAndSwap(0, 1) {
						go wsPool.refill(ctx, slot, q, s, info.targetIP, info.domains)
					}
					return true
				})
			}
		}
	}()
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

type Stats struct {
	connectionsTotal       atomic.Int64
	connectionsActive      atomic.Int64
	connectionsWs          atomic.Int64
	connectionsTcpFallback atomic.Int64
	connectionsCfproxy     atomic.Int64
	connectionsHttpReject  atomic.Int64
	connectionsPassthrough atomic.Int64
	connectionsBad         atomic.Int64
	wsErrors               atomic.Int64
	bytesUp                atomic.Int64
	bytesDown              atomic.Int64
	poolHits               atomic.Int64
	poolMisses             atomic.Int64
}

func (s *Stats) Summary() string {
	ph := s.poolHits.Load()
	pm := s.poolMisses.Load()
	return fmt.Sprintf(
		"total=%d active=%d ws=%d tcp_fb=%d cf=%d bad=%d err=%d pool=%d/%d up=%s down=%s",
		s.connectionsTotal.Load(), s.connectionsActive.Load(), s.connectionsWs.Load(),
		s.connectionsTcpFallback.Load(), s.connectionsCfproxy.Load(), s.connectionsBad.Load(),
		s.wsErrors.Load(), ph, ph+pm, humanBytes(s.bytesUp.Load()), humanBytes(s.bytesDown.Load()),
	)
}

func (s *Stats) SummaryRu() string {
	parts := []string{fmt.Sprintf("act:%d", s.connectionsActive.Load())}
	if ws := s.connectionsWs.Load(); ws > 0 {
		parts = append(parts, fmt.Sprintf("ws:%d", ws))
	}
	if cf := s.connectionsCfproxy.Load(); cf > 0 {
		parts = append(parts, fmt.Sprintf("cf:%d", cf))
	}
	if tcp := s.connectionsTcpFallback.Load(); tcp > 0 {
		parts = append(parts, fmt.Sprintf("tcp:%d", tcp))
	}
	if errCount := s.wsErrors.Load(); errCount > 0 {
		parts = append(parts, fmt.Sprintf("err:%d", errCount))
	}
	parts = append(parts, fmt.Sprintf("up:%s down:%s", humanBytes(s.bytesUp.Load()), humanBytes(s.bytesDown.Load())))
	return strings.Join(parts, " | ")
}

func (s *Stats) Reset() {
	s.connectionsTotal.Store(0)
	s.connectionsActive.Store(0)
	s.connectionsWs.Store(0)
	s.connectionsTcpFallback.Store(0)
	s.connectionsCfproxy.Store(0)
	s.connectionsHttpReject.Store(0)
	s.connectionsPassthrough.Store(0)
	s.connectionsBad.Store(0)
	s.wsErrors.Store(0)
	s.bytesUp.Store(0)
	s.bytesDown.Store(0)
	s.poolHits.Store(0)
	s.poolMisses.Store(0)
}

var stats Stats

func humanBytes(n int64) string {
	units := []string{"B", "KB", "MB", "GB", "TB"}
	f := float64(n)
	for i, u := range units {
		if math.Abs(f) < 1024 || i == len(units)-1 {
			return fmt.Sprintf("%.1f%s", f, u)
		}
		f /= 1024
	}
	return fmt.Sprintf("%.1f%s", f, "TB")
}

// ---------------------------------------------------------------------------
// Socket helpers
// ---------------------------------------------------------------------------

func setSockOpts(conn net.Conn) {
	if tc, ok := conn.(*net.TCPConn); ok {
		if tcpNodelay {
			_ = tc.SetNoDelay(true)
		}
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(15 * time.Second)
		_ = tc.SetReadBuffer(recvBuf)
		_ = tc.SetWriteBuffer(sendBuf)
	}
}

// ---------------------------------------------------------------------------
// XOR mask
// ---------------------------------------------------------------------------

func xorMaskInPlace(data, mask []byte) {
	n := len(data)
	if n == 0 {
		return
	}
	mask8 := uint64(mask[0]) | uint64(mask[1])<<8 | uint64(mask[2])<<16 | uint64(mask[3])<<24 |
		uint64(mask[0])<<32 | uint64(mask[1])<<40 | uint64(mask[2])<<48 | uint64(mask[3])<<56

	i := 0
	for ; i+8 <= n; i += 8 {
		v := binary.LittleEndian.Uint64(data[i:])
		binary.LittleEndian.PutUint64(data[i:], v^mask8)
	}
	for ; i < n; i++ {
		data[i] ^= mask[i&3]
	}
}

// ---------------------------------------------------------------------------
// RawWebSocket
// ---------------------------------------------------------------------------

var bytesPool = sync.Pool{
	New: func() any { return make([]byte, 131072) },
}

func SafeClose(conn net.Conn) {
	if conn == nil {
		return
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetLinger(0)
	}
	_ = conn.Close()
}

// wsUserAgents rotates realistic browser User-Agent strings across connections.
// Combined with Origin and Accept-Language headers this makes WS upgrades
// look indistinguishable from ordinary browser traffic to TSPU/DPI.
var wsUserAgents = []string{
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
	"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
}

func randomUserAgent() string {
	return wsUserAgents[int(time.Now().UnixNano()>>10)%len(wsUserAgents)]
}

// findSNISplitPos parses a TLS ClientHello packet and returns the byte offset
// inside the SNI hostname where the packet should be split into two TCP segments.
// Splitting directly inside the domain name (e.g. "mus" | "ic.youtube.com") prevents
// Russian TSPU (DPI) pattern-matchers from inspecting or blocking the target SNI.
// Returns 0 if not a TLS ClientHello or if SNI is not found.
func findSNISplitPos(p []byte) int {
	if len(p) < 44 || p[0] != 0x16 {
		return 0
	}
	// Handshake type: 0x01 (ClientHello)
	if p[5] != 0x01 {
		return 0
	}
	// Handshake header: type(1) + length(3) + version(2) + random(32) = 38 bytes
	// Session ID length byte is at offset 5 + 38 = 43
	idx := 43
	if idx >= len(p) {
		return 0
	}
	sessionIDLen := int(p[idx])
	idx += 1 + sessionIDLen
	if idx+2 > len(p) {
		return 0
	}
	// Cipher suites length (2 bytes)
	csLen := int(binary.BigEndian.Uint16(p[idx:]))
	idx += 2 + csLen
	if idx+1 > len(p) {
		return 0
	}
	// Compression methods length (1 byte)
	cmLen := int(p[idx])
	idx += 1 + cmLen
	if idx+2 > len(p) {
		return 0
	}
	// Extensions length (2 bytes)
	extsLen := int(binary.BigEndian.Uint16(p[idx:]))
	idx += 2
	end := idx + extsLen
	if end > len(p) {
		end = len(p)
	}

	for idx+4 <= end {
		extType := binary.BigEndian.Uint16(p[idx:])
		extLen := int(binary.BigEndian.Uint16(p[idx+2:]))
		idx += 4
		if extType == 0x0000 { // server_name (SNI) extension
			// Server name list length: 2 bytes
			// Server name type: 1 byte (0x00 = host_name)
			// Host name length: 2 bytes
			// Host name starts at idx + 5
			if idx+5 <= end && extLen >= 5 {
				nameType := p[idx+2]
				if nameType == 0x00 {
					hnLen := int(binary.BigEndian.Uint16(p[idx+3:]))
					hnStart := idx + 5
					if hnStart+hnLen <= end && hnLen > 3 {
						// Split after 3 characters into the SNI domain name (e.g. "mus" | "ic.youtube.com")
						return hnStart + 3
					}
				}
			}
			break
		}
		idx += extLen
	}
	return 0
}

// splitWriteConn implements TLS Record Fragmentation (RFC 5246 / RFC 8446)
// combined with TCP segment splitting for the TLS ClientHello.
// It splits the handshake into two valid TLS records and sends them in separate TCP segments.
// DPI systems (TSPU) that inspect single TLS records or single TCP packets cannot detect
// the forbidden SNI, while Google's server (BoringSSL) natively reassembles fragmented TLS records.
type splitWriteConn struct {
	net.Conn
	splitDone atomic.Bool
	hdrBuf    []byte
}

func (c *splitWriteConn) Write(p []byte) (int, error) {
	if !c.splitDone.Load() {
		// Handle clients that send the 5-byte TLS record header separately
		if len(p) == 5 && p[0] == 0x16 {
			c.hdrBuf = make([]byte, 5)
			copy(c.hdrBuf, p)
			return 5, nil
		}

		var full []byte
		if len(c.hdrBuf) == 5 {
			full = append(c.hdrBuf, p...)
			c.hdrBuf = nil
		} else if len(p) >= 6 && p[0] == 0x16 {
			full = p
		}

		if len(full) >= 10 && full[0] == 0x16 && full[5] == 0x01 {
			c.splitDone.Store(true)

			handshake := full[5:]
			splitPos := findSNISplitPos(full)
			if splitPos <= 5 || splitPos >= len(full) {
				splitPos = 40
			} else {
				splitPos -= 5
			}

			// Record 1
			rec1 := make([]byte, 5+splitPos)
			rec1[0] = 0x16
			rec1[1] = full[1]
			rec1[2] = full[2]
			binary.BigEndian.PutUint16(rec1[3:5], uint16(splitPos))
			copy(rec1[5:], handshake[:splitPos])

			// Record 2
			remLen := len(handshake) - splitPos
			rec2 := make([]byte, 5+remLen)
			rec2[0] = 0x16
			rec2[1] = full[1]
			rec2[2] = full[2]
			binary.BigEndian.PutUint16(rec2[3:5], uint16(remLen))
			copy(rec2[5:], handshake[splitPos:])

			n1, err := c.Conn.Write(rec1)
			if err != nil {
				return n1, err
			}
			time.Sleep(5 * time.Millisecond) // Ensure distinct TCP segment
			_, err = c.Conn.Write(rec2)
			if err != nil {
				return n1, err
			}
			return len(p), nil
		} else if len(full) > 0 {
			c.splitDone.Store(true)
			splitPos := 3
			if splitPos > len(full) {
				splitPos = len(full)
			}
			n1, err := c.Conn.Write(full[:splitPos])
			if err != nil {
				return n1, err
			}
			time.Sleep(3 * time.Millisecond)
			n2, err := c.Conn.Write(full[splitPos:])
			if err != nil {
				return n1 + n2, err
			}
			return len(p), nil
		}
	}
	return c.Conn.Write(p)
}

// tlsConfigPool is the shared TLS base config.
// Cipher suites and curve preferences mirror Chrome 131 to reduce the JA3
// fingerprint distance from a real browser (Go's default order is distinctive).
var tlsConfigPool = &tls.Config{
	ClientSessionCache: tls.NewLRUClientSessionCache(256),
	CipherSuites: []uint16{
		tls.TLS_AES_128_GCM_SHA256,
		tls.TLS_AES_256_GCM_SHA384,
		tls.TLS_CHACHA20_POLY1305_SHA256,
		tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
		tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
		tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
		tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
		tls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
		tls.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
	},
	CurvePreferences: []tls.CurveID{
		tls.X25519,
		tls.CurveP256,
		tls.CurveP384,
	},
	NextProtos: []string{"http/1.1"}, // WS upgrade needs HTTP/1.1
	MinVersion: tls.VersionTLS12,
}

const (
	opText   = 0x1
	opBinary = 0x2
	opClose  = 0x8
	opPing   = 0x9
	opPong   = 0xA
)

type WsHandshakeError struct {
	StatusCode int
	StatusLine string
	Headers    map[string]string
	Location   string
}

func (e *WsHandshakeError) Error() string {
	return fmt.Sprintf("HTTP %d: %s", e.StatusCode, e.StatusLine)
}
func (e *WsHandshakeError) IsRedirect() bool {
	switch e.StatusCode {
	case 301, 302, 303, 307, 308:
		return true
	}
	return false
}

type RawWebSocket struct {
	conn      net.Conn
	bufReader *bufio.Reader
	writeMu   sync.Mutex
	closed    atomic.Bool
}

type dohResponse struct {
	Answer []struct {
		Data string `json:"data"`
		Type int    `json:"type"`
	} `json:"Answer"`
}

func pickPreferredIP(candidates []string) string {
	var fallbackV6 string
	for _, candidate := range candidates {
		ip := net.ParseIP(strings.TrimSpace(candidate))
		if ip == nil {
			continue
		}
		if ip4 := ip.To4(); ip4 != nil {
			return ip4.String()
		}
		if fallbackV6 == "" {
			fallbackV6 = ip.String()
		}
	}
	return fallbackV6
}

func resolveDoH(ctx context.Context, domain string) string {
	if val, ok := dohCache.Load(domain); ok {
		entry := val.(dohCacheEntry)
		if time.Now().Before(entry.exp) {
			return entry.ip
		}
	}

	resCh := make(chan string, 10)
	dnsCtx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()

	udpServers := []string{
		"1.1.1.1:53",
		"8.8.8.8:53",
		"77.88.8.8:53",
		"1.0.0.1:53",
		"8.8.4.4:53",
		"9.9.9.9:53",
	}
	for _, srv := range udpServers {
		go func(s string) {
			r := &net.Resolver{
				PreferGo: true,
				Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
					d := net.Dialer{Timeout: 800 * time.Millisecond}
					return d.DialContext(ctx, "udp", s)
				},
			}
			ips, err := r.LookupHost(dnsCtx, domain)
			if preferred := pickPreferredIP(ips); err == nil && preferred != "" {
				select {
				case resCh <- preferred:
				default:
				}
			}
		}(srv)
	}

	endpoints := []string{
		"https://cloudflare-dns.com/dns-query",
		"https://dns.google/dns-query",
		"https://dns.quad9.net/dns-query",
		"https://dns.adguard-dns.com/dns-query",
		"https://doh.opendns.com/dns-query",
		"https://doh.sb/dns-query",
	}

	for _, url := range endpoints {
		go func(u string) {
			fullURL := fmt.Sprintf("%s?name=%s&type=A", u, domain)
			req, err := http.NewRequestWithContext(dnsCtx, "GET", fullURL, nil)
			if err != nil {
				return
			}
			req.Header.Set("Accept", "application/dns-json")
			resp, err := dohClient.Do(req)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode != 200 {
				return
			}
			var r dohResponse
			if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
				return
			}
			for _, ans := range r.Answer {
				if ans.Type == 1 {
					select {
					case resCh <- ans.Data:
					default:
					}
					return
				}
			}
		}(url)
	}

	select {
	case ip := <-resCh:
		dohCache.Store(domain, dohCacheEntry{ip: ip, exp: time.Now().Add(20 * time.Minute)})
		return ip
	case <-dnsCtx.Done():
		return ""
	}
}

func wsConnectTimeout(timeout float64) time.Duration {
	if timeout <= 0 {
		return 5 * time.Second
	}
	return time.Duration(timeout * float64(time.Second))
}

func wsHandshakeTimeout(total time.Duration) time.Duration {
	if total <= 0 {
		return 3 * time.Second
	}
	if total > 3*time.Second {
		return 3 * time.Second
	}
	return total
}

func contextRemainingTimeout(ctx context.Context, fallback time.Duration) time.Duration {
	if deadline, ok := ctx.Deadline(); ok {
		remaining := time.Until(deadline)
		if remaining > 0 {
			return remaining
		}
		return time.Millisecond
	}
	return fallback
}

func newTimedAttemptContext(parent context.Context, timeout time.Duration) (context.Context, context.CancelFunc, time.Duration) {
	effective := timeout
	if effective <= 0 {
		effective = 5 * time.Second
	}
	if deadline, ok := parent.Deadline(); ok {
		if remaining := time.Until(deadline); remaining > 0 && remaining < effective {
			effective = remaining
		}
	}
	ctx, cancel := context.WithTimeout(parent, effective)
	return ctx, cancel, effective
}

func compactConnError(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, context.Canceled) {
		return "canceled"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	var wsErr *WsHandshakeError
	if errors.As(err, &wsErr) {
		return fmt.Sprintf("http %d", wsErr.StatusCode)
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "timeout"
	}
	return err.Error()
}

func isHTTPStatusError(err error, statusCode int) bool {
	var wsErr *WsHandshakeError
	return errors.As(err, &wsErr) && wsErr.StatusCode == statusCode
}

func logCfConnError(format string, err error, args ...any) {
	if isHTTPStatusError(err, http.StatusTooManyRequests) {
		logWarn.Printf(format, args...)
		return
	}
	logError.Printf(format, args...)
}

// The same verified configuration is exercised by the host TLS regression tests.
func verifiedWsTLSConfig(domain string) *tls.Config {
    config := tlsConfigPool.Clone()
    config.ServerName = domain
    config.InsecureSkipVerify = false
    return config
}

func wsConnectOnce(ctx context.Context, dialAddr, domain, path string, timeout time.Duration) (*RawWebSocket, error) {
	if dialAddr == "" {
		return nil, fmt.Errorf("empty dial address")
	}

	dialer := &net.Dialer{
		Timeout: timeout,
	}

	tlsCfg := verifiedWsTLSConfig(domain)

	targetAddr := net.JoinHostPort(dialAddr, "443")
	rawConn, err := dialer.DialContext(ctx, "tcp", targetAddr)
	if err != nil {
		return nil, err
	}

    connection := rawConn
    stopDial := context.AfterFunc(ctx, func() { connection.Close() })
    defer stopDial()
	setSockOpts(rawConn)

	// Wrap in splitWriteConn so the TLS ClientHello is sent across two TCP
	// segments. Most TSPU/DPI boxes match the complete ClientHello in a single
	// packet; splitting at byte 3 (after the TLS record header) defeats this.
	splitConn := &splitWriteConn{Conn: rawConn}

	tlsConn := tls.Client(splitConn, tlsCfg)
	handshakeTimeout := wsHandshakeTimeout(timeout)
	handshakeCtx, cancel := context.WithTimeout(ctx, handshakeTimeout)
	defer cancel()

	_ = tlsConn.SetDeadline(time.Now().Add(handshakeTimeout))
	if err := tlsConn.HandshakeContext(handshakeCtx); err != nil {
		rawConn.Close()
		logDebug.Printf(" ws tls fail %s via %s: %s", domain, dialAddr, compactConnError(err))
		return nil, err
	}
	_ = tlsConn.SetDeadline(time.Time{})
	rawConn = tlsConn

	wsKeyBytes := make([]byte, 16)
	_, _ = rand.Read(wsKeyBytes)
	wsKey := base64.StdEncoding.EncodeToString(wsKeyBytes)

	req := fmt.Sprintf(
		"GET %s HTTP/1.1\r\n"+
			"Host: %s\r\n"+
			"Upgrade: websocket\r\n"+
			"Connection: Upgrade\r\n"+
			"Sec-WebSocket-Key: %s\r\n"+
			"Sec-WebSocket-Version: 13\r\n"+
			"Sec-WebSocket-Protocol: binary\r\n"+
			"Origin: https://%s\r\n"+
			"User-Agent: %s\r\n"+
			"Accept-Encoding: gzip, deflate, br\r\n"+
			"Accept-Language: en-US,en;q=0.9\r\n"+
			"Cache-Control: no-cache\r\n"+
			"Pragma: no-cache\r\n\r\n",
		path, domain, wsKey, domain, randomUserAgent(),
	)

	_ = rawConn.SetWriteDeadline(time.Now().Add(timeout))
	if _, err = rawConn.Write([]byte(req)); err != nil {
		rawConn.Close()
		return nil, err
	}
	_ = rawConn.SetWriteDeadline(time.Time{})

	bufReader := bufio.NewReaderSize(rawConn, 64*1024)
	_ = rawConn.SetReadDeadline(time.Now().Add(timeout))

	var responseLines []string
	for {
		line, err := bufReader.ReadString('\n')
		if err != nil {
			rawConn.Close()
			return nil, err
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			break
		}
		responseLines = append(responseLines, line)
		if len(responseLines) > 100 {
			rawConn.Close()
			return nil, fmt.Errorf("too many HTTP headers")
		}
	}
	_ = rawConn.SetReadDeadline(time.Time{})

	if len(responseLines) == 0 {
		rawConn.Close()
		return nil, &WsHandshakeError{StatusCode: 0, StatusLine: "empty response"}
	}

	firstLine := responseLines[0]
	parts := strings.SplitN(firstLine, " ", 3)
	statusCode := 0
	if len(parts) >= 2 {
		statusCode, _ = strconv.Atoi(parts[1])
	}

	if statusCode == 101 {
		return &RawWebSocket{conn: rawConn, bufReader: bufReader}, nil
	}
	headers := make(map[string]string)
	for _, hl := range responseLines[1:] {
		if idx := strings.IndexByte(hl, ':'); idx >= 0 {
			headers[strings.TrimSpace(strings.ToLower(hl[:idx]))] = strings.TrimSpace(hl[idx+1:])
		}
	}
	rawConn.Close()
	return nil, &WsHandshakeError{
		StatusCode: statusCode,
		StatusLine: firstLine,
		Headers:    headers,
		Location:   headers["location"],
	}
}

func cfConnectDomain(ctx context.Context, domain, path string, timeout float64) (*RawWebSocket, string, error) {
	if path == "" {
		path = "/apiws"
	}

	attemptTimeout := wsConnectTimeout(timeout)
	phaseTimeout := attemptTimeout
	if phaseTimeout > cfproxyDialPhaseTimeout {
		phaseTimeout = cfproxyDialPhaseTimeout
	}

	// 1. Check cache first
	if val, ok := dohCache.Load(domain); ok {
		entry := val.(dohCacheEntry)
		if time.Now().Before(entry.exp) {
			resolvedIP := entry.ip
			ipCtx, cancelIP, ipTimeout := newTimedAttemptContext(ctx, phaseTimeout)
			ws, err := wsConnectOnce(ipCtx, resolvedIP, domain, path, ipTimeout)
			cancelIP()
			if err == nil {
				return ws, resolvedIP, nil
			}
		}
	}

	// 2. If not cached or cached dial failed, try direct dial and DoH resolve/dial in parallel
	type dialResult struct {
		ws         *RawWebSocket
		resolvedIP string
		err        error
	}

	resultCh := make(chan dialResult, 2)
	dialCtx, cancelDial := context.WithCancel(ctx)
	defer cancelDial()

	var wg sync.WaitGroup

	// Goroutine A: Direct dial
	wg.Add(1)
	go func() {
		defer wg.Done()
		hostCtx, cancelHost, hostTimeout := newTimedAttemptContext(dialCtx, phaseTimeout)
		ws, err := wsConnectOnce(hostCtx, domain, domain, path, hostTimeout)
		cancelHost()
		select {
		case resultCh <- dialResult{ws: ws, resolvedIP: "", err: err}:
		case <-dialCtx.Done():
			if ws != nil {
				ws.Close()
			}
		}
	}()

	// Goroutine B: DoH resolve & IP dial
	wg.Add(1)
	go func() {
		defer wg.Done()
		resolvedIP := resolveDoH(dialCtx, domain)
		if resolvedIP == "" {
			select {
			case resultCh <- dialResult{err: fmt.Errorf("DoH resolve failed")}:
			case <-dialCtx.Done():
			}
			return
		}
		ipCtx, cancelIP, ipTimeout := newTimedAttemptContext(dialCtx, phaseTimeout)
		ws, err := wsConnectOnce(ipCtx, resolvedIP, domain, path, ipTimeout)
		cancelIP()
		select {
		case resultCh <- dialResult{ws: ws, resolvedIP: resolvedIP, err: err}:
		case <-dialCtx.Done():
			if ws != nil {
				ws.Close()
			}
		}
	}()

	// Wait for results
	go func() {
		wg.Wait()
		close(resultCh)
	}()

	var lastErr error
	var finalIP string
	for res := range resultCh {
		if res.err == nil && res.ws != nil {
			cancelDial() // Stop the other attempt
			// Consume remaining to make sure they are closed
			go func() {
				for r := range resultCh {
					if r.ws != nil {
						r.ws.Close()
					}
				}
			}()
			return res.ws, res.resolvedIP, nil
		}
		if res.err != nil {
			lastErr = res.err
			if res.resolvedIP != "" {
				finalIP = res.resolvedIP
			}
		}
	}

	return nil, finalIP, lastErr
}

func wsConnect(ctx context.Context, ip, domain, path string, timeout float64) (*RawWebSocket, error) {
	if path == "" {
		path = "/apiws"
	}

	attemptTimeout := wsConnectTimeout(timeout)
	attemptCtx, cancel := context.WithTimeout(ctx, attemptTimeout)
	defer cancel()

	primaryAddr := strings.TrimSpace(ip)
	if primaryAddr == "" {
		primaryAddr = domain
	}

	ws, err := wsConnectOnce(attemptCtx, primaryAddr, domain, path, attemptTimeout)
	if err == nil {
		return ws, nil
	}

	if primaryAddr == domain && net.ParseIP(primaryAddr) == nil {
		if resolvedIP := resolveDoH(attemptCtx, domain); resolvedIP != "" && resolvedIP != primaryAddr {
			return wsConnectOnce(attemptCtx, resolvedIP, domain, path, attemptTimeout)
		}
	}

	return nil, err
}

func connectDirectWS(ctx context.Context, target string, domains []string, timeout float64) (*RawWebSocket, bool, bool) {
	return connectParallelWS(ctx, target, domains, timeout)
}

func (ws *RawWebSocket) writeFrame(frame []byte, timeout time.Duration) error {
	ws.writeMu.Lock()
	defer ws.writeMu.Unlock()
	defer recycleFrame(frame)

	if timeout > 0 {
		_ = ws.conn.SetWriteDeadline(time.Now().Add(timeout))
		defer ws.conn.SetWriteDeadline(time.Time{})
	}

	_, err := ws.conn.Write(frame)
	if err != nil {
		ws.closed.Store(true)
	}
	return err
}

func (ws *RawWebSocket) Send(data []byte) error {
	if ws.closed.Load() {
		return fmt.Errorf("WebSocket closed")
	}
	frame := ws.buildFrame(opBinary, data, true)
	return ws.writeFrame(frame, wsWriteTimeout)
}

func (ws *RawWebSocket) SendBatch(parts [][]byte) error {
	if ws.closed.Load() {
		return fmt.Errorf("WebSocket closed")
	}
	ws.writeMu.Lock()
	defer ws.writeMu.Unlock()
	_ = ws.conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout))
	defer ws.conn.SetWriteDeadline(time.Time{})
	for _, part := range parts {
		frame := ws.buildFrame(opBinary, part, true)
		if _, err := ws.conn.Write(frame); err != nil {
			recycleFrame(frame)
			ws.closed.Store(true)
			return err
		}
		recycleFrame(frame)
	}
	return nil
}

func (ws *RawWebSocket) SendPing() error {
	if ws.closed.Load() {
		return fmt.Errorf("WebSocket closed")
	}
	frame := ws.buildFrame(opPing, nil, true)
	return ws.writeFrame(frame, wsControlTimeout)
}

func (ws *RawWebSocket) probe(timeout time.Duration) error {
	if ws.closed.Load() {
		return fmt.Errorf("WebSocket closed")
	}
	if err := ws.SendPing(); err != nil {
		return err
	}
	_ = ws.conn.SetReadDeadline(time.Now().Add(timeout))
	defer ws.conn.SetReadDeadline(time.Time{})

	for !ws.closed.Load() {
		opcode, payload, err := ws.readFrame()
		if err != nil {
			ws.closed.Store(true)
			return err
		}
		switch opcode {
		case opPong:
			return nil
		case opPing:
			if err := ws.writeFrame(ws.buildFrame(opPong, payload, true), wsControlTimeout); err != nil {
				return err
			}
		case opClose:
			ws.closed.Store(true)
			return io.EOF
		default:
			return fmt.Errorf("unexpected frame %d during pool probe", opcode)
		}
	}
	return io.EOF
}

func (ws *RawWebSocket) Recv() ([]byte, error) {
	for !ws.closed.Load() {
		opcode, payload, err := ws.readFrame()
		if err != nil {
			ws.closed.Store(true)
			return nil, err
		}
		switch opcode {
		case opClose:
			ws.closed.Store(true)
			closePayload := payload
			if len(closePayload) > 2 {
				closePayload = closePayload[:2]
			}
			reply := ws.buildFrame(opClose, closePayload, true)
			_ = ws.writeFrame(reply, wsControlTimeout)
			if cap(payload) == 131072 {
				bytesPool.Put(payload[:cap(payload)])
			}
			return nil, io.EOF
		case opPing:
			pong := ws.buildFrame(opPong, payload, true)
			_ = ws.writeFrame(pong, wsControlTimeout)
			if cap(payload) == 131072 {
				bytesPool.Put(payload[:cap(payload)])
			}
			continue
		case opPong:
			if cap(payload) == 131072 {
				bytesPool.Put(payload[:cap(payload)])
			}
			continue
		case opText, opBinary:
			return payload, nil
		}
	}
	return nil, io.EOF
}

func (ws *RawWebSocket) Close() {
	if ws.closed.Swap(true) {
		return
	}
	frame := ws.buildFrame(opClose, nil, true)
	_ = ws.writeFrame(frame, wsControlTimeout)
	_ = ws.conn.Close()
}

var framePool = sync.Pool{
	New: func() any { return make([]byte, 0, pooledFrameCap) },
}

func recycleFrame(frame []byte) {
	if cap(frame) == pooledFrameCap {
		framePool.Put(frame[:0])
	}
}

func (ws *RawWebSocket) buildFrame(opcode int, data []byte, mask bool) []byte {
	length := len(data)
	fb := byte(0x80 | opcode)

	headerSize := 2
	if mask {
		headerSize += 4
	}
	if length >= 126 && length < 65536 {
		headerSize += 2
	} else if length >= 65536 {
		headerSize += 8
	}

	totalSize := headerSize + length
	var result []byte
	if totalSize <= pooledFrameCap {
		result = framePool.Get().([]byte)[:0]
	} else {
		result = make([]byte, 0, totalSize)
	}
	result = result[:totalSize]

	pos := 0
	result[pos] = fb
	pos++

	var maskKey [4]byte
	if mask {
		_, _ = rand.Read(maskKey[:])
	}

	if length < 126 {
		lb := byte(length)
		if mask {
			lb |= 0x80
		}
		result[pos] = lb
		pos++
	} else if length < 65536 {
		lb := byte(126)
		if mask {
			lb |= 0x80
		}
		result[pos] = lb
		pos++
		binary.BigEndian.PutUint16(result[pos:], uint16(length))
		pos += 2
	} else {
		lb := byte(127)
		if mask {
			lb |= 0x80
		}
		result[pos] = lb
		pos++
		binary.BigEndian.PutUint64(result[pos:], uint64(length))
		pos += 8
	}

	if mask {
		copy(result[pos:], maskKey[:])
		pos += 4
		payloadStart := pos
		copy(result[payloadStart:], data)
		xorMaskInPlace(result[payloadStart:payloadStart+length], maskKey[:])
	} else {
		copy(result[pos:], data)
	}
	return result
}

func (ws *RawWebSocket) readFrame() (int, []byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(ws.bufReader, hdr[:]); err != nil {
		return 0, nil, err
	}

	opcode := int(hdr[0] & 0x0F)
	length := uint64(hdr[1] & 0x7F)

	if length == 126 {
		var buf [2]byte
		if _, err := io.ReadFull(ws.bufReader, buf[:]); err != nil {
			return 0, nil, err
		}
		length = uint64(binary.BigEndian.Uint16(buf[:]))
	} else if length == 127 {
		var buf [8]byte
		if _, err := io.ReadFull(ws.bufReader, buf[:]); err != nil {
			return 0, nil, err
		}
		length = binary.BigEndian.Uint64(buf[:])
	}

	hasMask := (hdr[1] & 0x80) != 0
	var maskKey [4]byte
	if hasMask {
		if _, err := io.ReadFull(ws.bufReader, maskKey[:]); err != nil {
			return 0, nil, err
		}
	}

	const maxFramePayload = 16 * 1024 * 1024
	if length > maxFramePayload {
		return 0, nil, fmt.Errorf("frame too large: %d bytes", length)
	}
	var payload []byte
	if length <= 131072 {
		payload = bytesPool.Get().([]byte)[:length]
	} else {
		payload = make([]byte, length)
	}
	if length > 0 {
		if _, err := io.ReadFull(ws.bufReader, payload); err != nil {
			if cap(payload) == 131072 {
				bytesPool.Put(payload[:cap(payload)])
			}
			return 0, nil, err
		}
	}

	if hasMask {
		xorMaskInPlace(payload, maskKey[:])
	}

	return opcode, payload, nil
}

// ---------------------------------------------------------------------------
// Crypto & MTProto Splitter
// ---------------------------------------------------------------------------

type TrackedStream struct {
	key       []byte
	iv        []byte
	processed uint64
	stream    cipher.Stream
}

func newTrackedCTR(key, iv []byte) (*TrackedStream, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return &TrackedStream{
		key:       append([]byte(nil), key...),
		iv:        append([]byte(nil), iv...),
		processed: 0,
		stream:    cipher.NewCTR(block, iv),
	}, nil
}

func (t *TrackedStream) XORKeyStream(dst, src []byte) {
	t.stream.XORKeyStream(dst, src)
	t.processed += uint64(len(src))
}

func (t *TrackedStream) Clone() cipher.Stream {
	block, _ := aes.NewCipher(t.key)
	cloneStream := cipher.NewCTR(block, t.iv)
	tClone := &TrackedStream{
		key:       t.key,
		iv:        t.iv,
		processed: t.processed,
		stream:    cloneStream,
	}
	var dummy [16384]byte
	rem := t.processed
	for rem > 0 {
		n := rem
		if n > 16384 {
			n = 16384
		}
		tClone.stream.XORKeyStream(dummy[:n], dummy[:n])
		rem -= n
	}
	return tClone
}

func newAESCTR(key, iv []byte) (cipher.Stream, error) {
	return newTrackedCTR(key, iv)
}

const (
	protoAbridged           = 0
	protoIntermediate       = 1
	protoPaddedIntermediate = 2
)

type MsgSplitter struct {
	stream    cipher.Stream
	protoType int
	cipherBuf []byte
	plainBuf  []byte
	disabled  bool
}

func protoTagToType(proto uint32) int {
	switch proto {
	case 0xEEEEEEEE:
		return protoIntermediate
	case 0xDDDDDDDD:
		return protoPaddedIntermediate
	default:
		return protoAbridged
	}
}

func newMsgSplitter(initData []byte, proto uint32) (*MsgSplitter, error) {
	if len(initData) < 56 {
		return nil, fmt.Errorf("init data too short")
	}
	stream, err := newAESCTR(initData[8:40], initData[40:56])
	if err != nil {
		return nil, err
	}
	skip := make([]byte, 64)
	stream.XORKeyStream(skip, zero64)

	return &MsgSplitter{
		stream:    stream,
		protoType: protoTagToType(proto),
	}, nil
}

func (s *MsgSplitter) Split(chunk []byte) [][]byte {
	if len(chunk) == 0 {
		return nil
	}
	if s.disabled {
		return [][]byte{chunk}
	}

	s.cipherBuf = append(s.cipherBuf, chunk...)
	decrypted := make([]byte, len(chunk))
	s.stream.XORKeyStream(decrypted, chunk)
	s.plainBuf = append(s.plainBuf, decrypted...)

	var parts [][]byte
	for len(s.cipherBuf) > 0 {
		pktLen := s.nextPacketLen()
		if pktLen < 0 {
			break
		}
		if pktLen == 0 {
			parts = append(parts, append([]byte(nil), s.cipherBuf...))
			s.cipherBuf = nil
			s.plainBuf = nil
			s.disabled = true
			break
		}
		if len(s.cipherBuf) < pktLen {
			break
		}
		parts = append(parts, append([]byte(nil), s.cipherBuf[:pktLen]...))
		s.cipherBuf = s.cipherBuf[pktLen:]
		s.plainBuf = s.plainBuf[pktLen:]
	}

	if len(s.cipherBuf) == 0 {
		s.cipherBuf = nil
		s.plainBuf = nil
	}
	if len(parts) == 0 {
		return nil
	}
	return parts
}

func (s *MsgSplitter) Flush() [][]byte {
	if len(s.cipherBuf) == 0 {
		return nil
	}
	tail := append([]byte(nil), s.cipherBuf...)
	s.cipherBuf = nil
	s.plainBuf = nil
	return [][]byte{tail}
}

func (s *MsgSplitter) nextPacketLen() int {
	if len(s.plainBuf) == 0 {
		return -1
	}
	switch s.protoType {
	case protoAbridged:
		first := s.plainBuf[0] & 0x7F
		var headerLen, payloadLen int
		if first == 0x7F {
			if len(s.plainBuf) < 4 {
				return -1
			}
			payloadLen = int(uint32(s.plainBuf[1])|uint32(s.plainBuf[2])<<8|uint32(s.plainBuf[3])<<16) * 4
			headerLen = 4
		} else {
			payloadLen = int(first) * 4
			headerLen = 1
		}
		if payloadLen <= 0 {
			return 0
		}
		pktLen := headerLen + payloadLen
		if len(s.plainBuf) < pktLen {
			return -1
		}
		return pktLen

	case protoIntermediate, protoPaddedIntermediate:
		if len(s.plainBuf) < 4 {
			return -1
		}
		payloadLen := int(binary.LittleEndian.Uint32(s.plainBuf[:4]) & 0x7FFFFFFF)
		if payloadLen <= 0 {
			return 0
		}
		pktLen := 4 + payloadLen
		if len(s.plainBuf) < pktLen {
			return -1
		}
		return pktLen
	}
	return 0
}

// ---------------------------------------------------------------------------
// WsPool & Bridging
// ---------------------------------------------------------------------------

func wsDomains(dc int, isMedia bool) []string {
	effectiveDC := dc
	if override, ok := dcOverrides[dc]; ok {
		effectiveDC = override
	}

	if isMedia {
		return []string{
			fmt.Sprintf("kws%d-1.web.telegram.org", effectiveDC),
			fmt.Sprintf("kws%d.web.telegram.org", effectiveDC),
		}
	}
	return []string{
		fmt.Sprintf("kws%d.web.telegram.org", effectiveDC),
		fmt.Sprintf("kws%d-1.web.telegram.org", effectiveDC),
	}
}

type dcSlot struct {
	dc      int
	isMedia int
}

type poolEntry struct {
	ws      *RawWebSocket
	created int64
}

type WsPool struct {
    enqueueMu sync.Mutex
	queues sync.Map
	status sync.Map
}

func newWsPool() *WsPool { return &WsPool{} }

func (p *WsPool) getQueue(slot dcSlot) (chan *poolEntry, *atomic.Int32) {
	q, _ := p.queues.LoadOrStore(slot, make(chan *poolEntry, 16))
	s, _ := p.status.LoadOrStore(slot, &atomic.Int32{})
	return q.(chan *poolEntry), s.(*atomic.Int32)
}

func isMediaInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func isPoolEntryUsable(e *poolEntry, now int64) bool {
	if e == nil || e.ws == nil || e.ws.closed.Load() {
		return false
	}
	if now-e.created > int64(wsPoolReuseMaxAge) {
		return false
	}
	return true
}

func (p *WsPool) Get(ctx context.Context, dc int, isMedia bool, targetIP string, domains []string) *RawWebSocket {
	slot := dcSlot{dc, isMediaInt(isMedia)}
	q, s := p.getQueue(slot)
	now := time.Now().Unix()
	var ws *RawWebSocket

	for {
		select {
		case e := <-q:
			if !isPoolEntryUsable(e, now) {
				if e != nil && e.ws != nil {
					SafeClose(e.ws.conn)
				}
				continue
			}
			ws = e.ws
			stats.poolHits.Add(1)
		default:
			stats.poolMisses.Add(1)
		}
		break
	}

	if s.CompareAndSwap(0, 1) {
		go p.refill(ctx, slot, q, s, targetIP, domains)
	}
	return ws
}

func (p *WsPool) refill(ctx context.Context, slot dcSlot, q chan *poolEntry, s *atomic.Int32, targetIP string, domains []string) {
	defer s.Store(0)
	needed := int(poolSize.Load()) - len(q)
	if needed <= 0 {
		return
	}

	var wg sync.WaitGroup
	for i := 0; i < needed; i++ {
		select {
		case <-ctx.Done():
			return
		default:
		}
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			if idx > 0 {
				select {
				case <-ctx.Done():
					return
				case <-time.After(time.Duration(idx*50) * time.Millisecond):
				}
			}
			if ws := connectOneWS(ctx, targetIP, domains); ws != nil {
				p.enqueueMu.Lock()
                defer p.enqueueMu.Unlock()
                if ctx.Err() != nil { SafeClose(ws.conn); return }
				now := time.Now().Unix()
				select {
				case q <- &poolEntry{ws: ws, created: now}:
				case <-ctx.Done():
					SafeClose(ws.conn)
				default:
					SafeClose(ws.conn)
				}
			}
		}(i)
	}
	wg.Wait()
}

func (p *WsPool) Warmup(ctx context.Context, dcOptMap map[int]string) {
	for dc, targetIP := range dcOptMap {
		if targetIP == "" {
			continue
		}
		for _, isMedia := range []bool{false, true} {
			select {
			case <-ctx.Done():
				return
			default:
			}
			domains := wsDomains(dc, isMedia)
			slot := dcSlot{dc, isMediaInt(isMedia)}
			q, s := p.getQueue(slot)
			if s.CompareAndSwap(0, 1) {
				go p.refill(ctx, slot, q, s, targetIP, domains)
			}
		}
	}
}

func (p *WsPool) IdleCount() int {
	count := 0
	p.queues.Range(func(_, val interface{}) bool {
		count += len(val.(chan *poolEntry))
		return true
	})
	return count
}

func (p *WsPool) CloseAll() {
    p.enqueueMu.Lock()
    defer p.enqueueMu.Unlock()
	p.queues.Range(func(_, val interface{}) bool {
		q := val.(chan *poolEntry)
		for {
			select {
			case e := <-q:
				SafeClose(e.ws.conn)
			default:
				return true
			}
		}
	})
}

var wsPool = newWsPool()

func mediaTag(isMedia bool) string {
	if isMedia {
		return "m"
	}
	return ""
}

func isHTTPTransport(data []byte) bool {
	if len(data) < 4 {
		return false
	}
	s := string(data)
	return strings.HasPrefix(s, "CONNECT ") ||
		strings.HasPrefix(s, "GET ") ||
		strings.HasPrefix(s, "POST ") ||
		strings.HasPrefix(s, "HEAD ") ||
		strings.HasPrefix(s, "PUT ") ||
		strings.HasPrefix(s, "DELETE ") ||
		strings.HasPrefix(s, "OPTIONS ")
}

func handleHTTPProxy(ctx context.Context, conn net.Conn, handshake []byte) {
	reader := bufio.NewReader(io.MultiReader(bytes.NewReader(handshake), conn))

	firstLine, err := reader.ReadString('\n')
	if err != nil {
		return
	}
	firstLine = strings.TrimRight(firstLine, "\r\n")
	parts := strings.Split(firstLine, " ")
	if len(parts) < 2 {
		return
	}

	method := strings.ToUpper(parts[0])
	target := parts[1]

	var headers []string
	for {
		hdr, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		if hdr == "\r\n" || hdr == "\n" {
			break
		}
		trimmed := strings.TrimSpace(hdr)
		if !strings.HasPrefix(strings.ToLower(trimmed), "proxy-connection:") {
			headers = append(headers, hdr)
		}
	}

	if method == "CONNECT" {
		host, port, err := net.SplitHostPort(target)
		if err != nil {
			host = target
			port = "443"
		}
		tunnelToTarget(ctx, conn, host, port, target, func() error {
			_, err := conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
			return err
		}, reader)
	} else {
		handlePlainHTTP(ctx, conn, reader, method, target, parts, headers)
	}
}

var googleEdgeIPs = []string{
	"142.251.36.46",
	"172.217.18.110",
	"64.233.163.198",
	"142.250.74.206",
	"142.250.185.142",
	"172.217.16.206",
	"216.58.209.174",
	"172.217.168.206",
	"142.250.180.142",
}

func isGoogleHost(host string) bool {
	h := strings.ToLower(host)
	return strings.Contains(h, "youtube") ||
		strings.Contains(h, "googlevideo") ||
		strings.Contains(h, "ytimg") ||
		strings.Contains(h, "google") ||
		strings.Contains(h, "ggpht") ||
		strings.Contains(h, "gvt1")
}

func containsStr(slice []string, s string) bool {
	for _, v := range slice {
		if v == s {
			return true
		}
	}
	return false
}

func tunnelToTarget(ctx context.Context, conn net.Conn, host, port, target string, onConnectSuccess func() error, bufferedClientReader *bufio.Reader) {
	var candidateIPs []string
	if net.ParseIP(host) != nil {
		candidateIPs = append(candidateIPs, host)
	} else {
		if dohIP := resolveDoH(ctx, host); dohIP != "" {
			candidateIPs = append(candidateIPs, dohIP)
		}
		if ips, err := net.DefaultResolver.LookupHost(ctx, host); err == nil {
			for _, ip := range ips {
				if net.ParseIP(ip) != nil && !containsStr(candidateIPs, ip) {
					candidateIPs = append(candidateIPs, ip)
				}
			}
		}
		if isGoogleHost(host) {
			for _, gIP := range googleEdgeIPs {
				if !containsStr(candidateIPs, gIP) {
					candidateIPs = append(candidateIPs, gIP)
				}
			}
		}
	}
	if len(candidateIPs) == 0 {
		candidateIPs = append(candidateIPs, host)
	}

	var remoteConn net.Conn
	var dialErr error
	timeoutPerIP := 3500 * time.Millisecond
	if len(candidateIPs) == 1 {
		timeoutPerIP = 10 * time.Second
	}

	for _, ip := range candidateIPs {
		dialAddr := net.JoinHostPort(ip, port)
		dialer := &net.Dialer{Timeout: timeoutPerIP}
		c, err := dialer.DialContext(ctx, "tcp", dialAddr)
		if err == nil {
			remoteConn = c
			break
		}
		dialErr = err
	}

	if remoteConn == nil {
		logWarn.Printf(" Proxy dial to %s failed: %v", target, dialErr)
		return
	}
	defer remoteConn.Close()
	stopRemote := context.AfterFunc(ctx, func() { remoteConn.Close() })
	defer stopRemote()
	setSockOpts(remoteConn)

	wrappedRemote := &splitWriteConn{Conn: remoteConn}

	if onConnectSuccess != nil {
		if err := onConnectSuccess(); err != nil {
			return
		}
	}

	if bufferedClientReader != nil {
		if n := bufferedClientReader.Buffered(); n > 0 {
			buf := make([]byte, n)
			if _, err := io.ReadFull(bufferedClientReader, buf); err == nil {
				if _, err := wrappedRemote.Write(buf); err != nil {
					return
				}
			}
		}
	}

	_, cancelRelay := context.WithCancel(ctx)
	defer cancelRelay()

	var wg sync.WaitGroup
	wg.Add(2)

	// Client -> Remote
	go func() {
		defer wg.Done()
		defer cancelRelay()
		buf := make([]byte, 32*1024)
		for {
			_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, rerr := conn.Read(buf)
			if n > 0 {
				stats.bytesUp.Add(int64(n))
				_ = wrappedRemote.SetWriteDeadline(time.Now().Add(30 * time.Second))
				if _, werr := wrappedRemote.Write(buf[:n]); werr != nil {
					return
				}
			}
			if rerr != nil {
				return
			}
		}
	}()

	// Remote -> Client
	go func() {
		defer wg.Done()
		defer cancelRelay()
		buf := make([]byte, 32*1024)
		for {
			_ = remoteConn.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, rerr := remoteConn.Read(buf)
			if n > 0 {
				stats.bytesDown.Add(int64(n))
				_ = conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
				if _, werr := conn.Write(buf[:n]); werr != nil {
					return
				}
			}
			if rerr != nil {
				return
			}
		}
	}()

	wg.Wait()
}

func handlePlainHTTP(ctx context.Context, conn net.Conn, reader *bufio.Reader, method, target string, firstLineParts, headers []string) {
	var host, path string
	if strings.HasPrefix(target, "http://") || strings.HasPrefix(target, "https://") {
		u, err := url.Parse(target)
		if err == nil {
			host = u.Host
			path = u.RequestURI()
		}
	}
	if host == "" {
		for _, h := range headers {
			if strings.HasPrefix(strings.ToLower(h), "host:") {
				host = strings.TrimSpace(h[5:])
				break
			}
		}
		path = target
	}
	if host == "" {
		_, _ = conn.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
		return
	}

	targetHost, targetPort, err := net.SplitHostPort(host)
	if err != nil {
		targetHost = host
		targetPort = "80"
	}

	targetIP := targetHost
	if net.ParseIP(targetHost) == nil {
		if dohIP := resolveDoH(ctx, targetHost); dohIP != "" {
			targetIP = dohIP
		}
	}

	dialAddr := net.JoinHostPort(targetIP, targetPort)
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	remoteConn, err := dialer.DialContext(ctx, "tcp", dialAddr)
	if err != nil {
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer remoteConn.Close()
	stopRemote := context.AfterFunc(ctx, func() { remoteConn.Close() })
	defer stopRemote()
	setSockOpts(remoteConn)

	proto := "HTTP/1.1"
	if len(firstLineParts) >= 3 {
		proto = firstLineParts[2]
	}
	newFirstLine := fmt.Sprintf("%s %s %s\r\n", method, path, proto)
	if _, err := remoteConn.Write([]byte(newFirstLine)); err != nil {
		return
	}
	for _, h := range headers {
		if _, err := remoteConn.Write([]byte(h)); err != nil {
			return
		}
	}
	if _, err := remoteConn.Write([]byte("\r\n")); err != nil {
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(remoteConn, reader)
		_ = remoteConn.Close()
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(conn, remoteConn)
		_ = conn.Close()
	}()
	wg.Wait()
}

func handleSocks5(ctx context.Context, conn net.Conn) bool {
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	nmethodsBuf := make([]byte, 1)
	if _, err := io.ReadFull(conn, nmethodsBuf); err != nil {
		return false
	}
	nmethods := int(nmethodsBuf[0])
	if nmethods < 1 || nmethods > 10 {
		return false
	}
	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(conn, methods); err != nil {
		return false
	}
	_ = conn.SetReadDeadline(time.Time{})

	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return true
	}

	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	reqHead := make([]byte, 4)
	if _, err := io.ReadFull(conn, reqHead); err != nil {
		return true
	}
	_ = conn.SetReadDeadline(time.Time{})

	if reqHead[0] != 0x05 || reqHead[1] != 0x01 {
		_, _ = conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return true
	}

	var host string
	atyp := reqHead[3]
	switch atyp {
	case 0x01: // IPv4
		ipBuf := make([]byte, 4)
		if _, err := io.ReadFull(conn, ipBuf); err != nil {
			return true
		}
		host = net.IP(ipBuf).String()
	case 0x03: // Domain
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return true
		}
		domLen := int(lenBuf[0])
		domBuf := make([]byte, domLen)
		if _, err := io.ReadFull(conn, domBuf); err != nil {
			return true
		}
		host = string(domBuf)
	case 0x04: // IPv6
		ipBuf := make([]byte, 16)
		if _, err := io.ReadFull(conn, ipBuf); err != nil {
			return true
		}
		host = net.IP(ipBuf).String()
	default:
		_, _ = conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return true
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return true
	}
	port := binary.BigEndian.Uint16(portBuf)
	target := fmt.Sprintf("%s:%d", host, port)

	tunnelToTarget(ctx, conn, host, fmt.Sprintf("%d", port), target, func() error {
		_, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return err
	}, nil)
	return true
}

func bridgeWS(ctx context.Context, conn net.Conn, ws *RawWebSocket,
	label string, dc int, dst string, port int, isMedia bool,
	splitter *MsgSplitter, cltDec, cltEnc, tgEnc, tgDec cipher.Stream) {

	ctx2, cancel := context.WithCancel(ctx)
	defer cancel()

	go func() {
		<-ctx2.Done()
		SafeClose(conn)
		ws.Close()
	}()

	var wg sync.WaitGroup
	wg.Add(2)

	// WS keepalive: periodic ping to detect dead connections on mobile
	lastActivity := time.Now()
	var activityMu sync.Mutex

	go func() {
		ticker := time.NewTicker(bridgePingInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx2.Done():
				return
			case <-ticker.C:
				activityMu.Lock()
				idle := time.Since(lastActivity)
				activityMu.Unlock()
				if idle > bridgePingInterval {
					if err := ws.SendPing(); err != nil {
						cancel()
						return
					}
				}
			}
		}
	}()

	go func() {
		defer wg.Done()
		defer cancel()
		buf := bytesPool.Get().([]byte)
		defer bytesPool.Put(buf)
		readLimit := cap(buf)
		if readLimit > wsBridgeChunkSize {
			readLimit = wsBridgeChunkSize
		}
		for {
			_ = conn.SetReadDeadline(time.Now().Add(bridgeReadTimeout))
			n, err := conn.Read(buf[:readLimit])
			if n > 0 {
				chunk := buf[:n]
				stats.bytesUp.Add(int64(n))

				activityMu.Lock()
				lastActivity = time.Now()
				activityMu.Unlock()

				cltDec.XORKeyStream(chunk, chunk)
				tgEnc.XORKeyStream(chunk, chunk)

				var sendErr error
				if splitter != nil {
					parts := splitter.Split(chunk)
					if len(parts) > 1 {
						sendErr = ws.SendBatch(parts)
					} else if len(parts) == 1 {
						sendErr = ws.Send(parts[0])
					}
				} else {
					sendErr = ws.Send(chunk)
				}
				if sendErr != nil {
					return
				}
			}
			if err != nil {
				if splitter != nil {
					tail := splitter.Flush()
					if len(tail) > 0 {
						if len(tail) > 1 {
							if sendErr := ws.SendBatch(tail); sendErr != nil {
								return
							}
						} else {
							if sendErr := ws.Send(tail[0]); sendErr != nil {
								return
							}
						}
					}
				}
				return
			}
		}
	}()

	go func() {
		defer wg.Done()
		defer cancel()
		for {
			_ = ws.conn.SetReadDeadline(time.Now().Add(bridgeReadTimeout))
			data, err := ws.Recv()
			if err != nil || data == nil {
				return
			}
			n := len(data)
			stats.bytesDown.Add(int64(n))

			activityMu.Lock()
			lastActivity = time.Now()
			activityMu.Unlock()

			tgDec.XORKeyStream(data, data)
			cltEnc.XORKeyStream(data, data)
			_, werr := conn.Write(data)
			if cap(data) == 131072 {
				bytesPool.Put(data[:cap(data)])
			}
			if werr != nil {
				return
			}
		}
	}()

	wg.Wait()
}

func bridgeTCP(ctx context.Context, client, remote net.Conn,
	label string, dc int, dst string, port int, isMedia bool, cltDec, cltEnc, tgEnc, tgDec cipher.Stream) {

	ctx2, cancel := context.WithCancel(ctx)

	go func() {
		<-ctx2.Done()
		SafeClose(client)
		SafeClose(remote)
	}()

	var wg sync.WaitGroup
	wg.Add(2)

	forward := func(src, dstW net.Conn, isUp bool) {
		defer wg.Done()
		defer cancel()
		buf := bytesPool.Get().([]byte)
		defer bytesPool.Put(buf)
		for {
			_ = src.SetReadDeadline(time.Now().Add(bridgeReadTimeout))
			n, err := src.Read(buf[:cap(buf)])
			if n > 0 {
				chunk := buf[:n]
				if isUp {
					stats.bytesUp.Add(int64(n))
					cltDec.XORKeyStream(chunk, chunk)
					tgEnc.XORKeyStream(chunk, chunk)
				} else {
					stats.bytesDown.Add(int64(n))
					tgDec.XORKeyStream(chunk, chunk)
					cltEnc.XORKeyStream(chunk, chunk)
				}
				if _, werr := dstW.Write(chunk); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}

	go forward(client, remote, true)
	go forward(remote, client, false)

	wg.Wait()
}

func tcpFallback(ctx context.Context, client net.Conn, dst string, port int,
	init []byte, label string, dc int, isMedia bool, cltDec, cltEnc, tgEnc, tgDec cipher.Stream) bool {

	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 60 * time.Second,
	}
	remote, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(dst, strconv.Itoa(port)))
	if err != nil {
		return false
	}

	stats.connectionsTcpFallback.Add(1)
	logInfo.Printf(" DC%d%s connected via TCP", dc, mediaTag(isMedia))
	_, _ = remote.Write(init)
	bridgeTCP(ctx, client, remote, label, dc, dst, port, isMedia, cltDec, cltEnc, tgEnc, tgDec)
	return true
}

func tryCfproxyBaseDomain(ctx context.Context, dc int, baseDomain string) (*RawWebSocket, string) {
	baseDomain = normalizeCfDomain(baseDomain)
	if baseDomain == "" {
		return nil, ""
	}
	if remaining := cfproxy429CooldownRemaining(baseDomain); remaining > 0 {
		logDebug.Printf(" CF skip %s: 429 cooldown %.0fs", baseDomain, math.Ceil(remaining.Seconds()))
		return nil, ""
	}
	if !acquireCfproxyAttemptSlot(ctx) {
		return nil, ""
	}
	defer releaseCfproxyAttemptSlot()

	domain := fmt.Sprintf("kws%d.%s", dc, baseDomain)
	logDebug.Printf(" CF try %s", domain)

	ws, resolvedIP, err := cfConnectDomain(ctx, domain, "/apiws", 5)
	if err != nil {
		if ctx.Err() == nil && isHTTPStatusError(err, http.StatusTooManyRequests) {
			markCfproxy429Cooldown(baseDomain, err)
		}
		if ctx.Err() == nil {
			if resolvedIP != "" {
				logCfConnError(" CF fail %s via %s: %s", err, domain, resolvedIP, compactConnError(err))
			} else {
				logCfConnError(" CF fail %s: %s", err, domain, compactConnError(err))
			}
		}
		return nil, ""
	}

	clearCfproxy429Cooldown(baseDomain)
	if resolvedIP != "" {
		logDebug.Printf(" CF ok %s via %s", domain, resolvedIP)
	} else {
		logDebug.Printf(" CF ok %s via hostname", domain)
	}
	return ws, baseDomain
}

func cfproxyFallback(ctx context.Context, conn net.Conn, relayInit []byte, label string,
	dc int, isMedia bool, splitter *MsgSplitter,
	cltDec, cltEnc, tgEnc, tgDec cipher.Stream) bool {

	cfproxyMu.RLock()
	if !cfproxyEnabled || len(cfproxyDomains) == 0 {
		cfproxyMu.RUnlock()
		return false
	}
	active := activeCfDomain
	domains := make([]string, len(cfproxyDomains))
	copy(domains, cfproxyDomains)
	cfproxyMu.RUnlock()

	ordered := []string{active}
	for _, d := range domains {
		if d != active {
			ordered = append(ordered, d)
		}
	}

	mTag := mediaTag(isMedia)
	logDebug.Printf(" CF fallback DC%d%s: %d domain(s)", dc, mTag, len(ordered))

	var ws *RawWebSocket
	var chosenDomain string

	if len(ordered) > 0 && ordered[0] != "" {
		ws, chosenDomain = tryCfproxyBaseDomain(ctx, dc, ordered[0])
	}

	if ws == nil && len(ordered) > 1 {
		remainingDomains := ordered[1:]

		type wsResult struct {
			ws     *RawWebSocket
			domain string
		}
		attemptCtx, cancelAttempts := context.WithCancel(ctx)
		defer cancelAttempts()

		ch := make(chan wsResult, len(remainingDomains))
		sem := make(chan struct{}, cfproxyFallbackParallel)
		for _, baseDomain := range remainingDomains {
			go func(bd string) {
				select {
				case sem <- struct{}{}:
				case <-attemptCtx.Done():
					ch <- wsResult{}
					return
				}
				defer func() { <-sem }()

				nextWS, nextDomain := tryCfproxyBaseDomain(attemptCtx, dc, bd)
				if nextWS != nil {
					select {
					case ch <- wsResult{ws: nextWS, domain: nextDomain}:
					case <-attemptCtx.Done():
						go nextWS.Close()
						ch <- wsResult{}
					}
					return
				}
				ch <- wsResult{}
			}(baseDomain)
		}

		for i := 0; i < len(remainingDomains); i++ {
			r := <-ch
			if r.ws != nil && ws == nil {
				ws = r.ws
				chosenDomain = r.domain
				cancelAttempts()
				remaining := len(remainingDomains) - i - 1
				if remaining > 0 {
					go func(left int) {
						for j := 0; j < left; j++ {
							rr := <-ch
							if rr.ws != nil {
								go rr.ws.Close()
							}
						}
					}(remaining)
				}
				break
			} else if r.ws != nil {
				go r.ws.Close()
			}
		}
	}

	if ws == nil {
		logWarn.Printf(" CF fallback DC%d%s: all CF domains unavailable", dc, mTag)
		return false
	}

	if chosenDomain != "" && chosenDomain != active {
		cfproxyMu.Lock()
		activeCfDomain = chosenDomain
		cfproxyMu.Unlock()
		saveActiveCfproxyDomain(chosenDomain)
		logInfo.Printf(" CF domain  %s", chosenDomain)
	}

	stats.connectionsCfproxy.Add(1)
	logInfo.Printf(" DC%d%s connected via CF", dc, mTag)

	if err := ws.Send(relayInit); err != nil {
		ws.Close()
		return false
	}

	bridgeWS(ctx, conn, ws, label, dc, chosenDomain, 443, isMedia, splitter, cltDec, cltEnc, tgEnc, tgDec)
	return true
}

func doFallback(ctx context.Context, conn net.Conn, relayInit []byte, label string,
	dc int, isMedia bool, splitter *MsgSplitter,
	cltDec, cltEnc, tgEnc, tgDec cipher.Stream) bool {

	if t, ok := cltDec.(interface{ Clone() cipher.Stream }); ok {
		cltDec = t.Clone()
	}
	if t, ok := cltEnc.(interface{ Clone() cipher.Stream }); ok {
		cltEnc = t.Clone()
	}
	if t, ok := tgEnc.(interface{ Clone() cipher.Stream }); ok {
		tgEnc = t.Clone()
	}
	if t, ok := tgDec.(interface{ Clone() cipher.Stream }); ok {
		tgDec = t.Clone()
	}

	fallbackDst := resolveFallbackTarget(dc, isMedia)

	cfproxyMu.RLock()
	useCf := cfproxyEnabled
	cfproxyMu.RUnlock()

	if useCf {
		if cfproxyFallback(ctx, conn, relayInit, label, dc, isMedia, splitter, cltDec, cltEnc, tgEnc, tgDec) {
			return true
		}
	}

	if fallbackDst != "" {
		if tcpFallback(ctx, conn, fallbackDst, 443, relayInit, label, dc, isMedia, cltDec, cltEnc, tgEnc, tgDec) {
			return true
		}
	}

	return false
}

// ---------------------------------------------------------------------------
// Fake TLS support (ee-secret)
// ---------------------------------------------------------------------------

const (
	tlsRecordHandshake = 0x16
	tlsRecordCCS       = 0x14
	tlsRecordAppData   = 0x17
	clientRandomOffset = 11
	clientRandomLen    = 32
	sessionIdOffset    = 44
	sessionIdLen       = 32
	timestampTolerance = 120
)

func verifyClientHello(data, secret []byte) ([]byte, []byte, bool) {
	n := len(data)
	if n < 43 {
		return nil, nil, false
	}
	if data[0] != tlsRecordHandshake || data[5] != 0x01 {
		return nil, nil, false
	}

	clientRandom := make([]byte, clientRandomLen)
	copy(clientRandom, data[clientRandomOffset:clientRandomOffset+clientRandomLen])

	zeroed := make([]byte, n)
	copy(zeroed, data)
	for i := 0; i < clientRandomLen; i++ {
		zeroed[clientRandomOffset+i] = 0
	}

	mac := hmacSHA256(secret, zeroed)

	for i := 0; i < 28; i++ {
		if mac[i] != clientRandom[i] {
			return nil, nil, false
		}
	}

	tsXor := make([]byte, 4)
	for i := 0; i < 4; i++ {
		tsXor[i] = clientRandom[28+i] ^ mac[28+i]
	}
	timestamp := binary.LittleEndian.Uint32(tsXor)
	now := uint32(time.Now().Unix())
	diff := int64(now) - int64(timestamp)
	if diff < 0 {
		diff = -diff
	}
	if diff > timestampTolerance {
		return nil, nil, false
	}

	sessionId := make([]byte, sessionIdLen)
	if n >= sessionIdOffset+sessionIdLen && data[43] == 0x20 {
		copy(sessionId, data[sessionIdOffset:sessionIdOffset+sessionIdLen])
	}

	return clientRandom, sessionId, true
}

func hmacSHA256(key, data []byte) []byte {
	h := hmac.New(sha256.New, key)
	h.Write(data)
	return h.Sum(nil)
}

var serverHelloTemplate = []byte{
	0x16, 0x03, 0x03, 0x00, 0x7a, 0x02, 0x00, 0x00, 0x76, 0x03, 0x03,
	0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
	0x20,
	0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
	0x13, 0x01, 0x00, 0x00, 0x2e, 0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20,
	0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
	0x00, 0x2b, 0x00, 0x02, 0x03, 0x04,
}

func buildServerHello(secret, clientRandom, sessionId []byte) []byte {
	sh := make([]byte, len(serverHelloTemplate))
	copy(sh, serverHelloTemplate)
	copy(sh[44:44+32], sessionId)

	pubKey := make([]byte, 32)
	rand.Read(pubKey)
	copy(sh[89:89+32], pubKey)

	ccsFrame := []byte{0x14, 0x03, 0x03, 0x00, 0x01, 0x01}

	encSize := 1900 + int(time.Now().UnixNano()%200)
	encData := make([]byte, encSize)
	rand.Read(encData)
	appRecord := make([]byte, 5+encSize)
	appRecord[0] = 0x17
	appRecord[1] = 0x03
	appRecord[2] = 0x03
	binary.BigEndian.PutUint16(appRecord[3:5], uint16(encSize))
	copy(appRecord[5:], encData)

	response := make([]byte, 0, len(sh)+len(ccsFrame)+len(appRecord))
	response = append(response, sh...)
	response = append(response, ccsFrame...)
	response = append(response, appRecord...)

	hmacInput := make([]byte, 0, len(clientRandom)+len(response))
	hmacInput = append(hmacInput, clientRandom...)
	hmacInput = append(hmacInput, response...)
	serverRandom := hmacSHA256(secret, hmacInput)

	copy(response[11:11+32], serverRandom)

	return response
}

type FakeTlsConn struct {
	conn     net.Conn
	readLeft int
}

func newFakeTlsConn(conn net.Conn) *FakeTlsConn {
	return &FakeTlsConn{conn: conn}
}

func (f *FakeTlsConn) Read(p []byte) (int, error) {
	if f.readLeft > 0 {
		toRead := f.readLeft
		if toRead > len(p) {
			toRead = len(p)
		}
		n, err := f.conn.Read(p[:toRead])
		f.readLeft -= n
		return n, err
	}

	for {
		hdr := make([]byte, 5)
		if _, err := io.ReadFull(f.conn, hdr); err != nil {
			return 0, err
		}

		rtype := hdr[0]
		recLen := int(binary.BigEndian.Uint16(hdr[3:5]))

		if rtype == tlsRecordCCS {
			if recLen > 0 {
				discard := make([]byte, recLen)
				if _, err := io.ReadFull(f.conn, discard); err != nil {
					return 0, err
				}
			}
			continue
		}

		if rtype != tlsRecordAppData {
			return 0, fmt.Errorf("unexpected TLS record type 0x%02X", rtype)
		}

		toRead := recLen
		if toRead > len(p) {
			toRead = len(p)
		}
		n, err := f.conn.Read(p[:toRead])
		f.readLeft = recLen - n
		return n, err
	}
}

func (f *FakeTlsConn) Write(p []byte) (int, error) {
	var parts []byte
	offset := 0
	for offset < len(p) {
		end := offset + 16384
		if end > len(p) {
			end = len(p)
		}
		chunk := p[offset:end]
		hdr := []byte{0x17, 0x03, 0x03, 0, 0}
		binary.BigEndian.PutUint16(hdr[3:5], uint16(len(chunk)))
		parts = append(parts, hdr...)
		parts = append(parts, chunk...)
		offset = end
	}
	_, err := f.conn.Write(parts)
	return len(p), err
}

func (f *FakeTlsConn) Close() error                       { return f.conn.Close() }
func (f *FakeTlsConn) LocalAddr() net.Addr                { return f.conn.LocalAddr() }
func (f *FakeTlsConn) RemoteAddr() net.Addr               { return f.conn.RemoteAddr() }
func (f *FakeTlsConn) SetDeadline(t time.Time) error      { return f.conn.SetDeadline(t) }
func (f *FakeTlsConn) SetReadDeadline(t time.Time) error  { return f.conn.SetReadDeadline(t) }
func (f *FakeTlsConn) SetWriteDeadline(t time.Time) error { return f.conn.SetWriteDeadline(t) }

// PrefixConn solves the 1/256 disconnect bug gracefully
type PrefixConn struct {
	net.Conn
	prefix []byte
}

func (c *PrefixConn) Read(p []byte) (int, error) {
	if len(c.prefix) > 0 {
		n := copy(p, c.prefix)
		c.prefix = c.prefix[n:]
		return n, nil
	}
	return c.Conn.Read(p)
}

func handleClient(ctx context.Context, conn net.Conn) {
	stats.connectionsTotal.Add(1)
	stats.connectionsActive.Add(1)
	defer func() {
		if stats.connectionsActive.Load() > 0 {
			stats.connectionsActive.Add(-1)
		}
	}()
	peer := conn.RemoteAddr().String()
	label := peer

	setSockOpts(conn)

	defer conn.Close()
	stopClose := context.AfterFunc(ctx, func() { conn.Close() })
	defer stopClose()

	proxySecretMu.RLock()
	currentSecret := proxySecret
	proxySecretMu.RUnlock()
	secretBytes, _ := hex.DecodeString(currentSecret)

	fakeTlsMu.RLock()
	useFakeTls := fakeTlsEnabled
	fakeTlsMu.RUnlock()

	firstByte := make([]byte, 1)
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(conn, firstByte); err != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Time{})

	if firstByte[0] == 0x05 {
		if handleSocks5(ctx, conn) {
			return
		}
	}

	var clientConn net.Conn = conn
	var handshake []byte

	if useFakeTls && firstByte[0] == tlsRecordHandshake {
		hdrRest := make([]byte, 4)
		if _, err := io.ReadFull(conn, hdrRest); err != nil {
			return
		}
		tlsHeader := append(firstByte, hdrRest...)
		recordLen := int(binary.BigEndian.Uint16(tlsHeader[3:5]))

		if recordLen > 16384 {
			// Not TLS, gracefully fallback
			clientConn = &PrefixConn{Conn: conn, prefix: tlsHeader}
		} else {
			recordBody := make([]byte, recordLen)
			if _, err := io.ReadFull(conn, recordBody); err != nil {
				return
			}
			clientHello := append(tlsHeader, recordBody...)
			clientRandom, sessionId, ok := verifyClientHello(clientHello, secretBytes)
			if !ok {
				// FakeTLS failed, fallback gracefully (fixes 1/256 disconnect bug)
				clientConn = &PrefixConn{Conn: conn, prefix: clientHello}
			} else {
				serverHello := buildServerHello(secretBytes, clientRandom, sessionId)
				if _, err := conn.Write(serverHello); err != nil {
					return
				}
				clientConn = newFakeTlsConn(conn)
			}
		}
	} else {
		// Check for plain HTTP methods first (e.g. CONNECT, GET, POST) so we don't
		// block on io.ReadFull(..., 64) if the HTTP header is short.
		fb := firstByte[0]
		if fb == 'C' || fb == 'G' || fb == 'P' || fb == 'H' || fb == 'D' || fb == 'O' {
			httpBuf := make([]byte, 2048)
			httpBuf[0] = fb
			_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			n, err := conn.Read(httpBuf[1:])
			_ = conn.SetReadDeadline(time.Time{})
			if err == nil && n > 0 {
				readBytes := httpBuf[:1+n]
				if isHTTPTransport(readBytes) {
					handleHTTPProxy(ctx, conn, readBytes)
					return
				}
				clientConn = &PrefixConn{Conn: conn, prefix: readBytes}
			} else {
				return
			}
		} else {
			clientConn = &PrefixConn{Conn: conn, prefix: firstByte}
		}
	}

	handshake = make([]byte, 64)
	_ = clientConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(clientConn, handshake); err != nil {
		return
	}
	_ = clientConn.SetReadDeadline(time.Time{})

	if isHTTPTransport(handshake) {
		handleHTTPProxy(ctx, conn, handshake)
		return
	}

	cltDecPrekey := handshake[8:40]
	cltDecIv := handshake[40:56]
	hashDec := sha256.New()
	hashDec.Write(cltDecPrekey)
	hashDec.Write(secretBytes)
	cltDecryptor, _ := newAESCTR(hashDec.Sum(nil), cltDecIv)

	decrypted := make([]byte, 64)
	cltDecryptor.XORKeyStream(decrypted, handshake)

	protoTag := decrypted[56:60]
	proto := binary.LittleEndian.Uint32(protoTag)
	if !validProtos[proto] {
		stats.connectionsBad.Add(1)
		return
	}

	dcRaw := int16(binary.LittleEndian.Uint16(decrypted[60:62]))
	dc := int(dcRaw)
	if dc < 0 {
		dc = -dc
	}
	isMedia := dcRaw < 0
	mTag := mediaTag(isMedia)

	cltEncPrekeyAndIv := make([]byte, 48)
	for i := 0; i < 48; i++ {
		cltEncPrekeyAndIv[i] = handshake[8+47-i]
	}
	hashEnc := sha256.New()
	hashEnc.Write(cltEncPrekeyAndIv[:32])
	hashEnc.Write(secretBytes)
	cltEncryptor, _ := newAESCTR(hashEnc.Sum(nil), cltEncPrekeyAndIv[32:])

	relayInit := make([]byte, 64)
	for {
		rand.Read(relayInit)
		if relayInit[0] == 0xEF {
			continue
		}
		s := string(relayInit[:4])
		if s == "HEAD" || s == "POST" || s == "GET " || s == "\xee\xee\xee\xee" || s == "\xdd\xdd\xdd\xdd" {
			continue
		}
		if relayInit[0] == 0x16 && relayInit[1] == 0x03 && relayInit[2] == 0x01 && relayInit[3] == 0x02 {
			continue
		}
		if relayInit[4] == 0 && relayInit[5] == 0 && relayInit[6] == 0 && relayInit[7] == 0 {
			continue
		}
		break
	}

	tgDecPrekeyAndIv := make([]byte, 48)
	for i := 0; i < 48; i++ {
		tgDecPrekeyAndIv[i] = relayInit[8+47-i]
	}

	tgEncryptor, _ := newAESCTR(relayInit[8:40], relayInit[40:56])
	tgDecryptor, _ := newAESCTR(tgDecPrekeyAndIv[:32], tgDecPrekeyAndIv[32:])

	dcBytes := make([]byte, 2)
	dcIdx := dc
	if isMedia {
		dcIdx = -dc
	}
	binary.LittleEndian.PutUint16(dcBytes, uint16(dcIdx))

	tailPlain := make([]byte, 8)
	copy(tailPlain[0:4], protoTag)
	copy(tailPlain[4:6], dcBytes)
	rand.Read(tailPlain[6:8])

	encryptedFull := make([]byte, 64)
	tgEncryptor.XORKeyStream(encryptedFull, relayInit)

	keystreamTail := make([]byte, 8)
	for i := 0; i < 8; i++ {
		keystreamTail[i] = encryptedFull[56+i] ^ relayInit[56+i]
		relayInit[56+i] = tailPlain[i] ^ keystreamTail[i]
	}

	dcKey := [2]int{dc, isMediaInt(isMedia)}
	now := float64(time.Now().UnixNano()) / 1e9

	splitter, _ := newMsgSplitter(relayInit, proto)

	target, dcConfigured := resolveConfiguredTarget(dc, isMedia)

	wsBlackMu.RLock()
	blacklisted := wsBlacklist[dcKey]
	wsBlackMu.RUnlock()

	if !dcConfigured || blacklisted {
		doFallback(ctx, clientConn, relayInit, label, dc, isMedia, splitter, cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
		return
	}

	// Register this slot so the background pool maintainer can keep it warm.
	domains := wsDomains(dc, isMedia)
	slot := dcSlot{dc, isMediaInt(isMedia)}
	registerPoolSlot(slot, target, domains)

	dcFailMu.RLock()
	failUntil := dcFailUntil[dcKey]
	dcFailMu.RUnlock()

	wsTimeout := 10.0
	if now < failUntil {
		wsTimeout = wsFailTimeout
	}

	var ws *RawWebSocket
	var wsFailedRedirect, allRedirects bool
	if now >= failUntil {
		ws = wsPool.Get(ctx, dc, isMedia, target, domains)
	}
	if ws == nil {
		ws, wsFailedRedirect, allRedirects = connectDirectWS(ctx, target, domains, wsTimeout)
	}

	if ws == nil {
		logWarn.Printf(" DC%d%s: all WS attempts failed (DPI/Internet)", dc, mTag)
		if wsFailedRedirect && allRedirects {
			wsBlackMu.Lock()
			wsBlacklist[dcKey] = true
			wsBlackMu.Unlock()
			logWarn.Printf(" DC%d%s blacklisted (302)", dc, mTag)
		} else {
			dcFailMu.Lock()
			strikes := dcFailStrikes[dcKey] + 1
			dcFailStrikes[dcKey] = strikes
			// Exponential backoff: 3s, 6s, 12s, 24s, max 60s
			backoff := math.Min(3.0*math.Pow(2, float64(strikes-1)), 60.0)
			dcFailUntil[dcKey] = now + backoff
			dcFailMu.Unlock()
		}

		splitterFb, _ := newMsgSplitter(relayInit, proto)
		doFallback(ctx, clientConn, relayInit, label, dc, isMedia, splitterFb, cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
		return
	}

	sendDirectInit := func(activeWS *RawWebSocket) error {
		if err := activeWS.Send(relayInit); err != nil {
			return err
		}
		logDebug.Printf(" direct relayInit sent DC%d%s", dc, mTag)
		return nil
	}

	if err := sendDirectInit(ws); err != nil {
		logWarn.Printf(" direct relayInit write fail DC%d%s: %s", dc, mTag, compactConnError(err))
		ws.Close()

		dcFailMu.Lock()
		dcFailUntil[dcKey] = now + dcFailCooldown
		dcFailMu.Unlock()

		logWarn.Printf(" direct retry fresh ws DC%d%s", dc, mTag)
		retryWS, retryFailedRedirect, retryAllRedirects := connectDirectWS(ctx, target, domains, wsTimeout)
		if retryWS == nil {
			if retryFailedRedirect && retryAllRedirects {
				wsBlackMu.Lock()
				wsBlacklist[dcKey] = true
				wsBlackMu.Unlock()
				logWarn.Printf(" DC%d%s blacklisted (302)", dc, mTag)
			}
			logWarn.Printf(" direct fallback DC%d%s", dc, mTag)
			splitterFb, _ := newMsgSplitter(relayInit, proto)
			doFallback(ctx, clientConn, relayInit, label, dc, isMedia, splitterFb, cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
			return
		}

		ws = retryWS
		if err = sendDirectInit(ws); err != nil {
			logWarn.Printf(" direct relayInit write fail DC%d%s: %s", dc, mTag, compactConnError(err))
			ws.Close()
			logWarn.Printf(" direct fallback DC%d%s", dc, mTag)
			splitterFb, _ := newMsgSplitter(relayInit, proto)
			doFallback(ctx, clientConn, relayInit, label, dc, isMedia, splitterFb, cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
			return
		}
	}

	dcFailMu.Lock()
	delete(dcFailUntil, dcKey)
	delete(dcFailStrikes, dcKey)
	dcFailMu.Unlock()

	stats.connectionsWs.Add(1)

	bridgeWS(ctx, clientConn, ws, label, dc, target, 443, isMedia, splitter, cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

func runProxy(ctx context.Context, host string, port int, dcOptMap map[int]string, started chan<- error) error {
	dcOptMu.Lock()
	dcOpt = dcOptMap
	dcOptMu.Unlock()

	addr := net.JoinHostPort(host, strconv.Itoa(port))
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			var err error
			controlErr := c.Control(func(fd uintptr) {
				err = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
				if err != nil {
					return
				}
				err = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, 15, 1)
			})
			if controlErr != nil {
				return controlErr
			}
			return err
		},
	}

	listener, err := lc.Listen(ctx, "tcp", addr)
	if err != nil {
		signalProxyStart(started, fmt.Errorf("listen on %s: %w", addr, err))
		return fmt.Errorf("listen on %s: %w", addr, err)
	}
	signalProxyStart(started, nil)

	srvCtx, srvCancel := context.WithCancel(ctx)
	defer srvCancel()

	if cfproxyEnabled { startCfproxyRefresh(srvCtx) }
	startPoolMaintainer(srvCtx)

	logInfo.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
	logInfo.Println("  TG WS Proxy started")
	logInfo.Printf("  Address: %s:%d", host, port)

	go func() {
		ticker := time.NewTicker(60 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-srvCtx.Done():
				return
			case <-ticker.C:
				logInfo.Printf(" %s", stats.SummaryRu())
			}
		}
	}()

	var activeConns sync.WaitGroup

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				select {
				case <-srvCtx.Done():
					return
				default:
					if ne, ok := err.(net.Error); ok && ne.Timeout() {
						continue
					}
					return
				}
			}
			activeConns.Add(1)
			go func() {
				defer activeConns.Done()
				handleClient(srvCtx, conn)
			}()
		}
	}()

	<-srvCtx.Done()
	_ = listener.Close()

	done := make(chan struct{})
	go func() {
		activeConns.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		logWarn.Println("Some connections did not finish in time, forcing shutdown")
	}

	wsPool.CloseAll()
    dohClient.CloseIdleConnections()
    githubClient.CloseIdleConnections()
	return nil
}

func parseCIDRPool(cidrsStr string) (map[int]string, error) {
	result := make(map[int]string)
	if strings.TrimSpace(cidrsStr) == "" {
		return result, nil
	}
	pairs := strings.Split(cidrsStr, ",")
	for _, pair := range pairs {
		parts := strings.Split(pair, ":")
		if len(parts) == 2 {
			dcRaw := strings.TrimSpace(parts[0])
			ipRaw := strings.TrimSpace(parts[1])
			if dc, err := strconv.Atoi(dcRaw); err == nil && ipRaw != "" {
				if parsedIP := net.ParseIP(ipRaw); parsedIP != nil {
					result[dc] = parsedIP.String()
				}
			}
		}
	}
	return result, nil
}

func signalProxyStart(started chan<- error, err error) {
	if started == nil {
		return
	}
	select {
	case started <- err:
	default:
	}
}

// ---------------------------------------------------------------------------
// CGO exports
// ---------------------------------------------------------------------------

var (
	globalCtx    context.Context
	globalCancel context.CancelFunc
	globalMu     sync.Mutex
	globalDone   chan struct{}
)

//export StartProxy
func StartProxy(cHost *C.char, port C.int, cDcIps *C.char, cSecret *C.char, verbose C.int) C.int {
	globalMu.Lock()
	defer globalMu.Unlock()

	if globalCancel != nil {
		return -1
	}

	host := C.GoString(cHost)
	goPort := int(port)
	dcIpsStr := C.GoString(cDcIps)
	secretStr := C.GoString(cSecret)
	isVerbose := int(verbose) != 0

	initLogging(isVerbose)
	clearCfproxy429Cooldowns()

	if len(secretStr) == 32 {
		if _, err := hex.DecodeString(secretStr); err == nil {
			proxySecretMu.Lock()
			proxySecret = secretStr
			proxySecretMu.Unlock()
		}
	}

	initCfproxyDomains()

	dcOptMap, err := parseCIDRPool(dcIpsStr)
	if err != nil {
		return -2
	}

	globalCtx, globalCancel = context.WithCancel(context.Background())
	started := make(chan error, 1)

	globalDone = make(chan struct{})
	ctx := globalCtx
	done := globalDone
	go func() {
		defer close(done)
		_ = runProxy(ctx, host, goPort, dcOptMap, started)
	}()

	if err := <-started; err != nil {
		globalCancel()
		globalCancel = nil
		globalCtx = nil
		return -3
	}

	return 0
}

//export StopProxy
func StopProxy() C.int {
	globalMu.Lock()
	defer globalMu.Unlock()

	if globalCancel == nil {
		return -1
	}

	globalCancel()
	if globalDone != nil { <-globalDone; globalDone = nil }
	globalCancel = nil
	globalCtx = nil

	stats.Reset()

	wsBlackMu.Lock()
	wsBlacklist = make(map[[2]int]bool)
	wsBlackMu.Unlock()

	dcFailMu.Lock()
	dcFailUntil = make(map[[2]int]float64)
	dcFailStrikes = make(map[[2]int]int)
	dcFailMu.Unlock()

	poolKnownSlots.Range(func(k, _ interface{}) bool {
		poolKnownSlots.Delete(k)
		return true
	})

	clearCfproxy429Cooldowns()


	return 0
}

//export SetPoolSize
func SetPoolSize(size C.int) {
	n := int32(size)
	if n < 2 {
		n = 2
	}
	if n > 16 {
		n = 16
	}
	poolSize.Store(n)
}

//export SetRecvBuffer
func SetRecvBuffer(size C.int) {
	n := int(size)
	if n < 16 {
		n = 16
	}
	if n > 2048 {
		n = 2048
	}
	recvBuf = n * 1024
}

//export SetSendBuffer
func SetSendBuffer(size C.int) {
	n := int(size)
	if n < 16 {
		n = 16
	}
	if n > 2048 {
		n = 2048
	}
	sendBuf = n * 1024
}

//export SetCfProxyCacheDir
func SetCfProxyCacheDir(cCacheDir *C.char) {
	cfproxyMu.Lock()
	cfproxyCacheDir = strings.TrimSpace(C.GoString(cCacheDir))
	cfproxyMu.Unlock()
}

//export SetCfProxyConfig
func SetCfProxyConfig(enabled C.int, priority C.int, cUserDomain *C.char) {
	cfproxyMu.Lock()
	defer cfproxyMu.Unlock()

	cfproxyEnabled = int(enabled) != 0

	userDomain := C.GoString(cUserDomain)
	cfproxyUserDomain = userDomain

	if userDomain != "" {
		cfproxyDomains = []string{userDomain}
		activeCfDomain = userDomain
	}
}

//export SetSecret
func SetSecret(cSecret *C.char) {
	s := C.GoString(cSecret)
	if len(s) != 32 {
		return
	}
	if _, err := hex.DecodeString(s); err != nil {
		return
	}
	proxySecretMu.Lock()
	proxySecret = s
	proxySecretMu.Unlock()
}

//export GetStats
func GetStats() *C.char {
	return C.CString(stats.Summary())
}

//export SetFakeTls
func SetFakeTls(enabled C.int, cDomain *C.char) {
	fakeTlsMu.Lock()
	defer fakeTlsMu.Unlock()

	fakeTlsEnabled = int(enabled) != 0
	fakeTlsDomain = C.GoString(cDomain)
}

//export GetSecretWithPrefix
func GetSecretWithPrefix() *C.char {
	proxySecretMu.RLock()
	sec := proxySecret
	proxySecretMu.RUnlock()

	fakeTlsMu.RLock()
	tlsOn := fakeTlsEnabled
	tlsDom := fakeTlsDomain
	fakeTlsMu.RUnlock()

	var result string
	if tlsOn && tlsDom != "" {
		domHex := hex.EncodeToString([]byte(tlsDom))
		result = "ee" + sec + domHex
	} else {
		result = "dd" + sec
	}
	return C.CString(result)
}

//export FreeString
func FreeString(p *C.char) {
	C.free(unsafe.Pointer(p))
}

// ResetForNetworkChange resets all connection state when the Android network
// interface changes (WiFi ↔ LTE). Pool connections are bound to the old
// interface and will silently fail; this flushes them immediately so new
// connections are established on the correct interface without a full restart.
//
//export ResetForNetworkChange
func ResetForNetworkChange() {
	globalMu.Lock()
	running := globalCancel != nil
	globalMu.Unlock()
	if !running {
		return
	}

	// Close all pooled WebSocket connections — they are bound to the old interface.
	wsPool.CloseAll()

	// Clear DC fail cooldowns so the new interface gets a fresh attempt.
	dcFailMu.Lock()
	dcFailUntil = make(map[[2]int]float64)
	dcFailStrikes = make(map[[2]int]int)
	dcFailMu.Unlock()

	// Clear the WS blacklist — redirects may behave differently on the new network.
	wsBlackMu.Lock()
	wsBlacklist = make(map[[2]int]bool)
	wsBlackMu.Unlock()

	// Clear CF 429 cooldowns — rate limits are per-IP and the IP just changed.
	clearCfproxy429Cooldowns()

	// Invalidate DoH cache — resolved IPs may differ on the new network.
	dohCache.Range(func(key, _ interface{}) bool {
		dohCache.Delete(key)
		return true
	})

	logInfo.Println(" Network change: pool flushed, cooldowns cleared")
}

// ResetCooldowns clears all 429 cooldowns, DC fail counters, and the WS blacklist
// without a full proxy restart. Call from Java when the user taps "refresh" or
// when the watchdog detects degraded quality during peak hours.
//
//export ResetCooldowns
func ResetCooldowns() {
	globalMu.Lock()
	running := globalCancel != nil
	globalMu.Unlock()
	if !running {
		return
	}

	clearCfproxy429Cooldowns()

	dcFailMu.Lock()
	dcFailUntil = make(map[[2]int]float64)
	dcFailStrikes = make(map[[2]int]int)
	dcFailMu.Unlock()

	wsBlackMu.Lock()
	wsBlacklist = make(map[[2]int]bool)
	wsBlackMu.Unlock()

	// Invalidate DoH cache so fresh DNS lookups happen on new attempts
	dohCache.Range(func(key, _ interface{}) bool {
		dohCache.Delete(key)
		return true
	})

	logInfo.Println(" ResetCooldowns: all cooldowns, blacklists and DoH cache cleared")
}

func main() {
	runtime.LockOSThread()
	initLogging(true)
	initCfproxyDomains()

	dcOptMap := map[int]string{
		2: "149.154.167.220",
		4: "149.154.167.220",
	}

	ctx, cancel := context.WithCancel(context.Background())
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		cancel()
	}()

	_ = runProxy(ctx, "127.0.0.1", defaultPort, dcOptMap, nil)
}
