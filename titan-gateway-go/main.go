package main

import (
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/time/rate"
	"gopkg.in/yaml.v3"
)

// ─── Config ───────────────────────────────────────────────

type Config struct {
	Upstreams struct {
		CoreBanking  string `yaml:"core_banking"`
		Notification string `yaml:"notification"`
		Promotion    string `yaml:"promotion"`
		AIService    string `yaml:"ai_service"`
	} `yaml:"upstreams"`
	Auth struct {
		JWTSecret string `yaml:"jwt_secret"`
	} `yaml:"auth"`
	RateLimit struct {
		RequestsPerSecond int `yaml:"requests_per_second"`
	} `yaml:"rate_limit"`
	Server struct {
		Port string `yaml:"port"`
	} `yaml:"server"`
}

func loadConfig() *Config {
	path := "config.yaml"
	if p := os.Getenv("CONFIG_PATH"); p != "" {
		path = p
	}
	data, err := os.ReadFile(path)
	if err != nil {
		log.Fatalf("failed to read config: %v", err)
	}
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		log.Fatalf("failed to parse config: %v", err)
	}
	if cfg.Server.Port == "" {
		cfg.Server.Port = "8088"
	}
	return &cfg
}

// ─── Rate Limiter (per-IP) ────────────────────────────────

type ipLimiter struct {
	mu       sync.Mutex
	limiters map[string]*rate.Limiter
	rps      rate.Limit
}

func newIPLimiter(rps int) *ipLimiter {
	return &ipLimiter{
		limiters: make(map[string]*rate.Limiter),
		rps:      rate.Limit(rps),
	}
}

func (l *ipLimiter) get(ip string) *rate.Limiter {
	l.mu.Lock()
	defer l.mu.Unlock()
	if lim, ok := l.limiters[ip]; ok {
		return lim
	}
	lim := rate.NewLimiter(l.rps, int(l.rps))
	l.limiters[ip] = lim
	return lim
}

// ─── Middleware ───────────────────────────────────────────

func jwtMiddleware(secret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		tokenStr, found := strings.CutPrefix(authHeader, "Bearer ")
		if !found || tokenStr == "" {
			http.Error(w, "Missing Authorization header", http.StatusUnauthorized)
			return
		}
		_, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
			return []byte(secret), nil
		})
		if err != nil {
			http.Error(w, "Invalid token", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func rateLimitMiddleware(lim *ipLimiter, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := r.RemoteAddr
		if i := strings.LastIndex(ip, ":"); i != -1 {
			ip = ip[:i]
		}
		if !lim.get(ip).Allow() {
			http.Error(w, "Rate limit exceeded", http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s %v", r.RemoteAddr, r.Method, r.URL.Path, time.Since(start))
	})
}

// ─── Proxy ────────────────────────────────────────────────

func newProxy(target string) http.Handler {
	u, err := url.Parse(target)
	if err != nil {
		log.Fatalf("invalid upstream %q: %v", target, err)
	}
	return httputil.NewSingleHostReverseProxy(u)
}

// ─── Main ─────────────────────────────────────────────────

func main() {
	cfg := loadConfig()
	lim := newIPLimiter(cfg.RateLimit.RequestsPerSecond)

	mux := http.NewServeMux()
	mux.Handle("/api/transactions/", newProxy(cfg.Upstreams.CoreBanking))
	mux.Handle("/api/notifications/", newProxy(cfg.Upstreams.Notification))
	mux.Handle("/api/promotions/", newProxy(cfg.Upstreams.Promotion))
	mux.Handle("/api/ai/", newProxy(cfg.Upstreams.AIService))

	handler := loggingMiddleware(
		rateLimitMiddleware(lim,
			jwtMiddleware(cfg.Auth.JWTSecret, mux),
		),
	)

	addr := "0.0.0.0:" + cfg.Server.Port
	log.Printf("titan-gateway-go listening on %s", addr)
	if err := http.ListenAndServe(addr, handler); err != nil {
		log.Fatal(err)
	}
}
