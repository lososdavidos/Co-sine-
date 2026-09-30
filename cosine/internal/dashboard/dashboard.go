// Package dashboard is Cosine's admin web UI (§5.3): first-run setup,
// accounts, Store and Inbox settings, and ingest status. No playback.
package dashboard

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"html/template"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
)

//go:embed templates/*.html
var templateFS embed.FS

const cookieName = "cosine_session"

type Dashboard struct {
	DB       *db.DB
	Auth     *auth.Service
	Ingester *ingest.Ingester
	// OnInboxChange restarts the Inbox watcher on a new path.
	OnInboxChange func(path string)
	Log           *slog.Logger

	pages map[string]*template.Template
}

type page struct {
	Title string
	User  *auth.User
	Error string
	Done  string
	Form  map[string]string
	Data  any
}

func (d *Dashboard) Handler() http.Handler {
	d.pages = map[string]*template.Template{}
	for _, name := range []string{"setup", "login", "status", "users", "settings"} {
		d.pages[name] = template.Must(template.ParseFS(templateFS, "templates/layout.html", "templates/"+name+".html"))
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /setup", d.setupForm)
	mux.HandleFunc("POST /setup", d.setup)
	mux.HandleFunc("GET /login", d.loginForm)
	mux.HandleFunc("POST /login", d.login)
	mux.HandleFunc("POST /logout", d.logout)
	mux.HandleFunc("GET /{$}", d.admin(d.status))
	mux.HandleFunc("GET /users", d.admin(d.users))
	mux.HandleFunc("POST /users", d.admin(d.createUser))
	mux.HandleFunc("POST /users/{id}/delete", d.admin(d.deleteUser))
	mux.HandleFunc("GET /settings", d.admin(d.settings))
	mux.HandleFunc("POST /settings/store", d.admin(d.moveStore))
	mux.HandleFunc("POST /settings/inbox", d.admin(d.changeInbox))
	return d.guard(mux)
}

// guard sends everything to /setup until an account exists, and rejects
// cross-site form posts.
func (d *Dashboard) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && !sameOrigin(r) {
			http.Error(w, "Cross-site request refused.", http.StatusForbidden)
			return
		}
		has, err := d.Auth.HasUsers(r.Context())
		if err != nil {
			http.Error(w, "Database error.", http.StatusInternalServerError)
			return
		}
		switch {
		case !has && r.URL.Path != "/setup":
			http.Redirect(w, r, "/setup", http.StatusSeeOther)
			return
		case has && r.URL.Path == "/setup":
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func sameOrigin(r *http.Request) bool {
	src := r.Header.Get("Origin")
	if src == "" {
		src = r.Header.Get("Referer")
	}
	if src == "" {
		return true // not a browser form post
	}
	u, err := url.Parse(src)
	return err == nil && u.Host == r.Host
}

type authed func(w http.ResponseWriter, r *http.Request, u auth.User)

// admin requires a session belonging to an admin account (Q17: the dashboard is admin-only).
func (d *Dashboard) admin(h authed) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(cookieName)
		if err != nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		u, err := d.Auth.Session(r.Context(), c.Value)
		if err != nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		if !u.IsAdmin {
			d.render(w, "login", page{Title: "Log in", Error: "The dashboard is for admin accounts."})
			return
		}
		h(w, r, u)
	}
}

func (d *Dashboard) render(w http.ResponseWriter, name string, p page) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if p.Error != "" {
		w.WriteHeader(http.StatusBadRequest)
	}
	if err := d.pages[name].ExecuteTemplate(w, "layout", p); err != nil {
		d.Log.Error("render", "page", name, "err", err)
	}
}

func done(w http.ResponseWriter, r *http.Request, to, msg string) {
	http.Redirect(w, r, to+"?done="+url.QueryEscape(msg), http.StatusSeeOther)
}

func (d *Dashboard) startSession(w http.ResponseWriter, r *http.Request, u auth.User) error {
	token, err := d.Auth.NewSession(r.Context(), u.ID)
	if err != nil {
		return err
	}
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: token, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode, Secure: r.TLS != nil,
		Expires: time.Now().Add(30 * 24 * time.Hour),
	})
	return nil
}

// ---------------------------------------------------------------- setup

func (d *Dashboard) setupForm(w http.ResponseWriter, r *http.Request) {
	d.render(w, "setup", page{Title: "Set up Cosine", Form: map[string]string{}})
}

// setup is the guided first run (Q58): Store, Inbox, admin. No config files.
func (d *Dashboard) setup(w http.ResponseWriter, r *http.Request) {
	form := map[string]string{
		"store":    strings.TrimSpace(r.FormValue("store")),
		"inbox":    strings.TrimSpace(r.FormValue("inbox")),
		"username": strings.TrimSpace(r.FormValue("username")),
	}
	fail := func(msg string) {
		d.render(w, "setup", page{Title: "Set up Cosine", Form: form, Error: msg})
	}
	if r.FormValue("password") != r.FormValue("password2") {
		fail("The passwords do not match.")
		return
	}
	if err := checkPaths(form["store"], form["inbox"]); err != nil {
		fail(err.Error())
		return
	}
	ctx := r.Context()
	u, err := d.Auth.CreateUser(ctx, form["username"], r.FormValue("password"), true)
	if err != nil {
		fail(err.Error())
		return
	}
	if err := d.DB.SetSetting(ctx, db.SettingStorePath, filepath.Clean(form["store"])); err != nil {
		fail(err.Error())
		return
	}
	if err := d.DB.SetSetting(ctx, db.SettingInboxPath, filepath.Clean(form["inbox"])); err != nil {
		fail(err.Error())
		return
	}
	if d.OnInboxChange != nil {
		d.OnInboxChange(filepath.Clean(form["inbox"]))
	}
	d.Log.Info("setup complete", "admin", u.Name, "store", form["store"], "inbox", form["inbox"])
	if err := d.startSession(w, r, u); err != nil {
		fail(err.Error())
		return
	}
	done(w, r, "/", "Cosine is set up. Drop audio files into the Inbox to add music.")
}

// checkPaths validates a Store and Inbox pair: absolute, writable, distinct,
// and not nested — an Inbox inside the Store would ingest the Store into itself.
func checkPaths(storePath, inboxPath string) error {
	for _, p := range []struct{ name, path string }{{"Store", storePath}, {"Inbox", inboxPath}} {
		if err := writableDir(p.path); err != nil {
			return fmt.Errorf("%s: %w", p.name, err)
		}
	}
	return checkApart(storePath, inboxPath)
}

func checkApart(a, b string) error {
	a, b = filepath.Clean(a), filepath.Clean(b)
	if a == b || within(a, b) || within(b, a) {
		return errors.New("the Store and the Inbox must be separate folders, neither inside the other")
	}
	return nil
}

func within(child, parent string) bool {
	rel, err := filepath.Rel(parent, child)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func writableDir(path string) error {
	if !filepath.IsAbs(path) {
		return errors.New("use an absolute path")
	}
	if err := os.MkdirAll(path, 0o755); err != nil {
		return fmt.Errorf("cannot create it: %w", err)
	}
	f, err := os.CreateTemp(path, ".cosine-write-test-*")
	if err != nil {
		return fmt.Errorf("cannot write to it: %w", err)
	}
	f.Close()
	return os.Remove(f.Name())
}

// ---------------------------------------------------------------- login

func (d *Dashboard) loginForm(w http.ResponseWriter, r *http.Request) {
	d.render(w, "login", page{Title: "Log in", Form: map[string]string{}})
}

func (d *Dashboard) login(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.FormValue("username"))
	u, err := d.Auth.CheckPassword(r.Context(), name, r.FormValue("password"))
	if err != nil {
		d.render(w, "login", page{Title: "Log in", Form: map[string]string{"username": name}, Error: "Wrong username or password."})
		return
	}
	if !u.IsAdmin {
		d.render(w, "login", page{Title: "Log in", Form: map[string]string{"username": name}, Error: "The dashboard is for admin accounts."})
		return
	}
	if err := d.startSession(w, r, u); err != nil {
		http.Error(w, "Could not start a session.", http.StatusInternalServerError)
		return
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (d *Dashboard) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(cookieName); err == nil {
		d.Auth.EndSession(r.Context(), c.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: "", Path: "/", MaxAge: -1})
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// ---------------------------------------------------------------- status

type job struct {
	When, Source, Input, Status, Detail string
}

type statusData struct {
	Store, Inbox                            string
	Tracks, Objects, Review, Missing, Users int
	Size                                    string
	Jobs                                    []job
}

func (d *Dashboard) status(w http.ResponseWriter, r *http.Request, u auth.User) {
	ctx := r.Context()
	var s statusData
	s.Store, _ = d.DB.Setting(ctx, db.SettingStorePath)
	s.Inbox, _ = d.DB.Setting(ctx, db.SettingInboxPath)
	var bytes int64
	d.DB.QueryRowContext(ctx, `SELECT
		(SELECT COUNT(*) FROM tracks),
		(SELECT COUNT(*) FROM objects),
		(SELECT COALESCE(SUM(size), 0) FROM objects),
		(SELECT COUNT(*) FROM tracks WHERE reviewed = 0),
		(SELECT COUNT(*) FROM objects WHERE missing = 1),
		(SELECT COUNT(*) FROM users)`).Scan(&s.Tracks, &s.Objects, &bytes, &s.Review, &s.Missing, &s.Users)
	s.Size = humanBytes(bytes)

	rows, err := d.DB.QueryContext(ctx,
		"SELECT created_at, source, input, status, COALESCE(error, '') FROM ingest_jobs ORDER BY id DESC LIMIT 25")
	if err == nil {
		for rows.Next() {
			var j job
			var at int64
			if rows.Scan(&at, &j.Source, &j.Input, &j.Status, &j.Detail) == nil {
				j.When = time.UnixMilli(at).Format("2006-01-02 15:04")
				s.Jobs = append(s.Jobs, j)
			}
		}
		rows.Close()
	}
	d.render(w, "status", page{Title: "Status", User: &u, Done: r.URL.Query().Get("done"), Data: s})
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}

// ---------------------------------------------------------------- accounts

type userRow struct {
	auth.User
	Tracks int
	Self   bool
}

func (d *Dashboard) users(w http.ResponseWriter, r *http.Request, u auth.User) {
	d.renderUsers(w, r, u, "")
}

func (d *Dashboard) renderUsers(w http.ResponseWriter, r *http.Request, u auth.User, errMsg string) {
	ctx := r.Context()
	list, err := d.Auth.Users(ctx)
	if err != nil {
		http.Error(w, "Database error.", http.StatusInternalServerError)
		return
	}
	rows := make([]userRow, 0, len(list))
	for _, x := range list {
		row := userRow{User: x, Self: x.ID == u.ID}
		d.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM pointers WHERE user_id = ?", x.ID).Scan(&row.Tracks)
		rows = append(rows, row)
	}
	d.render(w, "users", page{Title: "Accounts", User: &u, Error: errMsg, Done: r.URL.Query().Get("done"),
		Data: struct{ Users []userRow }{rows}})
}

func (d *Dashboard) createUser(w http.ResponseWriter, r *http.Request, u auth.User) {
	nu, err := d.Auth.CreateUser(r.Context(), r.FormValue("username"), r.FormValue("password"), r.FormValue("admin") != "")
	if err != nil {
		d.renderUsers(w, r, u, err.Error())
		return
	}
	done(w, r, "/users", "Created account "+nu.Name+".")
}

func (d *Dashboard) deleteUser(w http.ResponseWriter, r *http.Request, u auth.User) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if id == u.ID {
		d.renderUsers(w, r, u, "You cannot delete the account you are logged in with.")
		return
	}
	if err := d.Auth.DeleteUser(r.Context(), id); err != nil {
		d.renderUsers(w, r, u, err.Error())
		return
	}
	done(w, r, "/users", "Account deleted. Its music stays in the Store.")
}

// ---------------------------------------------------------------- settings

type settingsData struct{ Store, Inbox string }

func (d *Dashboard) settings(w http.ResponseWriter, r *http.Request, u auth.User) {
	d.renderSettings(w, r, u, "")
}

func (d *Dashboard) renderSettings(w http.ResponseWriter, r *http.Request, u auth.User, errMsg string) {
	var s settingsData
	s.Store, _ = d.DB.Setting(r.Context(), db.SettingStorePath)
	s.Inbox, _ = d.DB.Setting(r.Context(), db.SettingInboxPath)
	d.render(w, "settings", page{Title: "Settings", User: &u, Error: errMsg, Done: r.URL.Query().Get("done"), Data: s})
}

func (d *Dashboard) moveStore(w http.ResponseWriter, r *http.Request, u auth.User) {
	ctx := r.Context()
	path := filepath.Clean(strings.TrimSpace(r.FormValue("path")))
	inbox, _ := d.DB.Setting(ctx, db.SettingInboxPath)
	if err := writableDir(path); err != nil {
		d.renderSettings(w, r, u, "Store: "+err.Error())
		return
	}
	if err := checkApart(path, inbox); err != nil {
		d.renderSettings(w, r, u, err.Error())
		return
	}
	// Moving can take a while on a big library; it outlives a closed tab.
	moved, err := d.Ingester.Relocate(context.WithoutCancel(ctx), path)
	if err != nil {
		d.renderSettings(w, r, u, fmt.Sprintf("Moved %d files, then stopped: %v. Saving again resumes.", moved, err))
		return
	}
	done(w, r, "/settings", fmt.Sprintf("Store moved: %d files.", moved))
}

func (d *Dashboard) changeInbox(w http.ResponseWriter, r *http.Request, u auth.User) {
	ctx := r.Context()
	path := filepath.Clean(strings.TrimSpace(r.FormValue("path")))
	storePath, _ := d.DB.Setting(ctx, db.SettingStorePath)
	if err := writableDir(path); err != nil {
		d.renderSettings(w, r, u, "Inbox: "+err.Error())
		return
	}
	if err := checkApart(storePath, path); err != nil {
		d.renderSettings(w, r, u, err.Error())
		return
	}
	if err := d.DB.SetSetting(ctx, db.SettingInboxPath, path); err != nil {
		d.renderSettings(w, r, u, err.Error())
		return
	}
	if d.OnInboxChange != nil {
		d.OnInboxChange(path)
	}
	done(w, r, "/settings", "Inbox changed.")
}
